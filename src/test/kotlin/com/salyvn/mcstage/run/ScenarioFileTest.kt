package com.salyvn.mcstage.run

import com.salyvn.mcstage.core.Platform
import com.salyvn.mcstage.core.SetupError
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

class ScenarioFileTest {

    @TempDir
    lateinit var dir: Path

    @Test
    fun `reads a scenario and turns every platform and version into a case`() {
        val scenario = write(
            """
            name: my plugin loads
            platforms: [paper, purpur]
            versions: ["1.21.4", "latest"]
            memory: 3G
            plugins:
              - build/libs/thing.jar
            properties:
              level-type: minecraft:flat
            forbid:
              - "Could not load"
            timeout: 15s
            steps:
              - enabled: MyPlugin
                timeout: 45s
              - send: "mine reload"
              - expect: "reloaded"
              - wait: 2s
            """,
        )

        assertEquals("my plugin loads", scenario.name)
        assertEquals(listOf(Platform.PAPER, Platform.PURPUR), scenario.platforms)
        assertEquals(4, scenario.cases.size)
        assertEquals("paper 1.21.4", scenario.cases.first().label)
        assertEquals("3G", scenario.memory)
        assertEquals(mapOf("level-type" to "minecraft:flat"), scenario.properties)
        assertEquals(1, scenario.forbid.size)

        val enabled = scenario.steps[0] as Step.Enabled
        assertEquals(Duration.ofSeconds(45), enabled.timeout)
        assertTrue(enabled.matcher.matches("[MyPlugin] Enabling MyPlugin v1.2"))

        // The step with no timeout of its own takes the scenario's.
        assertEquals(Duration.ofSeconds(15), (scenario.steps[2] as Step.Expect).timeout)
        assertEquals(Duration.ofSeconds(2), (scenario.steps[3] as Step.Wait).duration)
    }

    @Test
    fun `a version and a platform can be written in the singular`() {
        val scenario = write(
            """
            platform: folia
            version: "1.21.4"
            steps:
              - expect: "Done ("
            """,
        )
        assertEquals(listOf(Platform.FOLIA), scenario.platforms)
        assertEquals(listOf("1.21.4"), scenario.versions)
    }

    @Test
    fun `a mistyped key is an error rather than something quietly ignored`() {
        val error = assertThrows<SetupError> {
            write(
                """
                platform: paper
                timout: 30s
                steps:
                  - expect: "Done ("
                """,
            )
        }
        assertTrue(error.message!!.contains("timout"), error.message!!)
    }

    @Test
    fun `a step that says nothing is an error, and so is one with a made-up key`() {
        assertTrue(
            assertThrows<SetupError> { write("steps:\n  - timeout: 5s\n") }
                .message!!.contains("does not say what to do"),
        )
        assertTrue(
            assertThrows<SetupError> { write("steps:\n  - expct: hello\n") }
                .message!!.contains("unknown key"),
        )
    }

    @Test
    fun `a scenario with no steps is refused`() {
        val error = assertThrows<SetupError> { write("platform: paper\n") }
        assertTrue(error.message!!.contains("no steps"), error.message!!)
    }

    @Test
    fun `a duration that is not one names the key it was written under`() {
        val error = assertThrows<SetupError> {
            write("steps:\n  - expect: hello\n    timeout: soonish\n")
        }
        assertTrue(error.message!!.contains("steps[0]"), error.message!!)
        assertTrue(error.message!!.contains("soonish"), error.message!!)
    }

    @Test
    fun `yaml that does not parse is reported on one line`() {
        val error = assertThrows<SetupError> { write("steps: [\n") }
        assertTrue(error.message!!.contains("not valid YAML"), error.message!!)
        assertEquals(1, error.message!!.lines().size, error.message!!)
    }

    @Test
    fun `a case slug survives a version name with dots and a dash`() {
        val scenario = write("platform: paper\nversion: \"1.21.4-rc1\"\nsteps:\n  - expect: x\n")
        assertEquals("paper-1.21.4-rc1", scenario.cases.single().slug)
    }

    private fun write(text: String): Scenario {
        val file = dir.resolve("scenario.yml")
        Files.writeString(file, text.trimIndent())
        return ScenarioFile.read(file)
    }
}
