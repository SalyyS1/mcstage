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
    private val json by option("--json", help = "Emit machine-readable JSON output").flag()

    override fun run() {
        val report = DoctorCheck.run(
            javaOverride = javaBinary,
            port = port,
            scenarioFile = scenario,
            bridgeUrl = bridgeUrl,
        )
        echo(if (json) DoctorCheck.renderJson(report) else DoctorCheck.renderText(report).trimEnd())
        if (!report.ok) {
            throw ProgramResult(1)
        }
    }
}
