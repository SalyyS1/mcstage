package com.salyvn.mcstage.run

import com.salyvn.mcstage.core.Durations
import java.time.Duration
import java.util.Locale

/**
 * Evaluates server console transcripts for Folia region merge/split barrier stalls, Moonrise chunk-task
 * scheduler deadlocks, synchronous chunk loads on tick threads, and tick-lag spikes exceeding a scenario's
 * `max-stall` budget.
 */
object BarrierStallAnalyzer {

    enum class Kind {
        HARD_BARRIER_STALL,
        SYNC_CHUNK_LOAD,
        TICK_LAG_EXCEEDED,
        PROBE_LATENCY_EXCEEDED,
        PROBE_FAILED,
    }

    data class Violation(
        val kind: Kind,
        val message: String,
        val lineNumber: Int? = null,
        val matchedLine: String? = null,
        val measuredMillis: Double? = null,
    )

    val HARD_BARRIER_REGEX = Regex(
        """(?:ThreadedRegionizer|ChunkTaskScheduler|ChunkHolderManager|RegionizedTaskQueue|ChunkSystem)[^\n]*?\b(?:stall|stalled|deadlock|blocked|error|crash|failed|timeout)\b|(?:Chunk|Region)(?:\s+(?:merge|split))?\s+barrier\s+(?:stall|stalled|deadlock|timeout|exceeded)|Waiting on chunk\s*\(-?\d+,\s*-?\d+\)\s*on\s*(?:tick|region)\s*thread""",
        RegexOption.IGNORE_CASE,
    )

    val SYNC_CHUNK_LOAD_REGEX = Regex(
        """Loaded chunk\s*\(-?\d+,\s*-?\d+\)\s*synchronously|\bsync-chunk-load\b""",
        RegexOption.IGNORE_CASE,
    )

    private val OVERLOADED_MILLIS_REGEX = Regex(
        """Can't keep up!\s*Is the server overloaded\?\s*Running\s+(\d+(?:\.\d+)?)ms\s+or\s+\d+\s+ticks?\s+behind""",
        RegexOption.IGNORE_CASE,
    )

    private val REGION_STALL_MILLIS_REGEX = Regex(
        """(?:Ticking region[^\n]*?took|Region\s*#?\d+[^\n]*?stalled for|barrier\s+wait(?:ed|\s+exceeded)?)\s+(\d+(?:\.\d+)?)ms""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Scans `lines` in order and returns the first chunk/region barrier stall or tick-lag violation
     * exceeding `maxStall`, or `null` when all observed lines stay within budget.
     */
    fun detectViolation(lines: Iterable<LogLine>, maxStall: Duration): Violation? {
        val budgetMillis = maxStall.toMillis().toDouble()
        for (line in lines) {
            val text = line.text
            val trimmed = text.trim()

            // First check quantified stall lines so e.g. "Region #2 stalled for 120ms" can be compared
            // directly against maxStall before falling back to unquantified hard-stall signatures.
            val quantified = extractStallMillis(text)
            if (quantified != null) {
                if (quantified > budgetMillis) {
                    val formattedMs = formatMillis(quantified)
                    return Violation(
                        kind = Kind.TICK_LAG_EXCEEDED,
                        message = "barrier or tick stall of ${formattedMs}ms exceeded max-stall " +
                            "${Durations.format(maxStall)} at line ${line.number}: $trimmed",
                        lineNumber = line.number,
                        matchedLine = trimmed,
                        measuredMillis = quantified,
                    )
                }
                // Quantified Running Xms behind / Ticking region took Xms within budget is permitted,
                // unless it is also an explicit deadlock/crash/error in ChunkTaskScheduler/ThreadedRegionizer.
                if (!containsExplicitDeadlockOrFailure(text)) {
                    continue
                }
            }

            if (HARD_BARRIER_REGEX.containsMatchIn(text)) {
                return Violation(
                    kind = Kind.HARD_BARRIER_STALL,
                    message = "chunk or region barrier stall at line ${line.number}: $trimmed",
                    lineNumber = line.number,
                    matchedLine = trimmed,
                )
            }

            if (SYNC_CHUNK_LOAD_REGEX.containsMatchIn(text)) {
                return Violation(
                    kind = Kind.SYNC_CHUNK_LOAD,
                    message = "synchronous chunk load on tick thread at line ${line.number}: $trimmed",
                    lineNumber = line.number,
                    matchedLine = trimmed,
                )
            }
        }
        return null
    }

    fun extractStallMillis(text: String): Double? {
        OVERLOADED_MILLIS_REGEX.find(text)?.groupValues?.getOrNull(1)?.toDoubleOrNull()?.let { return it }
        REGION_STALL_MILLIS_REGEX.find(text)?.groupValues?.getOrNull(1)?.toDoubleOrNull()?.let { return it }
        return null
    }

    private fun containsExplicitDeadlockOrFailure(text: String): Boolean =
        Regex("""\b(?:deadlock|crash|failed|error)\b""", RegexOption.IGNORE_CASE).containsMatchIn(text) &&
            Regex("""(?:ThreadedRegionizer|ChunkTaskScheduler|ChunkHolderManager|RegionizedTaskQueue|ChunkSystem)""", RegexOption.IGNORE_CASE)
                .containsMatchIn(text)

    private fun formatMillis(ms: Double): String =
        if (ms == ms.toLong().toDouble()) ms.toLong().toString()
        else String.format(Locale.ROOT, "%.1f", ms)
}
