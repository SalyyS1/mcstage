package com.salyvn.mcstage.run

import com.salyvn.mcstage.core.Platform
import com.salyvn.mcstage.instance.Instance
import com.salyvn.mcstage.instance.InstanceDescriptor
import com.salyvn.mcstage.testing.FakeServerLauncher
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/**
 * The runner's own decisions, checked against a process that misbehaves on request: a step that is never
 * satisfied, a line the scenario forbade, a shutdown that has to be forced, and Folia/crash-guard violations.
 */
class ScenarioRunnerTest {

    @TempDir
    lateinit var dir: Path

    @Test
    fun `a scenario whose lines all appear passes`() {
        val report = run(
            scenario(
                Step.Enabled("TestPlugin", Duration.ofSeconds(20)),
                Step.Send("say hi"),
                Step.Expect(LineMatcher.parse("issued server command: /say hi"), Duration.ofSeconds(10)),
            ),
            "enable-plugin",
        )

        val case = report.cases.single()
        assertEquals(Outcome.PASSED, case.outcome, "failure was ${case.failure}")
        assertTrue(report.ok)
        assertEquals(StopOutcome.CLEAN, case.stop)
        // boot, then one result per step.
        assertEquals(4, case.steps.size)
        assertTrue(case.facts.containsKey("ready in"), case.facts.toString())
        assertTrue(case.facts["build"]!!.contains("1.21.4"), case.facts.toString())
        assertTrue(case.facts.containsKey("java"))
    }

    @Test
    fun `a line that never appears fails the case and says what it waited for`() {
        val report = run(
            scenario(Step.Expect(LineMatcher.parse("a line no server prints"), Duration.ofMillis(600))),
        )

        val case = report.cases.single()
        val msg = case.failure!!
        assertEquals(Outcome.FAILED, case.outcome)
        assertTrue(msg.contains("a line no server prints"), msg)
        assertTrue(case.tail.isNotEmpty(), "a failure should carry the end of the output")
        assertTrue(case.diagnostics.any { it.ruleId == "MCS008" })
    }

    @Test
    fun `a forbidden line fails the case even though every step was satisfied`() {
        val report = run(
            scenario(Step.Wait(Duration.ofSeconds(1))).copy(
                forbid = listOf(LineMatcher.parse("re:IllegalStateException")),
            ),
            "throw",
        )

        val case = report.cases.single()
        val msg = case.failure!!
        assertEquals(Outcome.FAILED, case.outcome)
        assertTrue(msg.contains("forbids"), msg)
        assertTrue(case.steps.all { it.ok }, "every step was fine; the run failed on the output")
        assertTrue(case.diagnostics.any { it.ruleId == "MCS009" })
        assertTrue(case.diagnostics.any { it.ruleId == "MCS004" })
    }

    @Test
    fun `a server that will not stop is reported as killed rather than as passing`() {
        val report = run(
            scenario(Step.Wait(Duration.ofMillis(200))).copy(stopTimeout = Duration.ofSeconds(1)),
            "hang-on-stop",
        )

        val case = report.cases.single()
        val msg = case.failure!!
        assertEquals(Outcome.FAILED, case.outcome)
        assertEquals(StopOutcome.KILLED, case.stop)
        assertTrue(msg.contains("killed"), msg)
        assertTrue(case.diagnostics.any { it.ruleId == "MCS010" })
    }

    @Test
    fun `an instance whose eula was never accepted is not run at all`() {
        val instance = instance(eula = false)
        val report = ScenarioRunner(
            preparer = ExistingPreparer(instance),
            javaOverride = System.getProperty("java.home"),
            launcher = FakeServerLauncher.launcher(),
        ).run(scenario(Step.Wait(Duration.ofMillis(100))))

        val case = report.cases.single()
        val msg = case.failure!!
        assertEquals(Outcome.ERROR, case.outcome)
        assertTrue(msg.contains("EULA"), msg)
        assertTrue(case.steps.isEmpty(), "nothing should have run")
        assertTrue(case.diagnostics.any { it.ruleId == "MCS011" })
    }

    @Test
    fun `a server that dies before it is ready fails with its exit code`() {
        val report = run(scenario(Step.Wait(Duration.ofSeconds(1))), "die-before-ready")

        val case = report.cases.single()
        val msg = case.failure!!
        assertEquals(Outcome.FAILED, case.outcome)
        assertTrue(msg.contains("exited with code 1"), msg)
        assertTrue(case.diagnostics.any { it.ruleId == "MCS007" })
    }

