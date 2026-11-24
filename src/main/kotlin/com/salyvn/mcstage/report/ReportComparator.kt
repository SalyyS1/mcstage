package com.salyvn.mcstage.report

import com.salyvn.mcstage.core.BuildInfo
import com.salyvn.mcstage.run.CaseResult
import com.salyvn.mcstage.run.DiagnosticFinding
import com.salyvn.mcstage.run.Outcome
import com.salyvn.mcstage.run.RunReport
import com.salyvn.mcstage.run.Scenario
import com.salyvn.mcstage.core.Platform
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.time.Duration
import java.time.Instant
import kotlin.math.roundToInt

/**
 * Compares two mcstage JSON reports (baseline vs current) to gate CI on:
 * - outcome regressions (`passed` -> `failed`/`errored`),
 * - boot/scenario elapsed-time regressions above `maxSlowdownPercent`, and
 * - newly introduced `MCSxxx` diagnostic rules.
 */
object ReportComparator {

    data class CaseComparison(
        val platform: String,
        val version: String,
        val baselineOutcome: String?,
        val currentOutcome: String,
        val baselineElapsedMs: Long?,
        val currentElapsedMs: Long,
        val elapsedDeltaMs: Long?,
        val elapsedDeltaPercent: Double?,
        val outcomeRegressed: Boolean,
        val durationRegressed: Boolean,
        val newRules: List<String>,
        val resolvedRules: List<String>,
        val regressions: List<String>,
    ) {
        val label: String get() = "$platform/$version"
        val hasRegression: Boolean get() = regressions.isNotEmpty()
    }

    data class ComparisonResult(
        val baselineScenario: String,
        val currentScenario: String,
        val maxSlowdownPercent: Double,
        val cases: List<CaseComparison>,
        val totalRegressions: Int,
        val ok: Boolean,
    )

    private data class ParsedCase(
        val platform: String,
        val version: String,
        val outcome: String,
        val elapsedMs: Long,
        val rules: Set<String>,
    )

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    fun compare(
        baselineJson: String,
        currentJson: String,
        maxSlowdownPercent: Double = 25.0,
        failOnNewDiagnostics: Boolean = true,
    ): ComparisonResult {
        val baseObj = json.parseToJsonElement(baselineJson).jsonObject
        val currObj = json.parseToJsonElement(currentJson).jsonObject

        val baseScenario = baseObj["scenario"]?.jsonPrimitive?.contentOrNull ?: "baseline"
        val currScenario = currObj["scenario"]?.jsonPrimitive?.contentOrNull ?: "current"

        val baseCases = parseCases(baseObj["cases"]?.jsonArray).associateBy { "${it.platform}/${it.version}" }
        val currCases = parseCases(currObj["cases"]?.jsonArray)

        val comparisons = currCases.map { curr ->
            val key = "${curr.platform}/${curr.version}"
            val base = baseCases[key]
            val deltaMs = if (base != null) curr.elapsedMs - base.elapsedMs else null
            val deltaPct = if (base != null && base.elapsedMs > 0L) {
                ((curr.elapsedMs - base.elapsedMs).toDouble() / base.elapsedMs.toDouble()) * 100.0
            } else {
                null
            }
            val outcomeRegressed = base != null && base.outcome == "passed" && curr.outcome != "passed"
            val durationRegressed = deltaPct != null && deltaPct > maxSlowdownPercent
            val newRules = if (base != null) (curr.rules - base.rules).sorted() else curr.rules.sorted()
            val resolvedRules = if (base != null) (base.rules - curr.rules).sorted() else emptyList()

            val reasons = mutableListOf<String>()
            if (outcomeRegressed) {
                reasons.add("Outcome regressed from passed to ${curr.outcome}")
            }
            if (durationRegressed && deltaPct != null && base != null) {
                val pctStr = ((deltaPct * 10.0).roundToInt() / 10.0)
                reasons.add("Elapsed time increased by +$pctStr% (${base.elapsedMs}ms -> ${curr.elapsedMs}ms, limit +$maxSlowdownPercent%)")
            }
            if (failOnNewDiagnostics && newRules.isNotEmpty() && base != null) {
                reasons.add("New diagnostics introduced: ${newRules.joinToString(", ")}")
            }

            CaseComparison(
                platform = curr.platform,
                version = curr.version,
                baselineOutcome = base?.outcome,
                currentOutcome = curr.outcome,
                baselineElapsedMs = base?.elapsedMs,
                currentElapsedMs = curr.elapsedMs,
                elapsedDeltaMs = deltaMs,
                elapsedDeltaPercent = deltaPct,
                outcomeRegressed = outcomeRegressed,
                durationRegressed = durationRegressed,
                newRules = newRules,
                resolvedRules = resolvedRules,
                regressions = reasons,
            )
        }

        val totalReg = comparisons.sumOf { it.regressions.size }
        return ComparisonResult(
            baselineScenario = baseScenario,
            currentScenario = currScenario,
            maxSlowdownPercent = maxSlowdownPercent,
            cases = comparisons,
            totalRegressions = totalReg,
            ok = totalReg == 0,
        )
    }

