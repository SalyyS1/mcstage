package com.salyvn.mcstage.run

import com.salyvn.mcstage.core.Platform
import com.salyvn.mcstage.core.SetupError
import com.salyvn.mcstage.instance.Instance
import com.salyvn.mcstage.instance.InstanceDescriptor
import com.salyvn.mcstage.rcon.LoopbackRconBridgeServer
import com.salyvn.mcstage.rcon.RconFaultInjector
import com.salyvn.mcstage.rcon.RconFaultProfile
import com.salyvn.mcstage.rcon.RconPacket
import com.salyvn.mcstage.rcon.RconWireException
import com.salyvn.mcstage.report.SarifRunReporter
import com.salyvn.mcstage.testing.FakeServerLauncher
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

class FoliaRecoveryAndRconFaultTest {

    @TempDir
    lateinit var dir: Path

    @Test
    fun `FoliaCrashRecoveryAnalyzer tracks 16-chunk sections, verifies recovery windows, and flags crash loops`() {
        // Chunk (32, -16) maps to Folia section (2, -1) because floorDiv(32,16)=2 and floorDiv(-16,16)=-1.
        assertEquals("section(2,-1)", FoliaCrashRecoveryAnalyzer.extractRegionKey("Exception ticking region chunk (32, -16)"))

        val containedRecovery = listOf(
            LogLine(1, 100, "[12:00:01 ERROR]: Exception ticking region chunk (32, -16)"),
            LogLine(2, 350, "[12:00:01 INFO]: Recovered region section(2,-1) after entity retirement"),
        )
        val ok = FoliaCrashRecoveryAnalyzer.evaluate(
            transcript = containedRecovery,
            maxFaultsPerRegion = 1,
            loopWindow = Duration.ofSeconds(5),
            maxRecoveryTime = Duration.ofMillis(500),
            requireRecoveryOnFault = true,
        )
        assertNull(ok.violation)
        assertEquals(1, ok.totalFaults)
        assertTrue("section(2,-1)" in ok.recoveredRegions)

        // Missing recovery fails with UNRECOVERED_REGION_FAULT.
        val unrecovered = listOf(
            LogLine(1, 100, "[12:00:01 ERROR]: Exception ticking region section(4, 1)"),
            LogLine(2, 900, "[12:00:02 INFO]: Server continuing without recovery confirmation"),
        )
        val missing = FoliaCrashRecoveryAnalyzer.evaluate(
            transcript = unrecovered,
            maxFaultsPerRegion = 2,
            loopWindow = Duration.ofSeconds(5),
            maxRecoveryTime = Duration.ofMillis(400),
            requireRecoveryOnFault = true,
        )
        assertNotNull(missing.violation)
        assertEquals(FoliaCrashRecoveryAnalyzer.Kind.UNRECOVERED_REGION_FAULT, missing.violation!!.kind)

        // Three faults in the same region within loopWindow triggers CRASH_LOOP_EXCEEDED and passive MCS015.
        val crashLoop = listOf(
            LogLine(1, 100, "[12:00:01 ERROR]: Exception ticking region chunk (16, 16)"),
            LogLine(2, 220, "[12:00:01 ERROR]: Exception ticking region chunk (17, 18)"),
            LogLine(3, 340, "[12:00:01 ERROR]: Exception ticking region chunk (31, 31)"),
        )
        val loopRes = FoliaCrashRecoveryAnalyzer.evaluate(
            transcript = crashLoop,
            maxFaultsPerRegion = 2,
            loopWindow = Duration.ofSeconds(5),
            maxRecoveryTime = Duration.ofSeconds(2),
            requireRecoveryOnFault = false,
        )
        assertNotNull(loopRes.violation)
        val viol = loopRes.violation!!
        assertEquals(FoliaCrashRecoveryAnalyzer.Kind.CRASH_LOOP_EXCEEDED, viol.kind)
        assertEquals("section(1,1)", viol.regionKey)

        val findings = CrashAnalyzer.analyze(crashLoop)
        assertTrue(findings.any { it.ruleId == "MCS015" }, findings.toString())
        assertTrue(CrashAnalyzer.rule("MCS015").fatalForCrashGuard)
    }

