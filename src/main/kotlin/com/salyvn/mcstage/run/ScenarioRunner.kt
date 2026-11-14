package com.salyvn.mcstage.run

import com.salyvn.mcstage.core.Durations
import com.salyvn.mcstage.core.JavaRuntime
import com.salyvn.mcstage.core.PortProbe
import com.salyvn.mcstage.instance.Instance
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

/** Told what is happening while a scenario runs, so the CLI can print it as it goes. */
interface RunListener {
    fun onCaseStart(case: Scenario.Case) {}
    fun onEvent(message: String) {}
    fun onLine(line: LogLine) {}
    fun onStep(result: StepResult) {}
    fun onCaseEnd(result: CaseResult) {}
}

/**
 * Runs a scenario: for each platform and version, boot a server, drive its console, and decide.
 *
 * Cases run one after another rather than at once. Two servers on one machine compete for CPU and disk,
 * and a test that fails because it was starved is worse than a test that took longer.
 *
 * An instance directory is reused between runs of the same case. The world, and therefore the second
 * boot, is faster; and when something fails the directory is still there afterwards with the server's own
 * logs in it.
 */
class ScenarioRunner(
    private val preparer: CasePreparer,
    private val listener: RunListener = object : RunListener {},
    private val javaOverride: String? = null,
    private val basePort: Int = 25565,
    private val launcher: ServerLauncher = ServerLauncher.REAL,
) {

    fun run(scenario: Scenario): RunReport {
        val java = JavaRuntime.detect(javaOverride ?: scenario.java)
        listener.onEvent("java ${java.describe} at ${java.binary}")

        var nextPreferred = basePort
        val results = scenario.cases.map { case ->
            listener.onCaseStart(case)
            val allocated = PortProbe.allocateFreePort(if (basePort <= 0) 0 else nextPreferred)
            if (basePort > 0 && allocated != nextPreferred) {
                listener.onEvent("port $nextPreferred is busy on loopback; using free port $allocated")
            }
            if (basePort > 0) {
                nextPreferred = maxOf(nextPreferred + 1, allocated + 1)
            }
            val result = runCase(scenario, case, java, allocated)
            listener.onCaseEnd(result)
            result
        }

        return RunReport(
            tool = com.salyvn.mcstage.core.BuildInfo.NAME,
            toolVersion = com.salyvn.mcstage.core.BuildInfo.version,
            scenario = scenario.name,
            startedAt = Instant.now(),
            cases = results,
        )
    }

    private fun runCase(scenario: Scenario, case: Scenario.Case, java: JavaRuntime, port: Int): CaseResult {
        val startedAt = System.nanoTime()
        fun elapsed() = Duration.ofNanos(System.nanoTime() - startedAt)

        val instance: Instance
        val facts = linkedMapOf<String, String>()
        try {
            val prepared = preparer.prepare(case, scenario, port, listener::onEvent)
            instance = prepared.instance
            facts.putAll(prepared.facts)
            facts["java"] = java.describe
            facts["port"] = port.toString()
            prepared.notes.forEach(listener::onEvent)
        } catch (e: Exception) {
            return CaseResult(
                case = case,
                outcome = Outcome.ERROR,
                elapsed = elapsed(),
                failure = e.message ?: e::class.simpleName,
                facts = facts,
            )
        }

        if (!instance.eulaAccepted()) {
            return CaseResult(
                case = case,
                outcome = Outcome.ERROR,
                elapsed = elapsed(),
                failure = "The Minecraft EULA has not been accepted for ${instance.dir}. Pass --accept-eula " +
                    "to have mcstage write eula.txt, or write it yourself. mcstage never accepts it for you.",
                facts = facts,
            )
        }

        val server = ServerProcess(launcher.commandFor(instance, scenario, java), instance.dir)
        val watch = LineWatch(scenario.forbid)
        val steps = mutableListOf<StepResult>()

        fun consume(line: LogLine) {
            watch.inspect(line)
            listener.onLine(line)
        }

        try {
            server.start()
        } catch (e: Exception) {
            return CaseResult(case, Outcome.ERROR, elapsed(), steps, e.message, facts)
        }

        var failure: String? = null
        val readyAt: Long

        val ready = server.await(scenario.ready, scenario.readyTimeout.toMillis(), ::consume)
        readyAt = when (ready) {
            is WaitResult.Matched -> ready.line.elapsedMillis
            is WaitResult.TimedOut -> {
                failure = "The server did not report itself ready within " +
                    "${Durations.format(scenario.readyTimeout)} (waiting for ${scenario.ready.describe})."
                -1
            }

            is WaitResult.Exited -> {
                failure = "The server exited with code ${ready.exitCode} before it was ready."
                -1
            }
        }
        if (readyAt >= 0) {
            facts["ready in"] = Durations.format(Duration.ofMillis(readyAt))
            steps += StepResult("boot", ok = true, elapsed = Duration.ofMillis(readyAt))
            listener.onStep(steps.last())
        }

        if (failure == null) failure = runSteps(scenario, instance.dir, server, steps, ::consume)
        if (failure == null) failure = watch.firstViolation()

        val stop = server.stop(scenario.stopCommand, scenario.stopTimeout.toMillis(), ::consume)
        if (failure == null && stop == StopOutcome.KILLED) {
            failure = "The server was still running ${Durations.format(scenario.stopTimeout)} after " +
                "'${scenario.stopCommand}' and had to be killed."
        }

        watch.contribute(facts)
        facts["lines"] = server.linesSeen.toString()
        facts["stopped"] = stop.name.lowercase()
        server.exitCode?.let { facts["exit code"] = it.toString() }
        facts["instance"] = instance.dir.toString()

        return CaseResult(
            case = case,
            outcome = if (failure == null) Outcome.PASSED else Outcome.FAILED,
            elapsed = elapsed(),
            steps = steps,
            failure = failure,
            facts = facts,
            tail = if (failure == null) emptyList() else server.transcript.takeLast(TAIL_LINES).map { it.text },
            stop = stop,
        )
    }

    /** @return the first failure, or null when every step was satisfied. */
    private fun runSteps(
        scenario: Scenario,
        instanceDir: Path,
        server: ServerProcess,
        steps: MutableList<StepResult>,
        consume: (LogLine) -> Unit,
    ): String? {
        for (step in scenario.steps) {
            val startedAt = System.nanoTime()
            fun took() = Duration.ofNanos(System.nanoTime() - startedAt)

            val result: StepResult = when (step) {
                is Step.Send -> try {
                    server.send(step.command)
                    StepResult(step.describe, ok = true, elapsed = took())
                } catch (e: Exception) {
                    StepResult(step.describe, ok = false, elapsed = took(), detail = e.message)
                }

                is Step.Wait -> when (val outcome = server.drain(step.duration.toMillis(), consume)) {
                    is WaitResult.Exited -> StepResult(
                        step.describe, ok = false, elapsed = took(),
                        detail = "The server exited with code ${outcome.exitCode} while waiting.",
                    )

                    else -> StepResult(step.describe, ok = true, elapsed = took())
                }

                is Step.Expect -> await(step.describe, server, step.matcher, step.timeout, consume, took = ::took)
                is Step.Enabled -> await(step.describe, server, step.matcher, step.timeout, consume, took = ::took)
                is Step.FileExists -> awaitFileExists(step, instanceDir, server, consume, ::took)
                is Step.FileContains -> awaitFileContains(step, instanceDir, server, consume, ::took)
            }

            steps += result
            listener.onStep(result)
            if (!result.ok) return "${result.describe} failed: ${result.detail}"
        }
        return null
    }

    private fun await(
        describe: String,
        server: ServerProcess,
        matcher: LineMatcher,
        timeout: Duration,
        consume: (LogLine) -> Unit,
        took: () -> Duration,
    ): StepResult = when (val outcome = server.await(matcher, timeout.toMillis(), consume)) {
        is WaitResult.Matched -> StepResult(describe, true, took(), matched = outcome.line.text)
        is WaitResult.TimedOut -> StepResult(
            describe, false, took(),
            detail = "nothing ${matcher.describe} within ${Durations.format(timeout)}",
        )

        is WaitResult.Exited -> StepResult(
            describe, false, took(),
            detail = "the server exited with code ${outcome.exitCode} first",
        )
    }

    private fun awaitFileExists(
        step: Step.FileExists,
        instanceDir: Path,
        server: ServerProcess,
        consume: (LogLine) -> Unit,
        took: () -> Duration,
    ): StepResult {
        val target = InstancePaths.resolveInside(instanceDir, step.relativePath)
        val deadlineNanos = System.nanoTime() + step.timeout.toNanos()
        while (true) {
            if (Files.exists(target)) {
                return StepResult(step.describe, ok = true, elapsed = took(), matched = step.relativePath)
            }
            val remainingMillis = (deadlineNanos - System.nanoTime()) / 1_000_000L
            if (remainingMillis <= 0) {
                return StepResult(
                    step.describe,
                    ok = false,
                    elapsed = took(),
                    detail = "${step.relativePath} was not created within ${Durations.format(step.timeout)}",
                )
            }
            val drained = server.drain(minOf(FILE_POLL_MILLIS, remainingMillis), consume)
            if (drained is WaitResult.Exited) {
                return if (Files.exists(target)) {
                    StepResult(step.describe, ok = true, elapsed = took(), matched = step.relativePath)
                } else {
                    StepResult(
                        step.describe,
                        ok = false,
                        elapsed = took(),
                        detail = "the server exited with code ${drained.exitCode} before ${step.relativePath} appeared",
                    )
                }
            }
        }
    }

    private fun awaitFileContains(
        step: Step.FileContains,
        instanceDir: Path,
        server: ServerProcess,
        consume: (LogLine) -> Unit,
        took: () -> Duration,
    ): StepResult {
        val target = InstancePaths.resolveInside(instanceDir, step.relativePath)
        val deadlineNanos = System.nanoTime() + step.timeout.toNanos()
        var sawFile = false
        while (true) {
            if (Files.isRegularFile(target)) {
                sawFile = true
                val matchedLine = try {
                    Files.readAllLines(target, StandardCharsets.UTF_8).firstOrNull { step.matcher.matches(it) }
                } catch (_: Exception) {
                    null
                }
                if (matchedLine != null) {
                    return StepResult(step.describe, ok = true, elapsed = took(), matched = matchedLine)
                }
            }
            val remainingMillis = (deadlineNanos - System.nanoTime()) / 1_000_000L
            if (remainingMillis <= 0) {
                val why = if (!sawFile) {
                    "${step.relativePath} was never created within ${Durations.format(step.timeout)}"
                } else {
                    "no line in ${step.relativePath} ${step.matcher.describe} within ${Durations.format(step.timeout)}"
                }
                return StepResult(step.describe, ok = false, elapsed = took(), detail = why)
            }
            val drained = server.drain(minOf(FILE_POLL_MILLIS, remainingMillis), consume)
            if (drained is WaitResult.Exited) {
                return StepResult(
                    step.describe,
                    ok = false,
                    elapsed = took(),
                    detail = "the server exited with code ${drained.exitCode} before ${step.relativePath} matched",
                )
            }
        }
    }

    private companion object {
        const val TAIL_LINES = 40
        const val FILE_POLL_MILLIS = 75L
    }
}
