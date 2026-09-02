package com.salyvn.mcstage.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration

class DurationsTest {

    @Test
    fun `reads the forms a scenario file uses`() {
        assertEquals(Duration.ofSeconds(30), Durations.parse("30s"))
        assertEquals(Duration.ofSeconds(30), Durations.parse("30"))
        assertEquals(Duration.ofMillis(500), Durations.parse("500ms"))
        assertEquals(Duration.ofMinutes(2), Durations.parse("2m"))
        assertEquals(Duration.ofHours(1), Durations.parse("1h"))
        assertEquals(Duration.ofMillis(1500), Durations.parse("1.5s"))
        assertEquals(Duration.ofSeconds(45), Durations.parse(" 45 S "))
    }

    @Test
    fun `refuses what it cannot read, naming what was expected`() {
        assertThrows<SetupError> { Durations.parse("soon") }
        assertThrows<SetupError> { Durations.parse("-5s") }
        assertThrows<SetupError> { Durations.parse("") }
        assertEquals(
            true,
            assertThrows<SetupError> { Durations.parse("10 weeks") }.message!!.contains("Unknown unit"),
        )
    }

    @Test
    fun `formats for people rather than for parsers`() {
        assertEquals("900ms", Durations.format(Duration.ofMillis(900)))
        assertEquals("1.5s", Durations.format(Duration.ofMillis(1500)))
        assertEquals("2m", Durations.format(Duration.ofMinutes(2)))
        assertEquals("90.0s", Durations.format(Duration.ofSeconds(90)))
    }
}
