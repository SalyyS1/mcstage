package com.salyvn.mcstage.report

import com.github.ajalt.clikt.testing.test
import com.salyvn.mcstage.cli.ValidateCommand
import com.salyvn.mcstage.core.Platform
import com.salyvn.mcstage.core.PortProbe
import com.salyvn.mcstage.run.CaseResult
import com.salyvn.mcstage.run.Outcome
import com.salyvn.mcstage.run.RunReport
import com.salyvn.mcstage.run.Scenario
import com.salyvn.mcstage.run.StepResult
import com.salyvn.mcstage.run.StopOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import javax.xml.parsers.DocumentBuilderFactory

class ReportAndValidateTest {

    @TempDir
    lateinit var dir: Path

    @Test
    fun `junit xml reporter emits well-formed XML and strips illegal control characters`() {
        val report = RunReport(
            tool = "mcstage",
            toolVersion = "0.2.0",
            scenario = "warps & reload <matrix>",
            startedAt = Instant.parse("2026-11-14T06:00:00Z"),
            cases = listOf(
                CaseResult(
                    case = Scenario.Case(Platform.PAPER, "1.21.4"),
                    outcome = Outcome.PASSED,
                    elapsed = Duration.ofMillis(1234),
                    steps = listOf(StepResult("boot", ok = true, elapsed = Duration.ofMillis(900))),
                    facts = mapOf("build" to "1.21.4 build 232"),
                    stop = StopOutcome.CLEAN,
                ),
                CaseResult(
                    case = Scenario.Case(Platform.FOLIA, "1.21.4"),
                    outcome = Outcome.FAILED,
                    elapsed = Duration.ofMillis(2500),
                    steps = listOf(
                        StepResult("boot", ok = true, elapsed = Duration.ofMillis(1000)),
                        StepResult("expect contains \"ok\"", ok = false, elapsed = Duration.ofMillis(1500), detail = "timed out"),
                    ),
                    failure = "expect contains \"ok\" failed: timed out\u001B[31m",
                    tail = listOf("[Server] line with <xml> & \"quotes\""),
                    stop = StopOutcome.CLEAN,
                ),
            ),
        )

        val xml = JunitXmlRunReporter().render(report)
        assertTrue(xml.contains("<failure "), xml)
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(ByteArrayInputStream(xml.toByteArray(StandardCharsets.UTF_8)))
        assertEquals("testsuites", doc.documentElement.tagName)
        assertEquals(2, doc.getElementsByTagName("testcase").length)
        assertEquals(1, doc.getElementsByTagName("failure").length)
    }

    @Test
    fun `PortProbe skips an already bound loopback port`() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { occupied ->
            val busyPort = occupied.localPort
            val allocated = PortProbe.allocateFreePort(busyPort)
            assertNotEquals(busyPort, allocated)
            assertTrue(allocated in 1..65535)
        }
    }

    @Test
    fun `validate command checks scenario matrix and plugin globs`() {
        val pluginsOut = dir.resolve("libs")
        Files.createDirectories(pluginsOut)
        Files.writeString(pluginsOut.resolve("warps-1.0.jar"), "fake-jar")

        val scenarioFile = dir.resolve("mcstage.yml")
        Files.writeString(
            scenarioFile,
            """
            name: warps-smoke
            platforms: [paper, folia]
            versions: ["1.21.4"]
            plugins:
              - "${pluginsOut.toString().replace('\\', '/')}/*.jar"
            steps:
              - enabled: Warps
              - file-exists: "plugins/Warps/config.yml"
            """.trimIndent(),
        )

        val ok = ValidateCommand().test(
            listOf(
                scenarioFile.toString(),
                "--check-plugins",
                "--java",
                System.getProperty("java.home"),
                "--json",
            ),
        )
        assertEquals(0, ok.statusCode, ok.stderr)
        assertTrue(ok.output.contains("\"valid\": true"), ok.output)
        assertTrue(ok.output.contains("paper 1.21.4"), ok.output)
        assertTrue(ok.output.contains("folia 1.21.4"), ok.output)

        val badScenario = dir.resolve("missing-plugin.yml")
        Files.writeString(
            badScenario,
            """
            platform: paper
            plugins:
              - "${dir.resolve("nope/*.jar").toString().replace('\\', '/')}"
            steps:
              - expect: "Done ("
            """.trimIndent(),
        )
        val failed = ValidateCommand().test(
            listOf(
                badScenario.toString(),
                "--check-plugins",
                "--java",
                System.getProperty("java.home"),
            ),
        )
        assertEquals(2, failed.statusCode)
        assertTrue(failed.stderr.contains("did not match any .jar"), failed.stderr)
    }
}
