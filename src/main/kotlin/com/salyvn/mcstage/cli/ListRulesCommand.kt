package com.salyvn.mcstage.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.salyvn.mcstage.run.CrashAnalyzer
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

    override fun run() {
        val rules = CrashAnalyzer.RULES
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
