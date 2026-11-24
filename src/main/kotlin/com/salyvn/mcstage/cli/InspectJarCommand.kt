package com.salyvn.mcstage.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.path
import com.salyvn.mcstage.instance.PluginJarInspector
import kotlinx.serialization.json.Json

/**
 * CLI subcommand for pre-boot Plugin JAR Manifest & Bytecode inspection (`MCS023`).
 */
class InspectJarCommand : CliktCommand(name = "inspect-jar") {

    override fun help(context: Context) =
        "Inspect a plugin .jar (paper-plugin.yml / plugin.yml, main class bytecode Java version, Folia readiness) before booting a server."

    private val jarPath by argument("jar", help = "Path to the plugin .jar file").path()
    private val folia by option("--folia", help = "Require folia-supported: true in the plugin descriptor").flag()
    private val hostJava by option("--java-major", help = "Target host JVM feature version (default: current JVM)").int()
        .default(Runtime.version().feature())
    private val json by option("--json", help = "Emit machine-readable JSON output").flag()

    override fun run() {
        val report = PluginJarInspector.inspect(jarPath, targetFolia = folia, hostJavaMajor = hostJava)
        if (json) {
            echo(PRETTY_JSON.encodeToString(PluginJarInspector.toJson(report)))
        } else {
            echo("Plugin JAR Inspection (${report.descriptorType}): ${if (report.ok) "PASS" else "FAIL"}")
            echo("  JAR       : ${report.jarPath}")
            echo("  Plugin    : ${report.pluginName ?: "?"} ${report.pluginVersion ?: ""}".trimEnd())
            echo("  Main      : ${report.mainClass ?: "(missing)"} (present=${report.mainClassPresent}, java=${report.mainClassJavaMajor ?: "?"})")
            echo("  API / Folia: api=${report.apiVersion ?: "(unset)"} | folia-supported=${report.foliaSupported}")
            if (report.dependencies.isNotEmpty()) {
                echo("  Depends   : ${report.dependencies.joinToString(", ")}")
            }
            for (iss in report.issues) {
                echo("  [${iss.severity.uppercase()}] ${iss.code} (${iss.ruleId}): ${iss.message}")
            }
        }
        if (!report.ok) {
            throw ProgramResult(1)
        }
    }

    private companion object {
        val PRETTY_JSON = Json { prettyPrint = true }
    }
}
