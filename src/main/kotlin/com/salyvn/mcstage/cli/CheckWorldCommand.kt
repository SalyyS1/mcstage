package com.salyvn.mcstage.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.path
import com.salyvn.mcstage.run.WorldIntegrityChecker
import java.nio.file.Files
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * CLI subcommand for offline Minecraft Anvil world integrity verification (`MCS021`).
 */
class CheckWorldCommand : CliktCommand(name = "check-world") {

    override fun help(context: Context) =
        "Audit a Minecraft world directory (level.dat GZIP header, stale session.lock, Anvil .mca 4096-byte sector alignment) without booting a JVM."

    private val worldDir by argument("world", help = "Path to Minecraft world directory (e.g. ./world)").path()
    private val requireUnlocked by option("--require-unlocked", help = "Fail if stale session.lock is still present").flag()
    private val sarif by option("--sarif", help = "Write OASIS SARIF v2.1.0 Code Scanning report (MCS021)").path()
    private val html by option("--html", help = "Write self-contained dark-mode HTML world integrity report (MCS021)").path()
    private val json by option("--json", help = "Emit machine-readable JSON output").flag()

    override fun run() {
        val report = WorldIntegrityChecker.inspect(worldDir, requireSessionLockCleared = requireUnlocked)
        html?.let { target ->
            target.parent?.let { Files.createDirectories(it) }
            Files.writeString(target, WorldIntegrityChecker.renderHtml(report))
        }
        sarif?.let { target ->
            target.parent?.let { Files.createDirectories(it) }
            val sarifDoc = buildJsonObject {
                put("\$schema", "https://json.schemastore.org/sarif-2.1.0.json")
                put("version", "2.1.0")
                putJsonArray("runs") {
                    add(buildJsonObject {
                        putJsonObject("tool") {
                            putJsonObject("driver") {
                                put("name", "mcstage")
                                put("version", "0.35.0")
                                putJsonArray("rules") {
                                    add(buildJsonObject {
                                        put("id", "MCS021")
                                        put("name", "WorldAnvilIntegrityViolation")
                                        putJsonObject("shortDescription") {
                                            put("text", "Minecraft world level.dat, session.lock, or Anvil .mca corruption")
                                        }
                                    })
                                }
                            }
                        }
                        putJsonArray("results") {
                            for (iss in report.issues) {
                                add(buildJsonObject {
                                    put("ruleId", "MCS021")
                                    put("level", "error")
                                    putJsonObject("message") { put("text", "${iss.code}: ${iss.message}") }
                                    putJsonArray("locations") {
                                        add(buildJsonObject {
                                            putJsonObject("physicalLocation") {
                                                putJsonObject("artifactLocation") {
                                                    put("uri", iss.path.replace('\\', '/'))
                                                }
                                            }
                                        })
                                    }
                                })
                            }
                        }
                    })
                }
            }
            Files.writeString(target, PRETTY_JSON.encodeToString(sarifDoc))
        }
        if (json) {
            val payload = buildJsonObject {
                put("ok", report.ok)
                put("world_dir", report.worldDir)
                put("level_dat_valid", report.levelDatValid)
                put("session_lock_present", report.sessionLockPresent)
                put("region_files_scanned", report.regionFilesScanned)
                put("valid_chunk_headers", report.validChunkHeaders)
                put("issue_count", report.issues.size)
                putJsonArray("issues") {
                    for (iss in report.issues) {
                        add(buildJsonObject {
                            put("code", iss.code)
                            put("path", iss.path)
                            put("message", iss.message)
                        })
                    }
                }
            }
            echo(PRETTY_JSON.encodeToString(payload))
        } else {
            echo("World Integrity Check (MCS021): ${if (report.ok) "PASS" else "FAIL"}")
            echo("  World         : ${report.worldDir}")
            echo("  level.dat     : ${if (report.levelDatValid) "valid GZIP" else "INVALID/MISSING"}")
            echo("  session.lock  : ${if (report.sessionLockPresent) "present" else "cleared"}")
            echo("  Region files  : ${report.regionFilesScanned} scanned (${report.validChunkHeaders} valid chunk headers)")
            for (iss in report.issues) {
                echo("  [ERROR] ${iss.code}: ${iss.message} (${iss.path})")
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
