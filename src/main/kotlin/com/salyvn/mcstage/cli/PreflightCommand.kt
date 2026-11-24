package com.salyvn.mcstage.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.path
import com.salyvn.mcstage.core.BuildInfo
import com.salyvn.mcstage.core.Platform
import com.salyvn.mcstage.instance.ConfigPreflight
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
 * CLI subcommand for static preflight auditing of `server.properties` and JVM flags (`MCS022`).
 */
class PreflightCommand : CliktCommand(name = "preflight") {

    override fun help(context: Context) =
        "Audit server.properties and JVM startup flags (MCS022) for security, chunk I/O, and GC hazards before booting."

    private val target by argument("target", help = "Server directory containing server.properties, or direct path to server.properties").path()
    private val jvmArgs by option("--jvm-arg", help = "JVM flag to audit (repeatable, e.g. --jvm-arg -Xms2G --jvm-arg -Xmx2G)").multiple()
    private val sarifOut by option("--sarif", help = "Write OASIS SARIF v2.1.0 MCS022 report to path").path()
    private val json by option("--json", help = "Emit machine-readable JSON output").flag()

    override fun run() {
        val report = if (Files.isDirectory(target)) {
            ConfigPreflight.auditDirectory(target, jvmArgs)
        } else if (Files.isRegularFile(target)) {
            ConfigPreflight.audit(Files.readString(target), jvmArgs)
        } else {
            ConfigPreflight.audit("", jvmArgs)
        }
        sarifOut?.let { sp ->
            val diags = report.issues.map { iss ->
                DiagnosticFinding(
                    ruleId = "MCS022",
                    severity = iss.severity,
                    message = "[${iss.code}] ${iss.key}: ${iss.message}",
                    artifactPath = target.toString(),
                )
            }
            val runRep = RunReport(
                tool = BuildInfo.NAME,
                toolVersion = BuildInfo.version,
                scenario = "preflight",
                startedAt = Instant.now(),
                cases = listOf(
                    CaseResult(
                        case = Scenario.Case(Platform.PAPER, "preflight"),
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
            echo(PRETTY_JSON.encodeToString(ConfigPreflight.toJson(report)))
        } else {
            echo("Server Config Preflight (MCS022): Grade ${report.grade} (${report.score}/100) — ${if (report.ok) "PASS" else "FAIL"}")
            echo("  Properties parsed: ${report.propertiesParsed} | JVM flags analyzed: ${report.jvmFlagsAnalyzed}")
            if (report.issues.isEmpty()) {
                echo("  No hazards detected.")
            } else {
                for (iss in report.issues) {
                    echo("  [${iss.severity.uppercase()}] ${iss.code} (${iss.key}): ${iss.message}")
                    echo("    Fix: ${iss.recommendation}")
                }
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
