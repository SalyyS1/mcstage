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
}
