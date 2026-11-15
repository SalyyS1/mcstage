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

    @Test
    fun `parses file-exists and file-contains steps and rejects traversal paths`() {
        val scenario = write(
            """
            platform: paper
            version: "1.21.4"
            steps:
              - file-exists: "plugins/Warps/config.yml"
                timeout: 5s
              - file-contains: "plugins/Warps/warps.yml"
                match: "re:spawn:\\s+world"
              - file-contains:
                  path: "plugins/Warps/data.txt"
                  match: "ready=true"
            """,
        )
        assertEquals(3, scenario.steps.size)
        val exists = scenario.steps[0] as Step.FileExists
        assertEquals("plugins/Warps/config.yml", exists.relativePath)
        assertEquals(Duration.ofSeconds(5), exists.timeout)

        val containsRegex = scenario.steps[1] as Step.FileContains
        assertEquals("plugins/Warps/warps.yml", containsRegex.relativePath)
        assertTrue(containsRegex.matcher.matches("spawn:  world"))

        val containsNested = scenario.steps[2] as Step.FileContains
        assertEquals("plugins/Warps/data.txt", containsNested.relativePath)
        assertTrue(containsNested.matcher.matches("state ready=true ok"))

        val traversal = assertThrows<SetupError> {
            write("steps:\n  - file-exists: \"../outside.txt\"\n")
        }
        assertTrue(traversal.message!!.contains("inside the server instance"), traversal.message!!)

        val absolute = assertThrows<SetupError> {
            write("steps:\n  - file-exists: \"/etc/passwd\"\n")
        }
        assertTrue(absolute.message!!.contains("relative"), absolute.message!!)
    }

    @Test
    fun `mistyped key suggests closest known key`() {
        val err = assertThrows<SetupError> {
            write("platform: paper\ntimout: 10s\nsteps:\n  - expect: x\n")
        }
        assertTrue(err.message!!.contains("Did you mean 'timeout'?"), err.message!!)
    }

    @Test
    fun `parses crash-guard, not-expect, file-not-exists, and rejects invalid regex`() {
        val scenario = write(
            """
            platform: folia
            version: "1.21.4"
            crash-guard: true
            steps:
              - not-expect: "re:WARN|ERROR"
                timeout: 750ms
              - file-not-exists: "crash-reports/latest.txt"
              - file-not-exists: "plugins/Warps/corrupt.db"
                timeout: 400ms
            """,
        )
        assertTrue(scenario.crashGuard)
        assertEquals(3, scenario.steps.size)
        val notExpect = scenario.steps[0] as Step.NotExpect
        assertEquals(Duration.ofMillis(750), notExpect.duration)
        assertTrue(notExpect.matcher.matches("[03:00:00 ERROR]: boom"))

        val immediateAbsent = scenario.steps[1] as Step.FileNotExists
        assertEquals("crash-reports/latest.txt", immediateAbsent.relativePath)
        assertEquals(Duration.ZERO, immediateAbsent.duration)

        val windowedAbsent = scenario.steps[2] as Step.FileNotExists
        assertEquals(Duration.ofMillis(400), windowedAbsent.duration)

        val badRegex = assertThrows<SetupError> {
            write("steps:\n  - expect: \"re:[unclosed\"\n")
        }
        assertTrue(badRegex.message!!.contains("invalid regular expression"), badRegex.message!!)
    }

    @Test
    fun `parses inject-lag and no-barrier-stall and enforces Folia region spacing invariants`() {
        val scenario = write(
            """
            platform: folia
            version: "26.3"
            steps:
              - inject-lag: 1500ms
              - inject-lag:
                  regions: 4
                  mode: merge-split
                  world: "minecraft:overworld"
                  spacing: 1024
                  duration: 2s
                  cadence: 200ms
                  command: "stress {region} {x} {z} {chunkX} {chunkZ}"
                  cleanup: false
              - no-barrier-stall: 400ms
                timeout: 2s
              - no-barrier-stall:
                  max-stall: 350ms
                  window: 1s
                  probe-command: "list"
                  probe-expect: "re:players online|issued server command"
            """,
        )
        assertEquals(4, scenario.steps.size)
        val shorthandLag = scenario.steps[0] as Step.InjectLag
        assertEquals(2, shorthandLag.regions)
        assertEquals(FoliaRegionTopology.Mode.DISJOINT, shorthandLag.mode)
        assertEquals(Duration.ofMillis(1500), shorthandLag.duration)

        val mergeSplitLag = scenario.steps[1] as Step.InjectLag
        assertEquals(4, mergeSplitLag.regions)
        assertEquals(FoliaRegionTopology.Mode.MERGE_SPLIT, mergeSplitLag.mode)
        assertEquals(1024, mergeSplitLag.spacingBlocks)
        assertEquals(Duration.ofSeconds(2), mergeSplitLag.duration)
        assertEquals(Duration.ofMillis(200), mergeSplitLag.cadence)
        assertEquals(listOf("stress {region} {x} {z} {chunkX} {chunkZ}"), mergeSplitLag.commands)
        assertEquals(false, mergeSplitLag.cleanup)

        val shortStall = scenario.steps[2] as Step.NoBarrierStall
        assertEquals(Duration.ofMillis(400), shortStall.maxStall)
        assertEquals(Duration.ofSeconds(2), shortStall.window)

        val probedStall = scenario.steps[3] as Step.NoBarrierStall
        assertEquals(Duration.ofMillis(350), probedStall.maxStall)
        assertEquals("list", probedStall.probeCommand)

        val tooClose = assertThrows<SetupError> {
            write(
                """
                platform: folia
                steps:
                  - inject-lag:
                      regions: 2
                      spacing: 512
                      duration: 1s
                """,
            )
        }
        assertTrue(tooClose.message!!.contains("768"), tooClose.message!!)

        val singleMergeSplit = assertThrows<SetupError> {
            write(
                """
                platform: folia
                steps:
                  - inject-lag:
                      regions: 1
                      mode: merge-split
                """,
            )
        }
        assertTrue(singleMergeSplit.message!!.contains("at least 2 regions"), singleMergeSplit.message!!)

        val orphanProbeExpect = assertThrows<SetupError> {
            write(
                """
                platform: folia
                steps:
                  - no-barrier-stall:
                      max-stall: 300ms
                      probe-expect: "pong"
                """,
            )
        }
        assertTrue(orphanProbeExpect.message!!.contains("probe-command"), orphanProbeExpect.message!!)
    }

    private fun write(text: String): Scenario {
        val file = dir.resolve("scenario.yml")
        Files.writeString(file, text.trimIndent())
        return ScenarioFile.read(file)
    }
}
