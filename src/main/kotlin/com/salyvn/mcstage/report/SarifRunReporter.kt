package com.salyvn.mcstage.report

import com.salyvn.mcstage.run.CaseResult
import com.salyvn.mcstage.run.CrashAnalyzer
import com.salyvn.mcstage.run.DiagnosticFinding
import com.salyvn.mcstage.run.RunReport
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.nio.file.Path

/**
 * OASIS SARIF v2.1.0 report (`--format sarif` or `--sarif <path>`) for GitHub Code Scanning, GitLab SAST,
 * and IDE SARIF viewers.
 */
class SarifRunReporter {

    fun render(report: RunReport): String = json.encodeToString(JsonObject.serializer(), asSarif(report))

    private fun asSarif(report: RunReport): JsonObject {
        val scenarioUri = normalizeRelativeUri(report.scenarioFile)
        return buildJsonObject {
            put("\$schema", SARIF_SCHEMA)
            put("version", "2.1.0")
            putJsonArray("runs") {
                add(
                    buildJsonObject {
                        putJsonObject("tool") {
                            putJsonObject("driver") {
                                put("name", report.tool)
                                put("version", report.toolVersion)
                                put("informationUri", "https://github.com/SalyyS1/mcstage")
                                putJsonArray("rules") {
                                    for (rule in CrashAnalyzer.RULES) {
                                        add(
                                            buildJsonObject {
                                                put("id", rule.id)
                                                put("name", rule.name)
                                                putJsonObject("shortDescription") {
                                                    put("text", rule.shortDescription)
                                                }
                                                putJsonObject("fullDescription") {
                                                    put("text", rule.fullDescription)
                                                }
                                                putJsonObject("help") {
                                                    put("text", rule.help)
                                                }
                                                putJsonObject("defaultConfiguration") {
                                                    put("level", rule.defaultSeverity)
                                                }
                                            },
                                        )
                                    }
                                }
                            }
                        }
                        putJsonArray("invocations") {
                            add(
                                buildJsonObject {
                                    put("executionSuccessful", report.errored == 0)
                                    put("startTimeUtc", report.startedAt.toString())
                                },
                            )
                        }
                        putJsonArray("results") {
                            for (case in report.cases) {
                                for (finding in effectiveFindings(case)) {
                                    add(renderResult(case, finding, scenarioUri))
                                }
                            }
                        }
                    },
                )
            }
        }
    }

    private fun effectiveFindings(case: CaseResult): List<DiagnosticFinding> {
        if (case.diagnostics.isNotEmpty()) return case.diagnostics
        if (case.ok) return emptyList()
        val fallbackRule = if (case.outcome == com.salyvn.mcstage.run.Outcome.ERROR) "MCS011" else "MCS008"
        return listOf(
            DiagnosticFinding(
                ruleId = fallbackRule,
                severity = "error",
                message = case.failure ?: "Scenario case failed",
            ),
        )
    }

    private fun renderResult(
        case: CaseResult,
        finding: DiagnosticFinding,
        scenarioUri: String,
    ): JsonObject = buildJsonObject {
        val ruleIndex = CrashAnalyzer.RULES.indexOfFirst { it.id == finding.ruleId }
        put("ruleId", finding.ruleId)
        if (ruleIndex >= 0) put("ruleIndex", ruleIndex)
        put("level", finding.severity)
        putJsonObject("message") {
            put("text", "[${case.case.label}] ${finding.message}")
        }
        putJsonArray("locations") {
            add(
                buildJsonObject {
                    putJsonObject("physicalLocation") {
                        putJsonObject("artifactLocation") {
                            put("uri", scenarioUri)
                            put("uriBaseId", "%SRCROOT%")
                        }
                        putJsonObject("region") {
                            put("startLine", 1)
                        }
                    }
                    putJsonArray("logicalLocations") {
                        add(
                            buildJsonObject {
                                put("name", case.case.label)
                                put("fullyQualifiedName", "${case.case.platform.id}/${case.case.version}")
                                put("kind", "server-matrix-case")
                            },
                        )
                    }
                },
            )
        }
        putJsonObject("properties") {
            put("platform", case.case.platform.id)
            put("version", case.case.version)
            put("outcome", case.outcome.name.lowercase())
            put("elapsedMillis", case.elapsed.toMillis())
            finding.lineNumber?.let { put("logLine", it) }
            finding.matchedLine?.let { put("matchedLine", it) }
            finding.artifactPath?.let { put("instanceArtifact", it) }
            if (case.tail.isNotEmpty()) {
                putJsonArray("tail") {
                    for (line in case.tail.takeLast(15)) add(line)
                }
            }
        }
    }

    private fun normalizeRelativeUri(raw: String?): String {
        if (raw.isNullOrBlank()) return "mcstage.yml"
        val path = try {
            val candidate = Path.of(raw).normalize()
            val cwd = Path.of("").toAbsolutePath().normalize()
            if (candidate.isAbsolute) {
                if (candidate.startsWith(cwd)) cwd.relativize(candidate).toString()
                else candidate.fileName?.toString() ?: "mcstage.yml"
            } else {
                candidate.toString()
            }
        } catch (_: Exception) {
            raw
        }
        val forward = path.replace('\\', '/').trimStart('/')
        return if (forward.isEmpty() || forward.startsWith("..")) {
            Path.of(forward).fileName?.toString()?.takeIf { it.isNotEmpty() } ?: "mcstage.yml"
        } else {
            forward
        }
    }

    private companion object {
        const val SARIF_SCHEMA = "https://json.schemastore.org/sarif-2.1.0.json"
        val json = Json { prettyPrint = true }
    }
}
