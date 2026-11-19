package com.salyvn.mcstage.run

import com.salyvn.mcstage.core.Durations
import java.time.Duration

/**
 * Evaluates Folia regional tick fault containment, sliding-window crash-loop thresholds, and post-fault
 * regional recovery budgets (`MCS015` / `assert-region-recovery`).
 */
object FoliaCrashRecoveryAnalyzer {

    enum class Kind {
        HARD_CRASH_LOOP,
        CRASH_LOOP_EXCEEDED,
        UNRECOVERED_REGION_FAULT,
        PROBE_FAILED,
    }

    data class FaultEvent(
        val regionKey: String,
        val lineNumber: Int,
        val timestampMillis: Long,
        val matchedLine: String,
        val hardCrashLoop: Boolean,
    )

    data class Violation(
        val kind: Kind,
        val regionKey: String,
        val message: String,
        val lineNumber: Int? = null,
        val matchedLine: String? = null,
        val faultCount: Int = 0,
    )

    data class Assessment(
        val violation: Violation?,
        val totalFaults: Int,
        val recoveredRegions: Set<String>,
        val maxBurstPerRegion: Int,
    )

    val HARD_CRASH_LOOP_REGEX = Regex(
        """Region\s+crash[- ]loop\s+detected|Fatal\s+exception\s+ticking\s+region|ThreadedRegionizer[^\n]*?\baborted\s+region\s+tick\s+loop\b""",
        RegexOption.IGNORE_CASE,
    )

    val REGIONAL_FAULT_REGEX = Regex(
        """(?:Exception|Error|Crash)\s+ticking\s+region\b|Failed\s+to\s+tick\s+region\b|Unhandled\s+regional\s+tick\s+exception\b|\[Region\s+Scheduler\s+Thread\s*#\d+/(?:ERROR|WARN)\]:\s*(?:Unhandled\s+exception|Could\s+not\s+pass\s+event|Exception|Failed|java\.lang\.)|EntityScheduler[^\n]*?\b(?:exception|crashed|fault|retired\s+after\s+exception)\b|RegionizedTaskQueue[^\n]*?\b(?:exception|crashed|aborted)\b""",
        RegexOption.IGNORE_CASE,
    )

    val DEFAULT_RECOVERY_REGEX = Regex(
        """Recovered\s+region\b|Region[^\n]*?\bresumed\s+ticking\b|Region\s+recovery\s+complete\b|Isolated\s+faulting\s+(?:entity|task)\s+in\s+region\b|EntityScheduler\s+retired\s+faulting\s+task\b""",
        RegexOption.IGNORE_CASE,
    )

    private val SECTION_COORD_REGEX = Regex("""section\s*\[?\(\s*(-?\d+)\s*,\s*(-?\d+)\s*\)\]?""", RegexOption.IGNORE_CASE)
    private val CHUNK_COORD_REGEX = Regex("""chunk\s*\[?\(\s*(-?\d+)\s*,\s*(-?\d+)\s*\)\]?""", RegexOption.IGNORE_CASE)
    private val REGION_ID_REGEX = Regex("""\bregion\s*#(\d+)\b""", RegexOption.IGNORE_CASE)
    private val THREAD_ID_REGEX = Regex("""Region\s+Scheduler\s+Thread\s*#(\d+)""", RegexOption.IGNORE_CASE)
    private val CLOCK_STAMP_REGEX = Regex("""^\[(\d{2}):(\d{2}):(\d{2})(?:\.(\d{1,3}))?\b""")

    fun extractRegionKey(line: String): String {
        SECTION_COORD_REGEX.find(line)?.let { m ->
            return "section(${m.groupValues[1]},${m.groupValues[2]})"
        }
        CHUNK_COORD_REGEX.find(line)?.let { m ->
            val cx = m.groupValues[1].toIntOrNull()
            val cz = m.groupValues[2].toIntOrNull()
            if (cx != null && cz != null) {
                return "section(${Math.floorDiv(cx, 16)},${Math.floorDiv(cz, 16)})"
            }
        }
        REGION_ID_REGEX.find(line)?.let { m ->
            return "region#${m.groupValues[1]}"
        }
        THREAD_ID_REGEX.find(line)?.let { m ->
            return "thread#${m.groupValues[1]}"
        }
        return "region:default"
    }

    private fun extractExplicitRegionKeyOrNull(line: String): String? {
        val key = extractRegionKey(line)
        return if (key == "region:default") null else key
    }