    @Test
    fun `file-exists and file-contains steps observe files written by server commands`() {
        val report = run(
            scenario(
                Step.Send("write plugins/TestPlugin/warps.yml spawn: world_the_end"),
                Step.FileExists("plugins/TestPlugin/warps.yml", Duration.ofSeconds(5)),
                Step.FileContains(
                    "plugins/TestPlugin/warps.yml",
                    LineMatcher.parse("re:spawn:\\s+world_the_end"),
                    Duration.ofSeconds(5),
                ),
            ),
        )
        val case = report.cases.single()
        assertEquals(Outcome.PASSED, case.outcome, "failure was ${case.failure}")
        assertEquals(4, case.steps.size)
        assertEquals("spawn: world_the_end", case.steps.last().matched)
    }

    @Test
    fun `file-exists step fails cleanly when expected file never appears`() {
        val report = run(
            scenario(Step.FileExists("plugins/TestPlugin/missing.yml", Duration.ofMillis(350))),
        )
        val case = report.cases.single()
        val msg = case.failure!!
        assertEquals(Outcome.FAILED, case.outcome)
        assertTrue(msg.contains("missing.yml"), msg)
    }

    @Test
    fun `not-expect and file-not-exists pass when quiet and fail when violated`() {
        val quiet = run(
            scenario(
                Step.NotExpect(LineMatcher.parse("Something worth mentioning"), Duration.ofMillis(250)),
                Step.FileNotExists("plugins/TestPlugin/absent.yml", Duration.ofMillis(150)),
            ),
        )
        assertEquals(Outcome.PASSED, quiet.cases.single().outcome)

        val noisy = run(
            scenario(
                Step.Send("say forbidden-echo"),
                Step.NotExpect(LineMatcher.parse("forbidden-echo"), Duration.ofSeconds(2)),
            ),
        )
        val noisyCase = noisy.cases.single()
        assertEquals(Outcome.FAILED, noisyCase.outcome)
        assertTrue(noisyCase.failure!!.contains("forbidden-echo"))
    }

    @Test
    fun `crash-guard catches Folia region violations and crash-reports files automatically`() {
        val report = run(
            scenario(Step.Wait(Duration.ofMillis(250))).copy(crashGuard = true),
            "folia-violation",
            "write-crash",
        )
        val case = report.cases.single()
        val msg = case.failure!!
        assertEquals(Outcome.FAILED, case.outcome)
        assertTrue(msg.contains("crash-guard triggered [MCS003]"), msg)
        val ruleIds = case.diagnostics.map { it.ruleId }.toSet()
        assertTrue("MCS003" in ruleIds, ruleIds.toString())
        assertTrue("MCS006" in ruleIds, ruleIds.toString())
        val crashDiag = case.diagnostics.first { it.ruleId == "MCS006" }
        assertTrue(crashDiag.message.contains("Exception ticking world"), crashDiag.message)
        assertEquals("crash-reports/crash-2026-11-15_03.30.00-server.txt", crashDiag.artifactPath)
    }

    private fun run(scenario: Scenario, vararg flags: String): RunReport =
        ScenarioRunner(
            preparer = ExistingPreparer(instance()),
            javaOverride = System.getProperty("java.home"),
            launcher = FakeServerLauncher.launcher(*flags),
        ).run(scenario)

    private fun scenario(vararg steps: Step) = Scenario(
        name = "test",
        platforms = listOf(Platform.PAPER),
        versions = listOf("1.21.4"),
        steps = steps.toList(),
        memory = "1G",
        readyTimeout = Duration.ofSeconds(20),
        stopTimeout = Duration.ofSeconds(15),
    )

    private fun instance(eula: Boolean = true): Instance {
        val instance = Instance(dir.resolve("paper-1.21.4"))
        Files.createDirectories(instance.dir)
        // The launcher is replaced in these tests, so the jar only has to exist.
        Files.writeString(instance.jar, "stand-in for a server jar")
        if (eula) instance.acceptEula()
        instance.writeDescriptor(
            InstanceDescriptor(
                toolVersion = "test",
                platform = Platform.PAPER.id,
                version = "1.21.4",
                build = "232",
                jarFileName = "paper-1.21.4-232.jar",
                checksum = "sha256:0",
                memory = "1G",
                eulaAccepted = eula,
            ),
        )
        return instance
    }
}
