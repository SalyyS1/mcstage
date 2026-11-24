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
import com.salyvn.mcstage.core.BuildInfo
import com.salyvn.mcstage.core.Platform
import com.salyvn.mcstage.instance.PluginJarInspector
import com.salyvn.mcstage.report.SarifRunReporter
import com.salyvn.mcstage.run.CaseResult
import com.salyvn.mcstage.run.DiagnosticFinding
import com.salyvn.mcstage.run.Outcome
import com.salyvn.mcstage.run.RunReport
import com.salyvn.mcstage.run.Scenario
import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.time.Duration
import java.time.Instant

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
    private val sarifOut by option("--sarif", help = "Write OASIS SARIF v2.1.0 MCS023 report to path").path()
    private val htmlOut by option("--html", help = "Write a self-contained dark-mode HTML plugin JAR audit report").path()
    private val json by option("--json", help = "Emit machine-readable JSON output").flag()

    override fun run() {
        val report = PluginJarInspector.inspect(jarPath, targetFolia = folia, hostJavaMajor = hostJava)
        htmlOut?.let { hp ->
            hp.parent?.let { Files.createDirectories(it) }
            Files.writeString(hp, PluginJarInspector.renderHtml(report), StandardCharsets.UTF_8)
        }
        sarifOut?.let { sp ->
            val diags = report.issues.map { iss ->
                DiagnosticFinding(
                    ruleId = iss.ruleId,
                    severity = iss.severity,
                    message = "[${iss.code}] ${iss.message}",
                    artifactPath = report.jarPath,
                )
            }
            val runRep = RunReport(
                tool = BuildInfo.NAME,
                toolVersion = BuildInfo.version,
                scenario = jarPath.fileName.toString(),
                startedAt = Instant.now(),
                cases = listOf(
                    CaseResult(
                        case = Scenario.Case(Platform.PAPER, "pre-boot-jar"),
                        outcome = if (report.ok) Outcome.PASSED else Outcome.FAILED,
                        elapsed = Duration.ZERO,
                        diagnostics = diags,
                    ),
                ),
            )
            sp.parent?.let { Files.createDirectories(it) }
            Files.writeString(sp, SarifRunReporter().render(runRep), StandardCharsets.UTF_8)
        }
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
