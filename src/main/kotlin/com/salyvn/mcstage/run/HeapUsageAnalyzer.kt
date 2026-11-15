package com.salyvn.mcstage.run

/**
 * Parses JVM GC log lines emitted by Paper/Folia/Purpur servers to track heap usage.
 *
 * Supported formats:
 *
 * 1. Java 11+ unified GC log (-Xlog:gc or -verbose:gc defaults):
 *       [GC(N) Pause ... 42M->38M(512M) ...]
 *       [GC(N) ... Heap: 384M(512M)->340M(512M)]
 *
 * 2. Paper/Folia watch-dog GC notification line:
 *       [Server thread/WARN]: Can't keep up! ...
 *       Ahead-of-time GC: used=NNNm/MaxM
 *
 * 3. Explicit -XX:+PrintGCDetails "PSYoungGen" / "PSOldGen" / "heap size" summary lines
 *    emitted by older Paper builds with G1GC verbose:
 *       Heap: 256.0M(512.0M)->112.3M(512.0M)
 *
 * Returns the peak observed usage percentage across all matching lines.
 */
object HeapUsageAnalyzer {

    /**
     * Regex matching the standard JVM unified GC log "after->before(max)" heap token.
     * Captures: afterMB, maxMB (the capacities are in the form NNNm or NNN.NM).
     */
    private val AFTER_SLASH_MAX = Regex(
        """(\d+(?:\.\d+)?)[Mm]?\s*\((\d+(?:\.\d+)?)[Mm]?\)""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Matches the GC transition "before->after(max)" form found in unified GC logs:
     *   42M->38M(512M)
     */
    private val TRANSITION = Regex(
        """\d+(?:\.\d+)?[Mm]?\s*->\s*(\d+(?:\.\d+)?)[Mm]?\s*\((\d+(?:\.\d+)?)[Mm]?\)""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Matches the Paper watch-dog "used=NNNm/MaxM" GC notice line.
     */
    private val USED_SLASH_MAX = Regex(
        """used\s*=\s*(\d+(?:\.\d+)?)\s*[Mm]/(\d+(?:\.\d+)?)\s*[Mm]""",
        RegexOption.IGNORE_CASE,
    )

    /** Parse a single GC log line and return heap usage percent [0.0, 100.0], or null. */
    fun parseUsagePercent(line: String): Double? {
        // Prefer the transition form "NM->NM(MaxM)" since it gives the post-GC used value.
        TRANSITION.find(line)?.let { m ->
            val after = m.groupValues[1].toDoubleOrNull() ?: return@let null
            val max = m.groupValues[2].toDoubleOrNull() ?: return@let null
            if (max > 0.0) return (after / max) * 100.0
        }
        // Fall back to "used=NNm/MaxM" (watch-dog line).
        USED_SLASH_MAX.find(line)?.let { m ->
            val used = m.groupValues[1].toDoubleOrNull() ?: return@let null
            val max = m.groupValues[2].toDoubleOrNull() ?: return@let null
            if (max > 0.0) return (used / max) * 100.0
        }
        // Fall back to "NM(MaxM)" form (unified log heap summary).
        AFTER_SLASH_MAX.find(line)?.let { m ->
            val used = m.groupValues[1].toDoubleOrNull() ?: return@let null
            val max = m.groupValues[2].toDoubleOrNull() ?: return@let null
            if (max > 0.0) return (used / max) * 100.0
        }
        return null
    }

    /** True when this line looks like a JVM GC-related output line worth parsing. */
    fun isGcLine(line: String): Boolean =
        line.contains("GC(") ||
            line.contains("Heap:") ||
            line.contains("Heap used") ||
            line.contains("used=") ||
            line.contains("->") && (line.contains("MB") || line.contains("M("))

    /**
     * Peak heap usage percent observed across all lines that look like GC output.
     * Returns null when no GC lines were found.
     */
    fun peakUsagePercent(lines: Iterable<String>): Double? =
        lines.filter { isGcLine(it) }.mapNotNull { parseUsagePercent(it) }.maxOrNull()
}
