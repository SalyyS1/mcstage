package com.salyvn.mcstage.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.default
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.double
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.path
import com.salyvn.mcstage.run.MatrixEstimator
import com.salyvn.mcstage.run.ScenarioFile
import kotlinx.serialization.json.Json
import java.nio.file.Path

/**
 * CLI subcommand estimating CI RAM footprint, JAR cache size, wall-clock duration, and
 * recommended GitHub Actions shards for a scenario YAML (`mcstage budget`).
 */
class BudgetCommand : CliktCommand(name = "budget") {

    override fun help(context: Context) =
        "Estimate peak RAM, JAR cache size, serial vs sharded runtime, and CI shard count for a scenario YAML."

    private val file by argument("file", help = "Path to mcstage.yml").path().default(Path.of("mcstage.yml"))
    private val hostRamGb by option("--host-ram-gb", help = "CI runner host RAM in GB (default: 7.0)").double().default(7.0)
    private val maxShards by option("--max-shards", help = "Maximum parallel CI shards (default: 4)").int().default(4)
    private val html by option("--html", help = "Write self-contained dark-mode HTML CI budget report").path()
    private val json by option("--json", help = "Emit machine-readable JSON output").flag()

    override fun run() {
        val scenario = ScenarioFile.read(file)
        val est = MatrixEstimator.estimate(scenario, hostRamGb = hostRamGb, maxShards = maxShards)
        html?.let { target ->
            target.parent?.let { java.nio.file.Files.createDirectories(it) }
            java.nio.file.Files.writeString(target, MatrixEstimator.renderHtml(est))
        }
        if (json) {
            echo(PRETTY_JSON.encodeToString(MatrixEstimator.toJson(est)))
        } else {
            echo("MCStage CI Matrix Budget (${est.scenarioName})")
            echo("  Matrix Cases        : ${est.caseCount} (${est.stepCount} steps each)")
            echo("  Per-Case Heap       : ${est.perCaseMemoryMb} MB (peak ${est.peakRamMb} MB @ ${est.recommendedParallelism}x parallel)")
            echo("  JAR Cache Footprint : ~${est.estimatedJarCacheMb} MB")
            echo("  Estimated Runtime   : ~${est.estimatedSerialSeconds}s serial -> ~${est.estimatedParallelSeconds}s across ${est.recommendedShards} shard(s)")
            for (w in est.warnings) {
                echo("  [WARN] $w")
            }
        }
    }

    private companion object {
        val PRETTY_JSON = Json { prettyPrint = true }
    }
}
