package com.salyvn.mcstage.run

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class HeapUsageAnalyzerTest {

    // --- parseUsagePercent ---

    @Test
    fun `parses GC transition NM-NM(MaxM) form`() {
        // After GC, heap went from 128M to 64M, max is 512M -> 64/512 = 12.5%
        val pct = HeapUsageAnalyzer.parseUsagePercent("[GC(2) Pause Young 128M->64M(512M) 5ms]")
        assertNotNull(pct)
        assertEquals(12.5, pct!!, 0.01)
    }

    @Test
    fun `parses used=NNm-MaxM watch-dog form`() {
        val pct = HeapUsageAnalyzer.parseUsagePercent(
            "[20:01:05 WARN]: Can't keep up! used=400m/512m"
        )
        assertNotNull(pct)
        assertEquals(400.0 / 512.0 * 100.0, pct!!, 0.01)
    }

    @Test
    fun `parses Heap NM(MaxM) summary form`() {
        val pct = HeapUsageAnalyzer.parseUsagePercent("Heap: 256M(512M)")
        assertNotNull(pct)
        assertEquals(50.0, pct!!, 0.01)
    }

    @Test
    fun `returns null for a non-GC line`() {
        assertNull(HeapUsageAnalyzer.parseUsagePercent("[Server thread/INFO]: Server started!"))
    }

    @Test
    fun `returns null for a line with max zero`() {
        // Avoids division by zero
        assertNull(HeapUsageAnalyzer.parseUsagePercent("Heap: 0M(0M)"))
    }

    @Test
    fun `prefers transition form over summary form when both present on a line`() {
        // A unified GC log line may carry both; transition is more accurate (post-GC used)
        val pct = HeapUsageAnalyzer.parseUsagePercent(
            "[GC(1) Pause Full 384M->200M(512M) Heap: 384M(512M)->200M(512M)]"
        )
        // Both give the same value here; just confirm it parsed without error
        assertNotNull(pct)
        assertEquals(200.0 / 512.0 * 100.0, pct!!, 0.01)
    }

    // --- isGcLine ---

    @Test
    fun `identifies GC log lines`() {
        assert(HeapUsageAnalyzer.isGcLine("[GC(5) Pause Young 64M->32M(256M) 2ms]"))
        assert(HeapUsageAnalyzer.isGcLine("Heap: 100M(512M)"))
        assert(HeapUsageAnalyzer.isGcLine("used=200m/512m"))
        assert(HeapUsageAnalyzer.isGcLine("Heap used is 50 percent"))
        assert(!HeapUsageAnalyzer.isGcLine("[Server thread/INFO]: Done (3.456s)!"))
    }

    // --- peakUsagePercent ---

    @Test
    fun `returns null when no GC lines found`() {
        val lines = listOf("[INFO]: Server started!", "[INFO]: Loading world")
        assertNull(HeapUsageAnalyzer.peakUsagePercent(lines))
    }

    @Test
    fun `returns max among multiple GC lines`() {
        val lines = listOf(
            "[GC(1) Pause Young 64M->32M(256M)]",    // 32/256 = 12.5%
            "[GC(2) Pause Young 128M->100M(256M)]",  // 100/256 = 39.1%
            "[GC(3) Pause Full  200M->50M(256M)]",   // 50/256 = 19.5%
            "[INFO]: Server is running",
        )
        val peak = HeapUsageAnalyzer.peakUsagePercent(lines)
        assertNotNull(peak)
        assertEquals(100.0 / 256.0 * 100.0, peak!!, 0.01)
    }

    @Test
    fun `handles floating-point MB values in GC log`() {
        val pct = HeapUsageAnalyzer.parseUsagePercent("Heap: 256.5M(512.0M)")
        assertNotNull(pct)
        assertEquals(256.5 / 512.0 * 100.0, pct!!, 0.01)
    }
}
