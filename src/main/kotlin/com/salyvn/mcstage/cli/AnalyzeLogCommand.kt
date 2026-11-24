package com.salyvn.mcstage.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.long
import com.github.ajalt.clikt.parameters.types.path
import com.salyvn.mcstage.core.BuildInfo
import com.salyvn.mcstage.core.Platform
import com.salyvn.mcstage.report.SarifRunReporter
import com.salyvn.mcstage.run.BarrierStallAnalyzer
import com.salyvn.mcstage.run.CaseResult
import com.salyvn.mcstage.run.CrashAnalyzer
import com.salyvn.mcstage.run.HeapUsageAnalyzer
import com.salyvn.mcstage.run.LogLine
import com.salyvn.mcstage.run.Outcome
import com.salyvn.mcstage.run.RunReport
import com.salyvn.mcstage.run.Scenario
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

/**
 * Offline Paper/Folia/Purpur console log auditor (`mcstage analyze-log`).
 * Scans any existing `latest.log` for MCS001-MCS023 crash, Folia thread, watchdog,
 * heap, and barrier-stall issues and optionally exports OASIS SARIF v2.1.0.
 */
class AnalyzeLogCommand : CliktCommand(name = "analyze-log") {

    override fun help(context: Context) =
        "Analyze an offline Paper/Folia/Purpur latest.log for MCS001-MCS023 crashes, Folia violations, and stalls."

    private val logFile by argument("file", help = "Path to server latest.log").path(mustExist = true, canBeDir = false)
    private val maxStallMs by option("--max-stall-ms", help = "Barrier stall threshold in ms (default: 400)").long().default(400L)
    private val sarifOut by option("--sarif", help = "Write OASIS SARIF v2.1.0 report to path").path()
    private val json by option("--json", help = "Emit machine-readable JSON output").flag()
    private val failOnFatal by option("--fail-on-fatal", help = "Exit with code 1 if crash-guard triggers").flag()

    override fun run() {
        val rawLines = Files.readAllLines(logFile, StandardCharsets.UTF_8)
        val lines = rawLines.mapIndexed { idx, line ->
            LogLine(number = idx + 1, elapsedMillis = idx * 50L, text = line)
        }
        val findings = CrashAnalyzer.analyze(lines)
        val fatalReason = CrashAnalyzer.firstCrashGuardFailure(findings)
        val barrierViolation = BarrierStallAnalyzer.detectViolation(lines, Duration.ofMillis(maxStallMs))
        val peakHeapPct = HeapUsageAnalyzer.peakUsagePercent(rawLines)

        val sarifPath = sarifOut
        if (sarifPath != null) {
            val report = RunReport(
                tool = BuildInfo.NAME,
                toolVersion = BuildInfo.version,
                scenario = logFile.fileName.toString(),
                startedAt = Instant.now(),
                cases = listOf(
                    CaseResult(
                        case = Scenario.Case(Platform.PAPER, "offline-log"),
                        outcome = if (fatalReason == null) Outcome.PASSED else Outcome.FAILED,
                        elapsed = Duration.ZERO,
                        diagnostics = findings,
                    ),
                ),
            )
            sarifPath.parent?.let { Files.createDirectories(it) }
            Files.writeString(sarifPath, SarifRunReporter().render(report), StandardCharsets.UTF_8)
        }

        if (json) {
            val payload = buildJsonObject {
                put("file", logFile.toString())
                put("line_count", lines.size)
                put("finding_count", findings.size)
                put("crash_guard_triggered", fatalReason != null)
                put("barrier_ok", barrierViolation == null)
                peakHeapPct?.let { put("peak_heap_percent", it) }
                putJsonArray("findings") {
                    for (f in findings) {
                        add(buildJsonObject {
                            put("ruleId", f.ruleId)
                            put("severity", f.severity)
                            put("message", f.message)
                            f.lineNumber?.let { put("lineNumber", it) }
                        })
                    }
                }
            }
            echo(PRETTY_JSON.encodeToString(payload))
        } else {
            echo("MCStage Offline Log Audit (${logFile.fileName}, ${lines.size} lines)")
            echo("  Findings : ${findings.size} | Crash-Guard: ${if (fatalReason == null) "CLEAN" else "TRIGGERED"}")
            for (f in findings) {
                val loc = f.lineNumber?.let { "L$it " }.orEmpty()
                echo("  [${f.ruleId}/${f.severity}] $loc${f.message}")
            }
        }

        if (failOnFatal && fatalReason != null) {
            throw ProgramResult(1)
        }
    }

    private companion object {
        val PRETTY_JSON = Json { prettyPrint = true }
    }
}