    fun toJson(result: ComparisonResult): JsonObject = buildJsonObject {
        put("baseline_scenario", result.baselineScenario)
        put("current_scenario", result.currentScenario)
        put("max_slowdown_percent", result.maxSlowdownPercent)
        put("ok", result.ok)
        put("total_regressions", result.totalRegressions)
        putJsonArray("cases") {
            for (c in result.cases) {
                add(buildJsonObject {
                    put("platform", c.platform)
                    put("version", c.version)
                    c.baselineOutcome?.let { put("baseline_outcome", it) }
                    put("current_outcome", c.currentOutcome)
                    c.baselineElapsedMs?.let { put("baseline_elapsed_ms", it) }
                    put("current_elapsed_ms", c.currentElapsedMs)
                    c.elapsedDeltaMs?.let { put("elapsed_delta_ms", it) }
                    c.elapsedDeltaPercent?.let { put("elapsed_delta_percent", ((it * 100.0).roundToInt() / 100.0)) }
                    put("outcome_regressed", c.outcomeRegressed)
                    put("duration_regressed", c.durationRegressed)
                    put("has_regression", c.hasRegression)
                    putJsonArray("new_rules") { c.newRules.forEach { add(JsonPrimitive(it)) } }
                    putJsonArray("resolved_rules") { c.resolvedRules.forEach { add(JsonPrimitive(it)) } }
                    putJsonArray("regressions") { c.regressions.forEach { add(JsonPrimitive(it)) } }
                })
            }
        }
    }

    fun renderJson(result: ComparisonResult): String =
        json.encodeToString(JsonObject.serializer(), toJson(result))

    fun renderMarkdown(result: ComparisonResult): String {
        val sb = StringBuilder()
        sb.appendLine("### mcstage Report Comparison (${if (result.ok) "PASS" else "REGRESSION DETECTED"})")
        sb.appendLine()
        sb.appendLine("| Target | Baseline | Current | Elapsed Delta | New Rules | Status |")
        sb.appendLine("| --- | --- | --- | --- | --- | --- |")
        for (c in result.cases) {
            val deltaStr = when {
                c.elapsedDeltaMs == null -> "n/a"
                c.elapsedDeltaPercent != null -> {
                    val sign = if (c.elapsedDeltaMs >= 0) "+" else ""
                    val pct = (c.elapsedDeltaPercent * 10.0).roundToInt() / 10.0
                    "${sign}${c.elapsedDeltaMs}ms (${sign}${pct}%)"
                }
                else -> "${c.elapsedDeltaMs}ms"
            }
            val rulesStr = if (c.newRules.isEmpty()) "none" else c.newRules.joinToString(", ")
            val status = if (c.hasRegression) "REGRESSED" else "OK"
            sb.appendLine("| `${c.label}` | ${c.baselineOutcome ?: "new"} | ${c.currentOutcome} | $deltaStr | $rulesStr | $status |")
        }
        return sb.toString()
    }

    fun renderSarif(result: ComparisonResult): String {
        val caseResults = result.cases.map { c ->
            val platform = Platform.entries.firstOrNull { it.id.equals(c.platform, ignoreCase = true) } ?: Platform.PAPER
            val findings = c.regressions.map { msg ->
                DiagnosticFinding(
                    ruleId = "MCS020",
                    severity = "error",
                    message = msg,
                )
            }
            CaseResult(
                case = Scenario.Case(platform, c.version),
                outcome = if (c.hasRegression) Outcome.FAILED else Outcome.PASSED,
                elapsed = Duration.ofMillis(c.currentElapsedMs),
                failure = c.regressions.firstOrNull(),
                diagnostics = findings,
            )
        }
        val runReport = RunReport(
            tool = BuildInfo.NAME,
            toolVersion = BuildInfo.version,
            scenario = "${result.baselineScenario} -> ${result.currentScenario}",
            startedAt = Instant.now(),
            cases = caseResults,
        )
        return SarifRunReporter().render(runReport)
    }

    private fun parseCases(array: JsonArray?): List<ParsedCase> {
        if (array == null) return emptyList()
        return array.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val platform = obj["platform"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val version = obj["version"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val outcome = obj["outcome"]?.jsonPrimitive?.contentOrNull ?: "unknown"
            val elapsedMs = obj["elapsedMillis"]?.jsonPrimitive?.longOrNull ?: 0L
            val rules = obj["diagnostics"]?.jsonArray?.mapNotNull { d ->
                (d as? JsonObject)?.get("ruleId")?.jsonPrimitive?.contentOrNull
            }?.toSet() ?: emptySet()
            ParsedCase(platform, version, outcome, elapsedMs, rules)
        }
    }
}
