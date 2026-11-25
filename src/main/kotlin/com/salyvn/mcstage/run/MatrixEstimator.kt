package com.salyvn.mcstage.run

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.math.ceil

/**
 * Estimates CI matrix runtime, peak RAM, JAR cache footprint, and recommended GitHub Actions
 * shard strategy for a parsed [Scenario] before launching real Paper/Folia/Purpur JVMs.
 */
object MatrixEstimator {

    data class Estimate(
        val scenarioName: String,
        val caseCount: Int,
        val stepCount: Int,
        val perCaseMemoryMb: Int,
        val recommendedParallelism: Int,
        val peakRamMb: Int,
        val estimatedJarCacheMb: Int,
        val estimatedSerialSeconds: Int,
        val estimatedParallelSeconds: Int,
        val recommendedShards: Int,
        val warnings: List<String>,
    )

    fun estimate(scenario: Scenario, hostRamGb: Double = 7.0, maxShards: Int = 4): Estimate {
        val cases = scenario.cases.size.coerceAtLeast(1)
        val steps = scenario.steps.size
        val memMb = parseMemoryMb(scenario.memory) ?: 2048
        val hostMb = (hostRamGb.coerceAtLeast(2.0) * 1024.0).toInt()
        val usableMb = (hostMb - 1200).coerceAtLeast(1024)
        val maxFitParallel = (usableMb / (memMb + 384)).coerceAtLeast(1).coerceAtMost(cases)
        val peakRamMb = maxFitParallel * (memMb + 384)

        val uniquePlatforms = scenario.cases.map { "${it.platform.id}:${it.version}" }.toSet().size
        val cacheMb = uniquePlatforms * 62 // ~62 MB average server jar per (platform, version)

        // Empirical Paper/Folia boot + steps baseline (cold boot ~28s + 4s/step)
        val perCaseSec = 28 + (steps * 4) + if (scenario.cases.any { it.platform.id == "folia" }) 8 else 0
        val serialSec = cases * perCaseSec
        val shards = maxShards.coerceIn(1, cases)
        val parallelSec = ceil(cases.toDouble() / shards.toDouble()).toInt() * perCaseSec

        val warnings = mutableListOf<String>()
        if (memMb + 384 > usableMb) {
            warnings += "Configured memory '${scenario.memory}' (${memMb}MB + JVM metaspace) exceeds safe budget on a ${hostRamGb}GB runner."
        }
        if (serialSec > 480) {
            warnings += "Serial execution (~${serialSec}s) exceeds 8 minutes; split into $shards GitHub Actions matrix shards (~${parallelSec}s)."
        }
        if (cases > 6) {
            warnings += "Large matrix ($cases cases): enable ~/.mcstage JAR cache restoration in CI to save ~${cacheMb}MB download per run."
        }

        return Estimate(
            scenarioName = scenario.name,
            caseCount = cases,
            stepCount = steps,
            perCaseMemoryMb = memMb,
            recommendedParallelism = maxFitParallel,
            peakRamMb = peakRamMb,
            estimatedJarCacheMb = cacheMb,
            estimatedSerialSeconds = serialSec,
            estimatedParallelSeconds = parallelSec,
            recommendedShards = shards,
            warnings = warnings,
        )
    }

    private fun parseMemoryMb(raw: String): Int? {
        val s = raw.trim().lowercase()
        return when {
            s.endsWith("g") -> s.dropLast(1).toDoubleOrNull()?.times(1024.0)?.toInt()
            s.endsWith("m") -> s.dropLast(1).toIntOrNull()
            else -> s.toIntOrNull()
        }
    }

    fun toJson(est: Estimate): JsonObject = buildJsonObject {
        put("scenario", est.scenarioName)
        put("case_count", est.caseCount)
        put("step_count", est.stepCount)
        put("per_case_memory_mb", est.perCaseMemoryMb)
        put("recommended_parallelism", est.recommendedParallelism)
        put("peak_ram_mb", est.peakRamMb)
        put("estimated_jar_cache_mb", est.estimatedJarCacheMb)
        put("estimated_serial_seconds", est.estimatedSerialSeconds)
        put("estimated_parallel_seconds", est.estimatedParallelSeconds)
        put("recommended_shards", est.recommendedShards)
        putJsonArray("warnings") { est.warnings.forEach { add(JsonPrimitive(it)) } }
    }

    private fun escHtml(raw: String): String =
        raw.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    fun renderHtml(est: Estimate): String {
        val speedup = if (est.estimatedParallelSeconds > 0) {
            String.format(java.util.Locale.ROOT, "%.1fx", est.estimatedSerialSeconds.toDouble() / est.estimatedParallelSeconds.toDouble())
        } else "1.0x"
        val warnHtml = if (est.warnings.isEmpty()) {
            "<p style=\"color:#22c55e\">All CI RAM &amp; shard thresholds are within safe runner limits.</p>"
        } else {
            "<ul>" + est.warnings.joinToString("") { "<li style=\"color:#f59e0b\">${escHtml(it)}</li>" } + "</ul>"
        }
        return buildString {
            append("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">")
            append("<title>mcstage CI Matrix RAM &amp; Shard Budget Report</title>")
            append("<style>body{margin:0;padding:28px;background:#0b0f17;color:#e6edf7;font-family:-apple-system,BlinkMacSystemFont,Segoe UI,sans-serif}.wrap{max-width:960px;margin:0 auto}.card{background:#121826;border:1px solid #26324d;border-radius:10px;padding:18px;margin-bottom:16px}.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(180px,1fr));gap:12px}.kpi{background:#172033;border:1px solid #26324d;border-radius:8px;padding:12px}.kpi b{display:block;font-size:20px;color:#38bdf8}</style>")
            append("</head><body><div class=\"wrap\"><div class=\"card\">")
            append("<h1>mcstage CI Matrix RAM &amp; Shard Budget</h1>")
            append("<p>Scenario: <b>${escHtml(est.scenarioName)}</b> &middot; Cases: <b>${est.caseCount}</b> &middot; Steps/Case: <b>${est.stepCount}</b></p>")
            append("<div class=\"grid\">")
            append("<div class=\"kpi\"><span>Per-Case Heap</span><b>${est.perCaseMemoryMb} MB</b></div>")
            append("<div class=\"kpi\"><span>Peak RAM (${est.recommendedParallelism}x)</span><b>${est.peakRamMb} MB</b></div>")
            append("<div class=\"kpi\"><span>JAR Cache</span><b>~${est.estimatedJarCacheMb} MB</b></div>")
            append("<div class=\"kpi\"><span>Sharded Runtime</span><b>~${est.estimatedParallelSeconds}s (${est.recommendedShards} shards, $speedup)</b></div>")
            append("</div></div><div class=\"card\"><h2>Guardrails &amp; Warnings</h2>")
            append(warnHtml)
            append("</div></div></body></html>")
        }
    }
}
