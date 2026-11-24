package com.salyvn.mcstage.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.charset.StandardCharsets
import java.nio.file.Files

/**
 * Converts a saved `mcstage` JSON report into a GitHub Actions `$GITHUB_STEP_SUMMARY`
 * Markdown matrix summary (`mcstage summary`).
 */
class SummaryCommand : CliktCommand(name = "summary") {

    override fun help(context: Context) =
        "Render a saved mcstage JSON report as a GitHub Actions Step Summary Markdown table."

    private val reportFile by argument("file", help = "Path to mcstage JSON report").path(mustExist = true, canBeDir = false)
    private val output by option("-o", "--output", help = "Optional output Markdown path (prints to stdout if omitted)").path()
    private val badgeJson by option("--badge-json", help = "Optional path to write a shields.io Endpoint Badge JSON").path()

    override fun run() {
        val raw = Files.readString(reportFile, StandardCharsets.UTF_8)
        val root = Json.parseToJsonElement(raw).jsonObject
        val scenario = root["scenario"]?.jsonPrimitive?.contentOrNull ?: reportFile.fileName.toString()
        val cases = root["cases"]?.jsonArray.orEmpty()
        var passed = 0
        val rows = buildString {
            for (el in cases) {
                val obj = el.jsonObject
                val platform = obj["platform"]?.jsonPrimitive?.contentOrNull ?: "unknown"
                val version = obj["version"]?.jsonPrimitive?.contentOrNull ?: "?"
                val outcome = obj["outcome"]?.jsonPrimitive?.contentOrNull ?: "UNKNOWN"
                val elapsedMs = obj["elapsedMs"]?.jsonPrimitive?.contentOrNull ?: "0"
                val diagIds = obj["diagnostics"]?.jsonArray.orEmpty()
                    .mapNotNull { it.jsonObject["ruleId"]?.jsonPrimitive?.contentOrNull }
                    .distinct()
                    .joinToString(", ")
                    .ifEmpty { "—" }
                val ok = outcome.equals("PASSED", ignoreCase = true)
                if (ok) passed++
                val badge = if (ok) "PASS" else outcome.uppercase()
                appendLine("| `$platform` | `$version` | **$badge** | ${elapsedMs} ms | $diagIds |")
            }
        }
        val verdict = if (passed == cases.size) "PASS" else "FAIL"
        badgeJson?.let { bp ->
            val color = if (passed == cases.size) "brightgreen" else "red"
            val payload = """{"schemaVersion":1,"label":"mcstage matrix","message":"$verdict $passed/${cases.size}","color":"$color"}"""
            bp.parent?.let { Files.createDirectories(it) }
            Files.writeString(bp, payload, StandardCharsets.UTF_8)
        }
        val md = buildString {
            appendLine("## MCStage Real-Server Matrix Summary — `$verdict` ($passed/${cases.size})")
            appendLine("- **Scenario:** `$scenario`")
            appendLine()
            appendLine("| Platform | Version | Outcome | Elapsed | Diagnostics |")
            appendLine("|---|---|---|---:|---|")
            append(rows)
        }
        val out = output
        if (out != null) {
            out.parent?.let { Files.createDirectories(it) }
            Files.writeString(out, md, StandardCharsets.UTF_8)
            echo("Wrote Markdown summary to $out")
        } else {
            echo(md)
        }
    }
}
