package com.salyvn.mcstage.run

import com.salyvn.mcstage.core.Durations
import com.salyvn.mcstage.core.JavaRuntime
import com.salyvn.mcstage.core.PortProbe
import com.salyvn.mcstage.instance.Instance
import com.salyvn.mcstage.protocol.MinecraftSlpProbe
import com.salyvn.mcstage.rcon.LoopbackRconBridgeServer
import com.salyvn.mcstage.rcon.RconFaultInjector
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
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

    fun run(scenario: Scenario, scenarioFile: Path? = null): RunReport {
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
            scenarioFile = scenarioFile?.toString(),
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
            val msg = e.message ?: e::class.simpleName ?: "Scenario setup error"
            return CaseResult(
                case = case,
                outcome = Outcome.ERROR,
                elapsed = elapsed(),
                failure = msg,
                facts = facts,
                diagnostics = listOf(DiagnosticFinding("MCS011", "error", msg)),
            )
        }

        if (!instance.eulaAccepted()) {
            val msg = "The Minecraft EULA has not been accepted for ${instance.dir}. Pass --accept-eula " +
                "to have mcstage write eula.txt, or write it yourself. mcstage never accepts it for you."
            return CaseResult(
                case = case,
                outcome = Outcome.ERROR,
                elapsed = elapsed(),
                failure = msg,
                facts = facts,
                diagnostics = listOf(DiagnosticFinding("MCS011", "error", msg)),
            )
        }

        val existingCrashReports = CrashAnalyzer.snapshotCrashReports(instance.dir)
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
            val msg = e.message ?: "Failed to start server process"
            return CaseResult(
                case = case,
                outcome = Outcome.ERROR,
                elapsed = elapsed(),
                steps = steps,
                failure = msg,
                facts = facts,
                diagnostics = listOf(DiagnosticFinding("MCS011", "error", msg)),
            )
        }

        var failure: String? = null
        var failureRuleId: String? = null
        var failureLine: LogLine? = null
        val readyAt: Long

        val ready = server.await(scenario.ready, scenario.readyTimeout.toMillis(), ::consume)
        readyAt = when (ready) {
            is WaitResult.Matched -> ready.line.elapsedMillis
            is WaitResult.TimedOut -> {
                failure = "The server did not report itself ready within " +
                    "${Durations.format(scenario.readyTimeout)} (waiting for ${scenario.ready.describe})."
                failureRuleId = "MCS007"
                -1
            }

            is WaitResult.Exited -> {
                failure = "The server exited with code ${ready.exitCode} before it was ready."
                failureRuleId = "MCS007"
                -1
            }
        }
        if (readyAt >= 0) {
            facts["ready in"] = Durations.format(Duration.ofMillis(readyAt))
            steps += StepResult("boot", ok = true, elapsed = Duration.ofMillis(readyAt))
            listener.onStep(steps.last())
        }
        val bootLineNumber = server.linesSeen

        if (failure == null) {
            val stepFailure = runSteps(scenario, instance.dir, port, server, steps, bootLineNumber, ::consume)
            if (stepFailure != null) {
                failure = stepFailure.message
                failureRuleId = stepFailure.ruleId
                failureLine = stepFailure.line
            }
        }
        if (failure == null) {
            failure = watch.firstViolation()
            if (failure != null) {
                failureRuleId = "MCS009"
                failureLine = watch.firstViolationLine()
            }
        }

        val stop = server.stop(scenario.stopCommand, scenario.stopTimeout.toMillis(), ::consume)
        if (failure == null && stop == StopOutcome.KILLED) {
            failure = "The server was still running ${Durations.format(scenario.stopTimeout)} after " +
                "'${scenario.stopCommand}' and had to be killed."
            failureRuleId = "MCS010"
        }

        val logDiagnostics = CrashAnalyzer.analyze(server.transcript, instance.dir, existingCrashReports)
        if (failure == null && scenario.crashGuard) {
            val crashGuardFailure = CrashAnalyzer.firstCrashGuardFailure(logDiagnostics)
            if (crashGuardFailure != null) {
                failure = crashGuardFailure
            }
        }

        val diagnostics = buildList {
            addAll(logDiagnostics)
            if (failure != null && failureRuleId != null) {
                val alreadyRecorded = logDiagnostics.any {
                    it.ruleId == failureRuleId && (failureLine == null || it.lineNumber == failureLine.number)
                }
                if (!alreadyRecorded) {
                    add(
                        DiagnosticFinding(
                            ruleId = failureRuleId,
                            severity = "error",
                            message = failure,
                            lineNumber = failureLine?.number,
                            matchedLine = failureLine?.text?.trim(),
                        ),
                    )
                }
            }
        }
        if (diagnostics.isNotEmpty()) {
            facts["diagnostics"] = diagnostics.map { it.ruleId }.distinct().joinToString(", ")
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
            diagnostics = diagnostics,
        )
    }

    private data class StepFailure(
        val message: String,
        val ruleId: String = "MCS008",
        val line: LogLine? = null,
    )

    /** @return the first failure, or null when every step was satisfied. */
    private fun runSteps(
        scenario: Scenario,
        instanceDir: Path,
        serverPort: Int,
        server: ServerProcess,
        steps: MutableList<StepResult>,
        bootLineNumber: Int,
        consume: (LogLine) -> Unit,
    ): StepFailure? {
        var barrierCursor = bootLineNumber
        var recoveryCursor = bootLineNumber
        for (step in scenario.steps) {
            val startedAt = System.nanoTime()
            fun took() = Duration.ofNanos(System.nanoTime() - startedAt)
            var overrideRuleId = "MCS008"
            var failureLine: LogLine? = null

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
                is Step.NotExpect -> awaitNotExpect(step, server, consume, ::took)
                is Step.Enabled -> await(step.describe, server, step.matcher, step.timeout, consume, took = ::took)
                is Step.FileExists -> awaitFileExists(step, instanceDir, server, consume, ::took)
                is Step.FileNotExists -> awaitFileNotExists(step, instanceDir, server, consume, ::took)
                is Step.FileContains -> awaitFileContains(step, instanceDir, server, consume, ::took)
                is Step.InjectLag -> runInjectLag(step, server, consume, ::took)
                is Step.NoBarrierStall -> {
                    val (stepRes, violation) = awaitNoBarrierStall(step, server, barrierCursor, consume, ::took)
                    barrierCursor = server.linesSeen
                    if (violation != null) {
                        overrideRuleId = "MCS012"
                        failureLine = violation.lineNumber?.let { num ->
                            server.transcript.firstOrNull { it.number == num }
                        }
                    }
                    stepRes
                }

                is Step.AssertHeap -> {
                    val stepRes = awaitAssertHeap(step, server, consume, ::took)
                    if (!stepRes.ok) overrideRuleId = "MCS014"
                    stepRes
                }

                is Step.AssertRegionRecovery -> {
                    val (stepRes, violation) = awaitAssertRegionRecovery(step, server, recoveryCursor, consume, ::took)
                    recoveryCursor = server.linesSeen
                    if (violation != null) {
                        overrideRuleId = "MCS015"
                        failureLine = violation.lineNumber?.let { num ->
                            server.transcript.firstOrNull { it.number == num }
                        }
                    }
                    stepRes
                }

                is Step.RconFault -> {
                    val (stepRes, rconRule) = runRconFault(step, server, consume, ::took)
                    if (rconRule != null) overrideRuleId = rconRule
                    stepRes
                }

                is Step.BotProbe -> {
                    val stepRes = runBotProbe(step, serverPort, server, consume, ::took)
                    if (!stepRes.ok) overrideRuleId = "MCS018"
                    stepRes
                }

                is Step.AssertBridge -> {
                    val stepRes = runAssertBridge(step, server, consume, ::took)
                    if (!stepRes.ok) overrideRuleId = "MCS019"
                    stepRes
                }
            }

            steps += result
            listener.onStep(result)
            if (!result.ok) {
                return StepFailure(
                    message = "${result.describe} failed: ${result.detail}",
                    ruleId = overrideRuleId,
                    line = failureLine,
                )
            }
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

    private fun awaitNotExpect(
        step: Step.NotExpect,
        server: ServerProcess,
        consume: (LogLine) -> Unit,
        took: () -> Duration,
    ): StepResult = when (val outcome = server.await(step.matcher, step.duration.toMillis(), consume)) {
        is WaitResult.Matched -> StepResult(
            step.describe,
            ok = false,
            elapsed = took(),
            detail = "unexpected line ${outcome.line.number} ${step.matcher.describe}: ${outcome.line.text.trim()}",
            matched = outcome.line.text,
        )

        is WaitResult.TimedOut -> StepResult(step.describe, ok = true, elapsed = took())

        is WaitResult.Exited -> StepResult(
            step.describe,
            ok = false,
            elapsed = took(),
            detail = "the server exited with code ${outcome.exitCode} during ${Durations.format(step.duration)}",
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

    private fun awaitFileNotExists(
        step: Step.FileNotExists,
        instanceDir: Path,
        server: ServerProcess,
        consume: (LogLine) -> Unit,
        took: () -> Duration,
    ): StepResult {
        val target = InstancePaths.resolveInside(instanceDir, step.relativePath)
        if (Files.exists(target)) {
            return StepResult(
                step.describe,
                ok = false,
                elapsed = took(),
                detail = "${step.relativePath} exists inside the server instance",
            )
        }
        if (step.duration.isZero) {
            return StepResult(step.describe, ok = true, elapsed = took())
        }
        val deadlineNanos = System.nanoTime() + step.duration.toNanos()
        while (true) {
            val remainingMillis = (deadlineNanos - System.nanoTime()) / 1_000_000L
            if (remainingMillis <= 0) {
                return if (Files.exists(target)) {
                    StepResult(
                        step.describe,
                        ok = false,
                        elapsed = took(),
                        detail = "${step.relativePath} appeared within ${Durations.format(step.duration)}",
                    )
                } else {
                    StepResult(step.describe, ok = true, elapsed = took())
                }
            }
            val drained = server.drain(minOf(FILE_POLL_MILLIS, remainingMillis), consume)
            if (Files.exists(target)) {
                return StepResult(
                    step.describe,
                    ok = false,
                    elapsed = took(),
                    detail = "${step.relativePath} appeared within ${Durations.format(step.duration)}",
                )
            }
            if (drained is WaitResult.Exited) {
                return StepResult(
                    step.describe,
                    ok = false,
                    elapsed = took(),
                    detail = "the server exited with code ${drained.exitCode} while watching ${step.relativePath}",
                )
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

    private fun runInjectLag(
        step: Step.InjectLag,
        server: ServerProcess,
        consume: (LogLine) -> Unit,
        took: () -> Duration,
    ): StepResult {
        val nodes = FoliaRegionTopology.planNodes(
            regions = step.regions,
            spacingBlocks = step.spacingBlocks,
            originX = step.originX,
            originY = step.originY,
            originZ = step.originZ,
        )
        val bridges = if (step.mode == FoliaRegionTopology.Mode.MERGE_SPLIT) {
            FoliaRegionTopology.planBridgeNodes(nodes)
        } else {
            emptyList()
        }
        val useDefaultTickets = step.commands.isEmpty()
        var dispatched = 0
        var pulses = 0

        try {
            if (useDefaultTickets) {
                for (cmd in FoliaRegionTopology.defaultAnchorSetupCommands(nodes, step.world)) {
                    server.send(cmd)
                    dispatched++
                }
            }

            val deadlineNanos = System.nanoTime() + step.duration.toNanos()
            while (true) {
                val pulseCommands = if (useDefaultTickets) {
                    FoliaRegionTopology.defaultPulseCommands(step.mode, nodes, bridges, step.world, pulses)
                } else {
                    val activeNodes = if (step.mode == FoliaRegionTopology.Mode.MERGE_SPLIT && pulses % 2 == 0) {
                        nodes + bridges
                    } else {
                        nodes
                    }
                    activeNodes.flatMap { node ->
                        step.commands.map { tpl -> FoliaRegionTopology.renderCommand(tpl, node, step.world, pulses) }
                    }
                }
                for (cmd in pulseCommands) {
                    server.send(cmd)
                    dispatched++
                }
                pulses++

                val remainingMillis = (deadlineNanos - System.nanoTime()) / 1_000_000L
                if (remainingMillis <= 0) break

                val waitSlice = minOf(step.cadence.toMillis(), remainingMillis)
                if (waitSlice > 0) {
                    val drained = server.drain(waitSlice, consume)
                    if (drained is WaitResult.Exited) {
                        return StepResult(
                            step.describe,
                            ok = false,
                            elapsed = took(),
                            detail = "the server exited with code ${drained.exitCode} during synthetic lag injection",
                        )
                    }
                }
            }

            if (useDefaultTickets && step.cleanup) {
                for (cmd in FoliaRegionTopology.defaultAnchorCleanupCommands(nodes, bridges, step.world)) {
                    server.send(cmd)
                    dispatched++
                }
                server.drain(minOf(FILE_POLL_MILLIS, step.cadence.toMillis()), consume)
            }
        } catch (e: Exception) {
            return StepResult(step.describe, ok = false, elapsed = took(), detail = e.message)
        }

        return StepResult(
            step.describe,
            ok = true,
            elapsed = took(),
            matched = "$pulses pulse(s), $dispatched command(s) across ${nodes.size} region anchor(s)",
        )
    }

    private fun awaitNoBarrierStall(
        step: Step.NoBarrierStall,
        server: ServerProcess,
        barrierCursor: Int,
        consume: (LogLine) -> Unit,
        took: () -> Duration,
    ): Pair<StepResult, BarrierStallAnalyzer.Violation?> {
        val unseenTranscript = server.transcript.filter { it.number > barrierCursor }
        BarrierStallAnalyzer.detectViolation(unseenTranscript, step.maxStall)?.let { v ->
            return StepResult(step.describe, ok = false, elapsed = took(), detail = v.message, matched = v.matchedLine) to v
        }

        val windowDeadlineNanos = System.nanoTime() + step.window.toNanos()
        var probeNote: String? = null

        if (step.probeCommand != null) {
            val matcher = step.probeExpect ?: LineMatcher.parse(
                "re:There are \\d+.*players online|issued server command: /" + Regex.escape(step.probeCommand),
            )
            val probeStartedNanos = System.nanoTime()
            try {
                server.send(step.probeCommand)
            } catch (e: Exception) {
                val msg = e.message ?: "failed to send barrier probe '${step.probeCommand}'"
                val v = BarrierStallAnalyzer.Violation(BarrierStallAnalyzer.Kind.PROBE_FAILED, msg)
                return StepResult(step.describe, ok = false, elapsed = took(), detail = msg) to v
            }

            val probeLines = mutableListOf<LogLine>()
            val probeTimeoutMillis = maxOf(step.maxStall.toMillis() * 4L, step.window.toMillis(), 1000L)
            val probeOutcome = server.await(matcher, probeTimeoutMillis) { line ->
                consume(line)
                probeLines += line
            }
            BarrierStallAnalyzer.detectViolation(probeLines, step.maxStall)?.let { v ->
                return StepResult(step.describe, ok = false, elapsed = took(), detail = v.message, matched = v.matchedLine) to v
            }

            when (probeOutcome) {
                is WaitResult.Exited -> {
                    val msg = "the server exited with code ${probeOutcome.exitCode} during barrier probe"
                    val v = BarrierStallAnalyzer.Violation(BarrierStallAnalyzer.Kind.PROBE_FAILED, msg)
                    return StepResult(step.describe, ok = false, elapsed = took(), detail = msg) to v
                }

                is WaitResult.TimedOut -> {
                    val msg = "barrier probe '${step.probeCommand}' timed out after ${probeTimeoutMillis}ms " +
                        "(max-stall ${Durations.format(step.maxStall)})"
                    val v = BarrierStallAnalyzer.Violation(BarrierStallAnalyzer.Kind.PROBE_FAILED, msg)
                    return StepResult(step.describe, ok = false, elapsed = took(), detail = msg) to v
                }

                is WaitResult.Matched -> {
                    val rttMillis = (System.nanoTime() - probeStartedNanos) / 1_000_000.0
                    if (rttMillis > step.maxStall.toMillis().toDouble()) {
                        val formatted = String.format(java.util.Locale.ROOT, "%.1f", rttMillis)
                        val msg = "barrier probe '${step.probeCommand}' round-trip took ${formatted}ms, " +
                            "exceeding max-stall ${Durations.format(step.maxStall)}"
                        val v = BarrierStallAnalyzer.Violation(
                            kind = BarrierStallAnalyzer.Kind.PROBE_LATENCY_EXCEEDED,
                            message = msg,
                            lineNumber = probeOutcome.line.number,
                            matchedLine = probeOutcome.line.text.trim(),
                            measuredMillis = rttMillis,
                        )
                        return StepResult(
                            step.describe,
                            ok = false,
                            elapsed = took(),
                            detail = msg,
                            matched = probeOutcome.line.text,
                        ) to v
                    }
                    probeNote = "probe RTT ${String.format(java.util.Locale.ROOT, "%.1f", rttMillis)}ms"
                }
            }
        }

        while (true) {
            val remainingMillis = (windowDeadlineNanos - System.nanoTime()) / 1_000_000L
            if (remainingMillis <= 0) break
            val sliceLines = mutableListOf<LogLine>()
            val drained = server.drain(minOf(FILE_POLL_MILLIS, remainingMillis)) { line ->
                consume(line)
                sliceLines += line
            }
            BarrierStallAnalyzer.detectViolation(sliceLines, step.maxStall)?.let { v ->
                return StepResult(step.describe, ok = false, elapsed = took(), detail = v.message, matched = v.matchedLine) to v
            }
            if (drained is WaitResult.Exited) {
                val msg = "the server exited with code ${drained.exitCode} while watching for barrier stalls"
                val v = BarrierStallAnalyzer.Violation(BarrierStallAnalyzer.Kind.PROBE_FAILED, msg)
                return StepResult(step.describe, ok = false, elapsed = took(), detail = msg) to v
            }
        }

        return StepResult(step.describe, ok = true, elapsed = took(), matched = probeNote) to null
    }

    /**
     * Drains server output for [step.window] milliseconds, collecting every GC-related line, then
     * asserts that the peak heap usage percent never exceeded [step.maxPercent].
     *
     * When no GC lines appear during the window (e.g. because GC logging is not enabled), the step
     * passes with a note so the author knows to add `-verbose:gc` to `jvm-args`.
     */
    private fun awaitAssertHeap(
        step: Step.AssertHeap,
        server: ServerProcess,
        consume: (LogLine) -> Unit,
        took: () -> Duration,
    ): StepResult {
        val gcLines = mutableListOf<String>()
        val deadlineNanos = System.nanoTime() + step.window.toNanos()

        while (true) {
            val remainingMillis = (deadlineNanos - System.nanoTime()) / 1_000_000L
            if (remainingMillis <= 0) break
            val outcome = server.drain(minOf(FILE_POLL_MILLIS, remainingMillis)) { line ->
                consume(line)
                if (HeapUsageAnalyzer.isGcLine(line.text)) {
                    gcLines += line.text
                }
            }
            if (outcome is WaitResult.Exited) {
                return StepResult(
                    step.describe,
                    ok = false,
                    elapsed = took(),
                    detail = "server exited with code ${outcome.exitCode} during heap assertion",
                )
            }
        }

        if (gcLines.isEmpty()) {
            return StepResult(
                step.describe,
                ok = true,
                elapsed = took(),
                matched = "no GC log lines observed (enable -verbose:gc in jvm-args to get heap data)",
            )
        }

        val peakPct = HeapUsageAnalyzer.peakUsagePercent(gcLines)
        return if (peakPct == null || peakPct <= step.maxPercent) {
            StepResult(
                step.describe,
                ok = true,
                elapsed = took(),
                matched = peakPct?.let { "peak heap %.1f%%".format(java.util.Locale.ROOT, it) },
            )
        } else {
            val formatted = "%.1f".format(java.util.Locale.ROOT, peakPct)
            StepResult(
                step.describe,
                ok = false,
                elapsed = took(),
                detail = "heap peaked at $formatted% (threshold ${step.maxPercent}%)",
                matched = gcLines.lastOrNull(),
            )
        }
    }

    private fun awaitAssertRegionRecovery(
        step: Step.AssertRegionRecovery,
        server: ServerProcess,
        recoveryCursor: Int,
        consume: (LogLine) -> Unit,
        took: () -> Duration,
    ): Pair<StepResult, FoliaCrashRecoveryAnalyzer.Violation?> {
        if (step.faultCommand != null) {
            try {
                server.send(step.faultCommand)
            } catch (e: Exception) {
                val msg = e.message ?: "failed to send fault-command '${step.faultCommand}'"
                val v = FoliaCrashRecoveryAnalyzer.Violation(
                    kind = FoliaCrashRecoveryAnalyzer.Kind.PROBE_FAILED,
                    regionKey = "region:default",
                    message = msg,
                )
                return StepResult(step.describe, ok = false, elapsed = took(), detail = msg) to v
            }
        }

        val deadlineNanos = System.nanoTime() + step.window.toNanos()
        var probeDispatched = false
        while (true) {
            val remainingMillis = (deadlineNanos - System.nanoTime()) / 1_000_000L
            if (remainingMillis <= 0L) break
            val slice = minOf(FILE_POLL_MILLIS, remainingMillis)
            val drained = server.drain(slice, consume)
            if (drained is WaitResult.Exited) {
                val msg = "the server exited with code ${drained.exitCode} during Folia region recovery check"
                val v = FoliaCrashRecoveryAnalyzer.Violation(
                    kind = FoliaCrashRecoveryAnalyzer.Kind.PROBE_FAILED,
                    regionKey = "region:default",
                    message = msg,
                )
                return StepResult(step.describe, ok = false, elapsed = took(), detail = msg) to v
            }
            if (!probeDispatched && step.probeCommand != null) {
                probeDispatched = true
                try {
                    server.send(step.probeCommand)
                } catch (e: Exception) {
                    val msg = e.message ?: "failed to send probe-command '${step.probeCommand}'"
                    val v = FoliaCrashRecoveryAnalyzer.Violation(
                        kind = FoliaCrashRecoveryAnalyzer.Kind.PROBE_FAILED,
                        regionKey = "region:default",
                        message = msg,
                    )
                    return StepResult(step.describe, ok = false, elapsed = took(), detail = msg) to v
                }
            }
        }

        val windowLines = server.transcript.filter { it.number > recoveryCursor }
        val assessment = FoliaCrashRecoveryAnalyzer.evaluate(
            transcript = windowLines,
            maxFaultsPerRegion = step.maxFaults,
            loopWindow = step.loopWindow,
            maxRecoveryTime = step.maxRecovery,
            requireRecoveryOnFault = step.requireRecovery,
            customRecoveryMatcher = step.recoveryExpect,
        )
        assessment.violation?.let { v ->
            return StepResult(
                step.describe,
                ok = false,
                elapsed = took(),
                detail = v.message,
                matched = v.matchedLine,
            ) to v
        }

        val note = if (assessment.totalFaults == 0) {
            "0 regional faults observed"
        } else {
            "${assessment.totalFaults} fault(s) contained, recovered ${assessment.recoveredRegions.joinToString(", ")}"
        }
        return StepResult(step.describe, ok = true, elapsed = took(), matched = note) to null
    }

    private fun runRconFault(
        step: Step.RconFault,
        server: ServerProcess,
        consume: (LogLine) -> Unit,
        took: () -> Duration,
    ): Pair<StepResult, String?> {
        val profile = step.profile
        if (profile.port != null) {
            val report = RconFaultInjector.execute(profile.port, profile)
            server.drain(minOf(FILE_POLL_MILLIS, 50L), consume)
            return if (report.ok) {
                StepResult(step.describe, ok = true, elapsed = took(), matched = report.summaryNote()) to null
            } else {
                StepResult(
                    step.describe,
                    ok = false,
                    elapsed = took(),
                    detail = report.failureDetail ?: "RCON fault injection failed",
                    matched = report.summaryNote(),
                ) to (report.failureRuleId ?: "MCS017")
            }
        }

        val dispatchLock = Any()
        LoopbackRconBridgeServer(profile.password) { cmd ->
            synchronized(dispatchLock) {
                val captured = mutableListOf<String>()
                server.send(cmd)
                server.drain(35L) { line ->
                    consume(line)
                    captured += line.text
                }
                captured.joinToString("\n").ifEmpty { "ok: $cmd" }
            }
        }.use { bridge ->
            bridge.start()
            val report = RconFaultInjector.execute(bridge.port, profile)
            return if (report.ok) {
                StepResult(step.describe, ok = true, elapsed = took(), matched = report.summaryNote()) to null
            } else {
                StepResult(
                    step.describe,
                    ok = false,
                    elapsed = took(),
                    detail = report.failureDetail ?: "RCON fault injection failed",
                    matched = report.summaryNote(),
                ) to (report.failureRuleId ?: "MCS017")
            }
        }
    }

    private fun runBotProbe(
        step: Step.BotProbe,
        defaultPort: Int,
        server: ServerProcess,
        consume: (LogLine) -> Unit,
        took: () -> Duration,
    ): StepResult {
        val targetPort = step.port ?: defaultPort
        val res = MinecraftSlpProbe.probe(
            host = "127.0.0.1",
            port = targetPort,
            protocolVersion = step.protocolVersion,
            timeout = step.timeout,
        )
        server.drain(minOf(FILE_POLL_MILLIS, 40L), consume)
        if (!res.reachable) {
            return StepResult(
                step.describe,
                ok = false,
                elapsed = took(),
                detail = "SLP handshake to 127.0.0.1:$targetPort failed: ${res.error}",
            )
        }
        if (res.latencyMillis > step.maxLatency.toMillis()) {
            return StepResult(
                step.describe,
                ok = false,
                elapsed = took(),
                detail = "SLP latency ${res.latencyMillis}ms exceeded max-latency ${step.maxLatency.toMillis()}ms",
            )
        }
        if (step.expectVersion != null && !step.expectVersion.matches(res.versionName.orEmpty())) {
            return StepResult(
                step.describe,
                ok = false,
                elapsed = took(),
                detail = "SLP version '${res.versionName}' did not match ${step.expectVersion.describe}",
            )
        }
        if (step.expectMotd != null && !step.expectMotd.matches(res.motd.orEmpty())) {
            return StepResult(
                step.describe,
                ok = false,
                elapsed = took(),
                detail = "SLP MOTD '${res.motd}' did not match ${step.expectMotd.describe}",
            )
        }
        val note = "version=${res.versionName ?: "?"} protocol=${res.protocolVersion ?: -1} latency=${res.latencyMillis}ms"
        return StepResult(step.describe, ok = true, elapsed = took(), matched = note)
    }

    private fun runAssertBridge(
        step: Step.AssertBridge,
        server: ServerProcess,
        consume: (LogLine) -> Unit,
        took: () -> Duration,
    ): StepResult {
        val client = HttpClient.newBuilder().connectTimeout(step.timeout).build()
        val parser = Json { ignoreUnknownKeys = true }
        fun getJson(path: String): kotlinx.serialization.json.JsonObject {
            val reqBuilder = HttpRequest.newBuilder()
                .uri(URI.create("${step.baseUrl}$path"))
                .timeout(step.timeout)
                .GET()
            if (step.token.isNotBlank()) {
                reqBuilder.header("X-MCKit-Token", step.token)
            }
            val resp = client.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString())
            if (resp.statusCode() != 200) {
                throw IllegalStateException("Bridge HTTP ${resp.statusCode()} on $path")
            }
            return parser.parseToJsonElement(resp.body()).jsonObject
        }

        return try {
            val metrics = getJson("/api/v1/metrics")
            val errorsObj = getJson("/api/v1/errors")
            server.drain(minOf(FILE_POLL_MILLIS, 40L), consume)
            val tps = metrics["tps_1m"]?.jsonPrimitive?.doubleOrNull ?: 20.0
            val mspt = metrics["mspt"]?.jsonPrimitive?.doubleOrNull ?: 0.0
            val errCount = errorsObj["errors"]?.jsonArray?.size ?: 0
            val failures = mutableListOf<String>()
            if (tps < step.minTps) failures += "tps_1m=$tps < ${step.minTps}"
            if (mspt > step.maxMspt) failures += "mspt=${mspt}ms > ${step.maxMspt}ms"
            if (errCount > step.maxErrors) failures += "severe_errors=$errCount > ${step.maxErrors}"
            var guiSuffix = ""
            if (!step.player.isNullOrBlank() && step.expectGuiTitle != null) {
                val encodedPlayer = java.net.URLEncoder.encode(step.player, java.nio.charset.StandardCharsets.UTF_8)
                val guiObj = getJson("/api/v1/gui?player=$encodedPlayer")
                val actualTitle = guiObj["title"]?.jsonPrimitive?.content.orEmpty()
                if (!step.expectGuiTitle.matches(actualTitle)) {
                    failures += "gui[${step.player}] title '$actualTitle' did not match ${step.expectGuiTitle.describe}"
                }
                guiSuffix = " gui_title=$actualTitle"
            }
            val summary = "tps=$tps mspt=${mspt}ms errors=$errCount$guiSuffix"
            if (failures.isNotEmpty()) {
                StepResult(step.describe, ok = false, elapsed = took(), detail = failures.joinToString("; "), matched = summary)
            } else {
                StepResult(step.describe, ok = true, elapsed = took(), matched = summary)
            }
        } catch (e: Exception) {
            StepResult(step.describe, ok = false, elapsed = took(), detail = "Bridge query failed: ${e.message}")
        }
    }

    private companion object {
        const val TAIL_LINES = 40
        const val FILE_POLL_MILLIS = 75L
    }
}
