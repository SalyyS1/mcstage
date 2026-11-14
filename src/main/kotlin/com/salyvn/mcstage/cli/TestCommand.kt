package com.salyvn.mcstage.cli

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.path
import com.salyvn.mcstage.instance.InstanceCreator
import com.salyvn.mcstage.run.CreatingPreparer
import com.salyvn.mcstage.run.ScenarioFile
import com.salyvn.mcstage.run.ScenarioRunner
import java.nio.file.Files
import java.nio.file.Path

/**
 * Runs a scenario across every platform and version it names.
 *
 * This is the command a CI job calls. Everything it needs is in the file, so the job is one line and the
 * versions being tested are reviewable in the repository rather than buried in a pipeline definition.
 */
class TestCommand : ScenarioCommand(name = "test") {

    override fun help(context: Context) = "Run a scenario file against real servers."

    private val file by argument("scenario", help = "A scenario YAML file").path()

    private val work by option(
        "--work",
        help = "Where instances are kept between runs (default: .mcstage next to the scenario)",
    ).path()

    private val plugins by option(
        "--plugin",
        help = "Extra plugin jars, added to whatever the scenario lists",
    ).multiple()

    private val acceptEula by option(
        "--accept-eula",
        help = "Write eula=true in each instance, recording your agreement to the Minecraft EULA",
    ).flag()

    private val clean by option("--clean", help = "Delete the instances before running").flag()

    override fun execute() {
        val scenario = ScenarioFile.read(file).let {
            if (plugins.isEmpty()) it else it.copy(plugins = it.plugins + plugins)
        }

        val workRoot = work ?: (file.toAbsolutePath().parent ?: Path.of(".")).resolve(".mcstage")
        if (clean) remove(workRoot)

        val runner = ScenarioRunner(
            preparer = CreatingPreparer(InstanceCreator(cache, indexes), workRoot, acceptEula),
            listener = listener(),
            javaOverride = javaBinary,
            basePort = basePort,
        )

        emit(runner.run(scenario, scenarioFile = file))
    }

    private fun remove(root: Path) {
        if (!Files.exists(root)) return
        echo("removing $root")
        Files.walk(root).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }
}
