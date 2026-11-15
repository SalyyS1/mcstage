package com.salyvn.mcstage.run

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration

class FoliaTopologyAndBarrierTest {

    @Test
    fun `planned disjoint region anchors maintain Chebyshev section distance at least 3 across origins and spacings`() {
        for (spacing in listOf(768, 1024, 2048)) {
            for (origin in listOf(0, 255, -513)) {
                val nodes = FoliaRegionTopology.planNodes(
                    regions = 9,
                    spacingBlocks = spacing,
                    originX = origin,
                    originY = 70,
                    originZ = -origin,
                )
                assertEquals(9, nodes.size)
                for (i in nodes.indices) {
                    for (j in i + 1 until nodes.size) {
                        assertTrue(
                            FoliaRegionTopology.areGuaranteedDisjoint(nodes[i], nodes[j]),
                            "Expected disjoint regions for spacing=$spacing origin=$origin: ${nodes[i]} vs ${nodes[j]}",
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `merge-split bridge planner connects all region anchors with 1-section stepping stones`() {
        val nodes = FoliaRegionTopology.planNodes(regions = 4, spacingBlocks = 768)
        val bridges = FoliaRegionTopology.planBridgeNodes(nodes)
        // 4 nodes on a 2x2 grid with 3-section (768-block) stride -> 3 spanning edges * 2 intermediate sections = 6 bridges.
        assertEquals(6, bridges.size)

        val allSections = (nodes + bridges).map { it.sectionX to it.sectionZ }.toSet()
        // Verify all 4 anchors belong to a single connected component when edges exist for Chebyshev distance <= 2.
        val visited = mutableSetOf<Pair<Int, Int>>()
        val queue = ArrayDeque<Pair<Int, Int>>()
        val start = nodes.first().let { it.sectionX to it.sectionZ }
        visited += start
        queue += start
        while (queue.isNotEmpty()) {
            val (sx, sz) = queue.removeFirst()
            for (cand in allSections) {
                if (cand !in visited && maxOf(kotlin.math.abs(cand.first - sx), kotlin.math.abs(cand.second - sz)) <= 2) {
                    visited += cand
                    queue += cand
                }
            }
        }
        for (anchor in nodes) {
            assertTrue((anchor.sectionX to anchor.sectionZ) in visited, "Anchor $anchor was not bridged")
        }
    }

    @Test
    fun `command template renders region, block, chunk, section, world, and pulse placeholders`() {
        val node = FoliaRegionTopology.RegionNode(index = 2, row = 1, col = 0, blockX = 1024, blockY = 64, blockZ = -768)
        val rendered = FoliaRegionTopology.renderCommand(
            "bench r={region} pos={x},{y},{z} chunk={chunkX},{chunkZ} sec={sectionX},{sectionZ} w={world} p={pulse}",
            node = node,
            world = "minecraft:the_nether",
            pulse = 5,
        )
        assertEquals("bench r=2 pos=1024,64,-768 chunk=64,-48 sec=4,-3 w=minecraft:the_nether p=5", rendered)
    }

    @Test
    fun `BarrierStallAnalyzer enforces max-stall thresholds and catches hard stalls and sync chunk loads`() {
        val minorJitter = listOf(
            LogLine(1, 100, "[12:00:01 WARN]: Can't keep up! Is the server overloaded? Running 180ms or 3 ticks behind"),
            LogLine(2, 200, "[12:00:02 INFO]: Ticking region #4 took 95.5ms"),
        )
        assertNull(BarrierStallAnalyzer.detectViolation(minorJitter, Duration.ofMillis(250)))

        val budgetExceeded = BarrierStallAnalyzer.detectViolation(minorJitter, Duration.ofMillis(150))
        assertNotNull(budgetExceeded)
        assertEquals(BarrierStallAnalyzer.Kind.TICK_LAG_EXCEEDED, budgetExceeded!!.kind)
        assertEquals(180.0, budgetExceeded.measuredMillis)

        val syncChunk = listOf(
            LogLine(3, 300, "[12:00:03 WARN]: Loaded chunk (-12, 34) synchronously on region thread"),
        )
        val syncViolation = BarrierStallAnalyzer.detectViolation(syncChunk, Duration.ofSeconds(5))
        assertNotNull(syncViolation)
        assertEquals(BarrierStallAnalyzer.Kind.SYNC_CHUNK_LOAD, syncViolation!!.kind)

        val hardDeadlock = listOf(
            LogLine(4, 400, "[12:00:04 ERROR]: ChunkTaskScheduler deadlock detected while waiting on holder (4, 8)"),
        )
        val hardViolation = BarrierStallAnalyzer.detectViolation(hardDeadlock, Duration.ofSeconds(5))
        assertNotNull(hardViolation)
        assertEquals(BarrierStallAnalyzer.Kind.HARD_BARRIER_STALL, hardViolation!!.kind)

        val crashFindings = CrashAnalyzer.analyze(minorJitter + hardDeadlock)
        assertTrue(crashFindings.any { it.ruleId == "MCS012" })
        assertTrue(crashFindings.any { it.ruleId == "MCS013" })
        assertNotNull(CrashAnalyzer.firstCrashGuardFailure(crashFindings))
        assertFalse(CrashAnalyzer.rule("MCS013").fatalForCrashGuard)
    }
}