    @Test
    fun `RconPacket binary wire codec round-trips UTF-8 payloads and rejects malformed frames`() {
        val original = RconPacket(requestId = 77, type = RconPacket.TYPE_AUTH_OR_EXEC, payload = "tps")
        val bytes = original.encode()
        // 4 (length field) + 4 (id) + 4 (type) + 3 ("tps") + 2 (null terminators) = 17 bytes.
        assertEquals(17, bytes.size)
        val decoded = RconPacket.decode(ByteArrayInputStream(bytes))
        assertEquals(original, decoded)

        // Bad length (< 10) is rejected as CORRUPT_FRAME.
        val badLen = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(6).putInt(1).array()
        val lenErr = assertThrows(RconWireException::class.java) {
            RconPacket.decode(ByteArrayInputStream(badLen))
        }
        assertEquals(RconWireException.Kind.CORRUPT_FRAME, lenErr.kind)

        // Missing null terminator is rejected as CORRUPT_FRAME.
        val badPad = bytes.copyOf().also { it[it.lastIndex] = 0x7F }
        val padErr = assertThrows(RconWireException::class.java) {
            RconPacket.decode(ByteArrayInputStream(badPad))
        }
        assertEquals(RconWireException.Kind.CORRUPT_FRAME, padErr.kind)
    }

    @Test
    fun `RconFaultInjector recovers deterministically from packet drops and frame corruption and enforces MCS016-MCS017`() {
        LoopbackRconBridgeServer(password = "secret-rcon") { cmd ->
            "There are 0 of a max of 20 players online (cmd=$cmd)"
        }.use { server ->
            server.start()

            val profile = RconFaultProfile(
                commands = listOf("list", "tps"),
                iterations = 6,
                baseLatency = Duration.ofMillis(2),
                jitter = Duration.ofMillis(3),
                dropRate = 0.30,
                corruptRate = 0.15,
                maxRetries = 6,
                retryBackoff = Duration.ofMillis(2),
                attemptTimeout = Duration.ofMillis(250),
                maxP95Latency = Duration.ofMillis(500),
                seed = 20261119L,
                password = "secret-rcon",
                expectResponse = LineMatcher.parse("players online"),
            )

            val runA = RconFaultInjector.execute(server.port, profile) { /* virtual zero-wait sleep for deterministic speed */ }
            val runB = RconFaultInjector.execute(server.port, profile) { /* identical seed replay */ }
            assertTrue(runA.ok, "Expected RCON retry recovery to succeed, got: ${runA.failureDetail}")
            assertEquals(6, runA.succeededCommands)
            assertEquals(runA.droppedPackets, runB.droppedPackets, "SplitMix64 drop schedule must be deterministic")
            assertEquals(runA.corruptedFrames, runB.corruptedFrames, "SplitMix64 corrupt schedule must be deterministic")
            assertTrue(runA.droppedPackets > 0 || runA.corruptedFrames > 0)

            // Wrong password immediately fails with MCS017 AUTH_REJECTED.
            val badAuth = RconFaultInjector.execute(
                server.port,
                profile.copy(password = "wrong-pass", dropRate = 0.0, corruptRate = 0.0),
            ) {}
            assertFalse(badAuth.ok)
            assertEquals("MCS017", badAuth.failureRuleId)
            assertTrue(badAuth.failureDetail!!.contains("authentication rejected"), badAuth.failureDetail)

            // 100% packet loss with maxRetries=1 fails with MCS017.
            val dropExhausted = RconFaultInjector.execute(
                server.port,
                profile.copy(dropRate = 1.0, maxRetries = 1),
            ) {}
            assertFalse(dropExhausted.ok)
            assertEquals("MCS017", dropExhausted.failureRuleId)

            // Latency exceeding attemptTimeout fails with MCS016.
            val timeoutExceeded = RconFaultInjector.execute(
                server.port,
                profile.copy(
                    baseLatency = Duration.ofMillis(400),
                    jitter = Duration.ZERO,
                    attemptTimeout = Duration.ofMillis(100),
                    dropRate = 0.0,
                    corruptRate = 0.0,
                    maxRetries = 1,
                ),
            ) {}
            assertFalse(timeoutExceeded.ok)
            assertEquals("MCS016", timeoutExceeded.failureRuleId)
        }
    }

