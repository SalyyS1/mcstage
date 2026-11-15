package com.salyvn.mcstage.report

import com.github.ajalt.clikt.testing.test
import com.salyvn.mcstage.cli.AggregateCommand
import com.salyvn.mcstage.core.SetupError
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory

class JunitXmlAggregatorTest {

    @TempDir
    lateinit var dir: Path

    @Test
    fun `aggregates testsuites and standalone testsuite files across directories while excluding output path`() {
        val reportsDir = dir.resolve("reports")
        val nestedDir = reportsDir.resolve("folia")
        Files.createDirectories(nestedDir)

        Files.writeString(
            reportsDir.resolve("paper.xml"),
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <testsuites name="warps-paper" tests="2" failures="0" errors="0" time="4.250">
              <testsuite name="warps-paper" tests="2" failures="0" errors="0" timestamp="2026-11-15T12:00:00Z" time="4.250">
                <testcase classname="mcstage.paper" name="paper 1.21.4" time="2.100"/>
                <testcase classname="mcstage.paper" name="paper 26.3" time="2.150"/>
              </testsuite>
            </testsuites>
            """.trimIndent(),
        )

        Files.writeString(
            nestedDir.resolve("TEST-folia.xml"),
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <testsuite name="warps-folia" tests="3" failures="1" errors="0" skipped="1" time="6.500">
              <testcase classname="mcstage.folia" name="folia 1.21.4" time="2.500"/>
              <testcase classname="mcstage.folia" name="folia 26.3" time="4.000">
                <failure message="MCS012 barrier stall" type="ScenarioFailure">Region merge barrier stalled</failure>
                <system-out>diagnostics: MCS012</system-out>
              </testcase>
              <testcase classname="mcstage.folia" name="folia experimental" time="0.000">
                <skipped message="prerelease disabled"/>
              </testcase>
            </testsuite>
            """.trimIndent(),
        )

        val mergedOut = reportsDir.resolve("merged.xml")
        val summaryMd = dir.resolve("summary.md")

        // First run writes merged.xml inside reportsDir.
        val firstRun = AggregateCommand().test(
            listOf(
                reportsDir.toString(),
                "--name",
                "full-ci-matrix",
                "-o",
                mergedOut.toString(),
                "--summary-markdown",
                summaryMd.toString(),
            ),
        )
        assertEquals(0, firstRun.statusCode, firstRun.stderr)

        // Second run scanning the same directory must exclude merged.xml rather than doubling counts.
        val secondRun = AggregateCommand().test(
            listOf(
                reportsDir.toString(),
                "--name",
                "full-ci-matrix",
                "-o",
                mergedOut.toString(),
                "--summary-markdown",
                summaryMd.toString(),
                "--fail-on-failure",
            ),
        )
        assertEquals(1, secondRun.statusCode)

        val xml = Files.readString(mergedOut)
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(ByteArrayInputStream(xml.toByteArray(StandardCharsets.UTF_8)))
        assertEquals("testsuites", doc.documentElement.tagName)
        assertEquals("5", doc.documentElement.getAttribute("tests"))
        assertEquals("1", doc.documentElement.getAttribute("failures"))
        assertEquals("0", doc.documentElement.getAttribute("errors"))
        assertEquals("1", doc.documentElement.getAttribute("skipped"))
        assertEquals("10.750", doc.documentElement.getAttribute("time"))

        val md = Files.readString(summaryMd)
        assertTrue(md.contains("## JUnit Matrix Summary: full-ci-matrix (FAILED)"), md)
        assertTrue(md.contains("| warps-paper |"), md)
        assertTrue(md.contains("| warps-folia |"), md)
        assertTrue(md.contains("MCS012 barrier stall"), md)
    }

    @Test
    fun `supports glob discovery and JSON output format`() {
        val shardDir = dir.resolve("shards")
        Files.createDirectories(shardDir)
        Files.writeString(
            shardDir.resolve("shard-1.xml"),
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <testsuite name="shard-1" tests="1" failures="0" errors="0" time="1.200">
              <testcase classname="mcstage.paper" name="paper 26.3" time="1.200"/>
            </testsuite>
            """.trimIndent(),
        )

        val globPattern = shardDir.toString().replace('\\', '/') + "/*.xml"
        val res = AggregateCommand().test(listOf(globPattern, "--format", "json", "--fail-on-failure"))
        assertEquals(0, res.statusCode, res.stderr)
        assertTrue(res.output.contains("\"ok\": true"), res.output)
        assertTrue(res.output.contains("\"tests\": 1"), res.output)
        assertTrue(res.output.contains("\"passed\": 1"), res.output)
    }

    @Test
    fun `rejects XXE doctype declarations safely and handles empty globs`() {
        val evilXml = dir.resolve("xxe.xml")
        Files.writeString(
            evilXml,
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE foo [
              <!ELEMENT foo ANY >
              <!ENTITY xxe SYSTEM "file:///etc/passwd" >
            ]>
            <testsuite name="evil">&xxe;</testsuite>
            """.trimIndent(),
        )
        val err = assertThrows(SetupError::class.java) {
            JunitXmlAggregator().parseFile(evilXml)
        }
        assertTrue(err.message!!.contains("not valid XML"), err.message!!)
        assertFalse(err.message!!.contains("root:x:"))

        val emptyGlob = dir.toString().replace('\\', '/') + "/none-*.xml"
        val failedEmpty = AggregateCommand().test(listOf(emptyGlob))
        assertEquals(2, failedEmpty.statusCode)

        val allowedEmpty = AggregateCommand().test(listOf(emptyGlob, "--allow-empty", "--format", "json"))
        assertEquals(0, allowedEmpty.statusCode, allowedEmpty.stderr)
        assertTrue(allowedEmpty.output.contains("\"tests\": 0"), allowedEmpty.output)
    }
}