    private fun effectiveTimestampMillis(line: LogLine, index: Int): Long {
        if (line.elapsedMillis > 0L) return line.elapsedMillis
        CLOCK_STAMP_REGEX.find(line.text.trim())?.let { m ->
            val h = m.groupValues[1].toLongOrNull() ?: 0L
            val min = m.groupValues[2].toLongOrNull() ?: 0L
            val s = m.groupValues[3].toLongOrNull() ?: 0L
            val msRaw = m.groupValues[4]
            val ms = when (msRaw.length) {
                1 -> (msRaw.toLongOrNull() ?: 0L) * 100L
                2 -> (msRaw.toLongOrNull() ?: 0L) * 10L
                3 -> msRaw.toLongOrNull() ?: 0L
                else -> 0L
            }
            return (((h * 60L + min) * 60L) + s) * 1000L + ms
        }
        return index * 50L
    }

    fun isFaultLine(text: String): Boolean =
        HARD_CRASH_LOOP_REGEX.containsMatchIn(text) || REGIONAL_FAULT_REGEX.containsMatchIn(text)

    fun evaluate(
        transcript: List<LogLine>,
        maxFaultsPerRegion: Int = 1,
        loopWindow: Duration = Duration.ofSeconds(5),
        maxRecoveryTime: Duration = Duration.ofSeconds(2),
        requireRecoveryOnFault: Boolean = true,
        customRecoveryMatcher: LineMatcher? = null,
    ): Assessment {
        val windowMs = maxOf(1L, loopWindow.toMillis())
        val recoveryBudgetMs = maxOf(1L, maxRecoveryTime.toMillis())
        val regionFaultHistory = linkedMapOf<String, ArrayDeque<FaultEvent>>()
        val pendingRecovery = linkedMapOf<String, FaultEvent>()
        val recoveredRegions = linkedSetOf<String>()
        var totalFaults = 0
        var maxBurst = 0

        for ((idx, line) in transcript.withIndex()) {
            val nowMs = effectiveTimestampMillis(line, idx)
            val text = line.text

            // Check whether any pending faulted region has already breached maxRecoveryTime before this line.
            if (requireRecoveryOnFault && pendingRecovery.isNotEmpty()) {
                for ((regionKey, firstUnrecovered) in pendingRecovery) {
                    val elapsedSinceFault = nowMs - firstUnrecovered.timestampMillis
                    if (elapsedSinceFault > recoveryBudgetMs) {
                        return Assessment(
                            violation = Violation(
                                kind = Kind.UNRECOVERED_REGION_FAULT,
                                regionKey = regionKey,
                                message = "Folia $regionKey did not recover within ${Durations.format(maxRecoveryTime)} " +
                                    "after regional fault at line ${firstUnrecovered.lineNumber}: ${firstUnrecovered.matchedLine}",
                                lineNumber = firstUnrecovered.lineNumber,
                                matchedLine = firstUnrecovered.matchedLine,
                                faultCount = totalFaults,
                            ),
                            totalFaults = totalFaults,
                            recoveredRegions = recoveredRegions,
                            maxBurstPerRegion = maxBurst,
                        )
                    }
                }
            }

            val hardLoop = HARD_CRASH_LOOP_REGEX.containsMatchIn(text)
            val regionalFault = hardLoop || REGIONAL_FAULT_REGEX.containsMatchIn(text)

            if (regionalFault) {
                val regionKey = extractRegionKey(text)
                val event = FaultEvent(
                    regionKey = regionKey,
                    lineNumber = line.number,
                    timestampMillis = nowMs,
                    matchedLine = text.trim(),
                    hardCrashLoop = hardLoop,
                )
                totalFaults++

                if (hardLoop) {
                    return Assessment(
                        violation = Violation(
                            kind = Kind.HARD_CRASH_LOOP,
                            regionKey = regionKey,
                            message = "Folia regional crash-loop reported on $regionKey: ${event.matchedLine}",
                            lineNumber = event.lineNumber,
                            matchedLine = event.matchedLine,
                            faultCount = totalFaults,
                        ),
                        totalFaults = totalFaults,
                        recoveredRegions = recoveredRegions,
                        maxBurstPerRegion = maxOf(maxBurst, 1),
                    )
                }

                val deque = regionFaultHistory.getOrPut(regionKey) { ArrayDeque() }
                while (deque.isNotEmpty() && nowMs - deque.first().timestampMillis > windowMs) {
                    deque.removeFirst()
                }
                deque.addLast(event)
                if (deque.size > maxBurst) maxBurst = deque.size

                if (deque.size > maxFaultsPerRegion) {
                    return Assessment(
                        violation = Violation(
                            kind = Kind.CRASH_LOOP_EXCEEDED,
                            regionKey = regionKey,
                            message = "Folia $regionKey faulted ${deque.size} time(s) within ${Durations.format(loopWindow)} " +
                                "(max-faults $maxFaultsPerRegion): ${event.matchedLine}",
                            lineNumber = event.lineNumber,
                            matchedLine = event.matchedLine,
                            faultCount = deque.size,
                        ),
                        totalFaults = totalFaults,
                        recoveredRegions = recoveredRegions,
                        maxBurstPerRegion = maxBurst,
                    )
                }

                pendingRecovery.putIfAbsent(regionKey, event)
                continue
            }

            val matchesRecovery = (customRecoveryMatcher?.matches(text) == true) ||
                DEFAULT_RECOVERY_REGEX.containsMatchIn(text)
            if (matchesRecovery && pendingRecovery.isNotEmpty()) {
                val explicitTarget = extractExplicitRegionKeyOrNull(text)
                if (explicitTarget != null && pendingRecovery.containsKey(explicitTarget)) {
                    val fault = pendingRecovery.remove(explicitTarget)!!
                    if (requireRecoveryOnFault && nowMs - fault.timestampMillis > recoveryBudgetMs) {
                        return Assessment(
                            violation = Violation(
                                kind = Kind.UNRECOVERED_REGION_FAULT,
                                regionKey = explicitTarget,
                                message = "Folia $explicitTarget recovered after ${nowMs - fault.timestampMillis}ms, " +
                                    "exceeding max-recovery ${Durations.format(maxRecoveryTime)}",
                                lineNumber = line.number,
                                matchedLine = text.trim(),
                                faultCount = totalFaults,
                            ),
                            totalFaults = totalFaults,
                            recoveredRegions = recoveredRegions,
                            maxBurstPerRegion = maxBurst,
                        )
                    }
                    recoveredRegions += explicitTarget
                } else {
                    // Global or custom recovery confirmation clears all regions within recoveryBudgetMs.
                    val iterator = pendingRecovery.entries.iterator()
                    while (iterator.hasNext()) {
                        val (rk, fault) = iterator.next()
                        if (requireRecoveryOnFault && nowMs - fault.timestampMillis > recoveryBudgetMs) {
                            return Assessment(
                                violation = Violation(
                                    kind = Kind.UNRECOVERED_REGION_FAULT,
                                    regionKey = rk,
                                    message = "Folia $rk recovered after ${nowMs - fault.timestampMillis}ms, " +
                                        "exceeding max-recovery ${Durations.format(maxRecoveryTime)}",
                                    lineNumber = line.number,
                                    matchedLine = text.trim(),
                                    faultCount = totalFaults,
                                ),
                                totalFaults = totalFaults,
                                recoveredRegions = recoveredRegions,
                                maxBurstPerRegion = maxBurst,
                            )
                        }
                        recoveredRegions += rk
                        iterator.remove()
                    }
                }
            }
        }

        if (requireRecoveryOnFault && pendingRecovery.isNotEmpty()) {
            val (regionKey, fault) = pendingRecovery.entries.first()
            return Assessment(
                violation = Violation(
                    kind = Kind.UNRECOVERED_REGION_FAULT,
                    regionKey = regionKey,
                    message = "Folia $regionKey remained unrecovered after regional fault at line ${fault.lineNumber}: ${fault.matchedLine}",
                    lineNumber = fault.lineNumber,
                    matchedLine = fault.matchedLine,
                    faultCount = totalFaults,
                ),
                totalFaults = totalFaults,
                recoveredRegions = recoveredRegions,
                maxBurstPerRegion = maxBurst,
            )
        }

        return Assessment(
            violation = null,
            totalFaults = totalFaults,
            recoveredRegions = recoveredRegions,
            maxBurstPerRegion = maxBurst,
        )
    }

    /**
     * Passive transcript detector used by [CrashAnalyzer] (`MCS015`) when no explicit step parameters are given:
     * flags explicit regional crash-loop messages or >= 3 repeated faults in a single region within 10s.
     */
    fun detectPassiveCrashLoop(transcript: List<LogLine>): Violation? =
        evaluate(
            transcript = transcript,
            maxFaultsPerRegion = 2,
            loopWindow = Duration.ofSeconds(10),
            maxRecoveryTime = Duration.ofSeconds(10),
            requireRecoveryOnFault = false,
        ).violation
}
