package com.salyvn.mcstage.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.path
import com.salyvn.mcstage.run.CrashAnalyzer
import java.nio.file.Files
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Lists all MCStage crash-guard & static diagnostic rules (`MCS001`..`MCS023`).
 */
class ListRulesCommand : CliktCommand(name = "list-rules") {

    override fun help(context: Context) =
        "List all MCStage diagnostic and crash-guard rules (MCS001-MCS023)."

    private val json by option("--json", help = "Emit machine-readable JSON output").flag()
    private val badgeJson by option("--badge-json", help = "Write shields.io Endpoint Badge JSON summarizing diagnostic rules").path()

    override fun run() {
        val rules = CrashAnalyzer.RULES
        badgeJson?.let { target ->
            target.parent?.let { Files.createDirectories(it) }
            val badge = """{"schemaVersion":1,"label":"mcstage rules","message":"${rules.size} SARIF rules (MCS001-MCS${String.format("%03d", rules.size)})","color":"brightgreen"}"""
            Files.writeString(target, badge)
        }
        if (json) {
            val payload = buildJsonObject {
                put("rule_count", rules.size)
                putJsonArray("rules") {
                    for (r in rules) {
                        add(buildJsonObject {
                            put("id", r.id)
                            put("name", r.name)
                            put("severity", r.defaultSeverity)
                            put("fatalForCrashGuard", r.fatalForCrashGuard)
                            put("shortDescription", r.shortDescription)
                        })
                    }
                }
            }
            echo(PRETTY_JSON.encodeToString(payload))
        } else {
            echo("MCStage Diagnostic & Crash-Guard Rules (${rules.size} total)")
            for (r in rules) {
                val fatal = if (r.fatalForCrashGuard) "fatal" else "info "
                echo("  ${r.id}  [${r.defaultSeverity.padEnd(7)} | $fatal]  ${r.name} — ${r.shortDescription}")
            }
        }
    }

    private companion object {
        val PRETTY_JSON = Json { prettyPrint = true }
    }
}
