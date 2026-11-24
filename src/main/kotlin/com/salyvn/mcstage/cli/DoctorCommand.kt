package com.salyvn.mcstage.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.path
import com.salyvn.mcstage.core.DoctorCheck
import java.nio.file.Files
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Self-check command verifying Java 21+, JAR cache, loopback port, optional scenario YAML, and
 * optional companion `mckit-bridge-plugin` reachability.
 */
class DoctorCommand : CliktCommand(name = "doctor") {

    override fun help(context: Context) =
        "Check Java 21+ runtime, JAR cache health, loopback port availability, and optional scenario/bridge readiness."

    private val javaBinary by option("--java", help = "Explicit Java binary or JDK home to inspect")
    private val port by option("--port", help = "Preferred loopback server port to probe (default: 25565)").int().default(25565)
    private val scenario by option("--scenario", help = "Optional scenario YAML file to validate").path()
    private val bridgeUrl by option("--bridge-url", help = "Optional companion mckit-bridge-plugin URL (e.g. http://127.0.0.1:25590)")
    private val badgeJson by option("--badge-json", help = "Write a shields.io Endpoint Badge JSON file summarizing readiness").path()
    private val json by option("--json", help = "Emit machine-readable JSON output").flag()

    override fun run() {
        val report = DoctorCheck.run(
            javaOverride = javaBinary,
            port = port,
            scenarioFile = scenario,
            bridgeUrl = bridgeUrl,
        )
        badgeJson?.let { target ->
            target.parent?.let { Files.createDirectories(it) }
            val badge = buildJsonObject {
                put("schemaVersion", 1)
                put("label", "mcstage doctor")
                put("message", if (report.ok) "READY (${report.javaVersion})" else "ISSUES")
                put("color", if (report.ok) "brightgreen" else "red")
            }
            Files.writeString(target, PRETTY_JSON.encodeToString(badge))
        }
        echo(if (json) DoctorCheck.renderJson(report) else DoctorCheck.renderText(report).trimEnd())
        if (!report.ok) {
            throw ProgramResult(1)
        }
    }

    private companion object {
        val PRETTY_JSON = Json { prettyPrint = true }
    }
}