    @Test
    fun `ScenarioFile parses assert-region-recovery and rcon-fault and validates keys and ranges`() {
        val yamlFile = dir.resolve("v060.yml")
        Files.writeString(
            yamlFile,
            """
            name: folia recovery and rcon resilience
            platforms: [folia]
            versions: ["26.3"]
            crash-guard: true
            steps:
              - assert-region-recovery:
                  max-faults: 1
                  loop-window: 5s
                  max-recovery: 1500ms
                  window: 1s
                  fault-command: "emit-region-fault recover"
                  recovery-expect: "re:Recovered region"
              - rcon-fault:
                  commands: ["list", "tps"]
                  iterations: 4
                  latency: 15ms
                  jitter: 10ms
                  drop-rate: 25%
                  corrupt-rate: 0.1
                  max-retries: 4
                  max-p95: 350ms
                  seed: 99
            """.trimIndent(),
        )
        val parsed = ScenarioFile.read(yamlFile)
        assertEquals(2, parsed.steps.size)
        val rec = parsed.steps[0] as Step.AssertRegionRecovery
        assertEquals(1, rec.maxFaults)
        assertEquals(Duration.ofMillis(1500), rec.maxRecovery)
        val rcon = parsed.steps[1] as Step.RconFault
        assertEquals(0.25, rcon.profile.dropRate, 1e-6)
        assertEquals(0.10, rcon.profile.corruptRate, 1e-6)
        assertEquals(99L, rcon.profile.seed)

        val badDrop = dir.resolve("bad-rcon.yml")
        Files.writeString(
            badDrop,
            """
            platform: paper
            steps:
              - rcon-fault:
                  drop-rate: 1.5
            """.trimIndent(),
        )
        val err = assertThrows(SetupError::class.java) { ScenarioFile.read(badDrop) }
        assertTrue(err.message!!.contains("drop-rate"), err.message)

        val typoKey = dir.resolve("typo-recovery.yml")
        Files.writeString(
            typoKey,
            """
            platform: folia
            steps:
              - assert-region-recovery:
                  max-fauts: 2
            """.trimIndent(),
        )
        val typoErr = assertThrows(SetupError::class.java) { ScenarioFile.read(typoKey) }
        assertTrue(typoErr.message!!.contains("max-faults"), typoErr.message)
    }

    @Test
    fun `ScenarioRunner executes assert-region-recovery and rcon-fault end-to-end against child JVM and emits SARIF MCS015-MCS017`() {
        val passing = runScenario(
            Step.AssertRegionRecovery(
                maxFaults = 1,
                loopWindow = Duration.ofSeconds(5),
                maxRecovery = Duration.ofSeconds(2),
                window = Duration.ofMillis(200),
                faultCommand = "emit-region-fault recover",
            ),
            Step.RconFault(
                RconFaultProfile(
                    commands = listOf("say rcon-ping"),
                    iterations = 3,
                    baseLatency = Duration.ofMillis(5),
                    jitter = Duration.ofMillis(5),
                    dropRate = 0.25,
                    maxRetries = 5,
                    retryBackoff = Duration.ofMillis(5),
                    attemptTimeout = Duration.ofMillis(400),
                    maxP95Latency = Duration.ofSeconds(2),
                    seed = 7L,
                    expectResponse = LineMatcher.parse("rcon-ping"),
                ),
            ),
        )
        val passCase = passing.cases.single()
        assertEquals(Outcome.PASSED, passCase.outcome, "Expected pass, got: ${passCase.failure}")
        assertTrue(passCase.steps[1].matched!!.contains("recovered section(3,-1)"), passCase.steps[1].matched)
        assertTrue(passCase.steps[2].matched!!.contains("3/3 RCON cmd(s) ok"), passCase.steps[2].matched)

        val failingLoop = runScenario(
            Step.AssertRegionRecovery(
                maxFaults = 1,
                loopWindow = Duration.ofSeconds(5),
                maxRecovery = Duration.ofSeconds(1),
                window = Duration.ofMillis(180),
                faultCommand = "emit-region-crash-loop",
            ),
        )
        val failCase = failingLoop.cases.single()
        assertEquals(Outcome.FAILED, failCase.outcome)
        assertTrue(failCase.diagnostics.any { it.ruleId == "MCS015" }, failCase.diagnostics.toString())

        val sarif = SarifRunReporter().render(failingLoop)
        assertTrue(sarif.contains("\"MCS015\""))
        assertTrue(sarif.contains("\"MCS016\""))
        assertTrue(sarif.contains("\"MCS017\""))
        assertTrue(sarif.contains("\"ruleId\": \"MCS015\""))
    }

    private fun runScenario(vararg steps: Step): RunReport {
        val inst = Instance(dir.resolve("folia-${System.nanoTime()}"))
        Files.createDirectories(inst.dir)
        Files.writeString(inst.jar, "stand-in")
        inst.acceptEula()
        inst.writeDescriptor(
            InstanceDescriptor(
                toolVersion = "test",
                platform = Platform.FOLIA.id,
                version = "26.3",
                build = "10",
                jarFileName = "folia-26.3-10.jar",
                checksum = "sha256:0",
                memory = "1G",
                eulaAccepted = true,
            ),
        )
        val scenario = Scenario(
            name = "v060-e2e",
            platforms = listOf(Platform.FOLIA),
            versions = listOf("26.3"),
            steps = steps.toList(),
            memory = "1G",
            readyTimeout = Duration.ofSeconds(20),
            stopTimeout = Duration.ofSeconds(15),
        )
        return ScenarioRunner(
            preparer = ExistingPreparer(inst),
            javaOverride = System.getProperty("java.home"),
            launcher = FakeServerLauncher.launcher(),
        ).run(scenario)
    }
}
