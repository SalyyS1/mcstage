package com.salyvn.mcstage.cli

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.path
import com.salyvn.mcstage.core.Durations
import com.salyvn.mcstage.core.JavaRuntime
import com.salyvn.mcstage.core.Platform
import com.salyvn.mcstage.instance.Instance
import com.salyvn.mcstage.run.ExistingPreparer
import com.salyvn.mcstage.run.LineMatcher
import com.salyvn.mcstage.run.Scenario
import com.salyvn.mcstage.run.ScenarioFile
import com.salyvn.mcstage.run.ScenarioRunner
import com.salyvn.mcstage.run.ServerCommandLine
import com.salyvn.mcstage.run.ServerProcess
import com.salyvn.mcstage.run.Step
import com.salyvn.mcstage.run.StopOutcome
import java.time.Duration

/**
 * Boots an instance that already exists.
 *
 * Two ways to use it, and they are the two halves of a working day. With `--expect` it is a check: boot,
 * assert, stop, exit non-zero if something was missing. With `--attach` it is a console: boot, install the
 * jar that was just built, and hand the keyboard over, which is the loop of changing a plugin and seeing
 * what it does without keeping a server directory and a start script by hand.
 */
class RunCommand : ScenarioCommand(name = "run") {

    override fun help(context: Context) = "Boot an existing instance, optionally asserting on its output."

    private val dir by argument("dir", help = "An instance directory created by mcstage").path()

    private val plugins by option("--plugin", help = "Plugin jars to install before booting").multiple()

    private val commands by option(
        "--command",
        help = "A console command to send once the server is ready; repeatable, sent in order",
    ).multiple()

    private val expects by option(
        "--expect",
        help = "A line that must appear; repeatable, awaited in order after the commands are sent",
    ).multiple()

    private val forbid by option("--forbid", help = "A line that must not appear").multiple()

    private val scenarioFile by option(
        "--scenario",
        help = "Take the steps from a scenario file instead, ignoring its platforms and versions",
    ).path()

    private val attach by option(
        "--attach",
        help = "Forward this terminal's input to the server console and stay until it exits",
    ).flag()

    private val memory by option("-m", "--memory", help = "Heap size, overriding the instance's").default("")

    override fun execute() {
        val instance = Instance(dir)
        val descriptor = instance.descriptor()
        val heap = memory.ifBlank { descriptor.memory }

        if (attach) {
            attachTo(instance, heap)
            return
        }

        val base = scenarioFile?.let { ScenarioFile.read(it) }
        val steps = base?.steps ?: buildList {
            for (command in commands) add(Step.Send(command))
            for (expect in expects) {
                add(Step.Expect(matcher = LineMatcher.parse(expect), timeout = Duration.ofSeconds(60)))
            }
        }.ifEmpty {
            // Booting and shutting down cleanly is a real check on its own, and it is what somebody who
            // typed no expectations meant.
            listOf(Step.Wait(Duration.ofSeconds(1)))
        }

        val scenario = Scenario(
            name = base?.name ?: "run ${dir.fileName}",
            platforms = listOf(Platform.of(descriptor.platform)),
            versions = listOf(descriptor.version),
            steps = steps,
            plugins = plugins,
            memory = heap,
            jvmArgs = base?.jvmArgs ?: emptyList(),
            ready = base?.ready ?: LineMatcher.parse(Platform.DEFAULT_READY),
            readyTimeout = base?.readyTimeout ?: Duration.ofSeconds(180),
            stopCommand = base?.stopCommand ?: Platform.DEFAULT_STOP,
            stopTimeout = base?.stopTimeout ?: Duration.ofSeconds(60),
            forbid = (base?.forbid ?: emptyList()) + forbid.map { LineMatcher.parse(it) },
        )

        val runner = ScenarioRunner(
            preparer = ExistingPreparer(instance),
            listener = listener(),
            javaOverride = javaBinary,
            basePort = descriptor.port,
        )
        emit(runner.run(scenario))
    }

    /**
     * A console session. There is no verdict here: the exit code is the server's own, because the person
     * watching is the assertion.
     */
    private fun attachTo(instance: Instance, heap: String) {
        val descriptor = instance.descriptor()
        if (!instance.eulaAccepted()) {
            echo(
                "The Minecraft EULA has not been accepted for $dir, so the server will refuse to start. " +
                    "Run create --accept-eula, or write eula=true yourself.",
                err = true,
            )
            throw ProgramResult(2)
        }

        val installed = instance.installPlugins(plugins)
        if (installed.isNotEmpty()) echo("plugins: ${installed.joinToString(", ")}")

        val runtime = JavaRuntime.detect(javaBinary)
        echo("java ${runtime.describe}")
        echo(
            "${descriptor.platform} ${descriptor.version} build ${descriptor.build} on " +
                "${descriptor.bind}:${descriptor.port}",
        )
        echo("Type console commands here. ${Platform.DEFAULT_STOP} shuts it down; so does end of input.")
        echo("")

        val server = ServerProcess(ServerCommandLine.of(instance, heap, emptyList(), runtime), instance.dir)
        server.start()

        val forwarder = Thread({
            try {
                System.`in`.bufferedReader().forEachLine { server.send(it) }
            } catch (_: Exception) {
                // The server stopped reading, which the drain below is about to notice.
            }
        }, "mcstage-console")
        forwarder.isDaemon = true
        forwarder.start()

        server.drain(ATTACH_LIMIT_MILLIS) { echo(it.text) }
        val stop = server.stop(Platform.DEFAULT_STOP, STOP_TIMEOUT_MILLIS) { echo(it.text) }

        val ran = Duration.ofMillis(server.transcript.lastOrNull()?.elapsedMillis ?: 0)
        val code = server.exitCode ?: 0
        echo("")
        echo(
            "server exited with code $code after ${Durations.format(ran)}" +
                if (stop == StopOutcome.KILLED) ", after being killed" else "",
        )
        if (code != 0) throw ProgramResult(1)
    }

    private companion object {
        /** Long enough to be a working session, short enough that a forgotten terminal is not forever. */
        const val ATTACH_LIMIT_MILLIS = 8L * 60 * 60 * 1000
        const val STOP_TIMEOUT_MILLIS = 60_000L
    }
}
