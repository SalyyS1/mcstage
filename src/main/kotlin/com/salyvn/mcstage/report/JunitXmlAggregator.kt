package com.salyvn.mcstage.report

import com.salyvn.mcstage.core.SetupError
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

/**
 * Merges multiple JUnit XML reports (from sharded `mcstage test --format junit` matrix jobs, Gradle
 * JUnit 5 runs, or Maven Surefire) into a single `<testsuites>` XML document, structured JSON summary,
 * or GitHub Actions Markdown report.
 *
 * XML parsing is hardened against XXE (`disallow-doctype-decl`, external entity resolution disabled).
 */
class JunitXmlAggregator {

    enum class CaseStatus {
        PASSED,
        FAILED,
        ERRORED,
        SKIPPED,
    }

    data class AggregatedCase(
        val suiteName: String,
        val className: String,
        val name: String,
        val timeSeconds: Double,
        val status: CaseStatus,
        val failureMessage: String? = null,
        val failureType: String? = null,
        val failureBody: String? = null,
        val systemOut: String? = null,
        val systemErr: String? = null,
        val sourceFile: String,
    )

    data class AggregatedSuite(
        val name: String,
        val timestamp: String? = null,
        val sourceFile: String,
        val cases: List<AggregatedCase>,
        val declaredTimeSeconds: Double? = null,
    ) {
        val tests: Int get() = cases.size
        val passed: Int get() = cases.count { it.status == CaseStatus.PASSED }
        val failures: Int get() = cases.count { it.status == CaseStatus.FAILED }
        val errors: Int get() = cases.count { it.status == CaseStatus.ERRORED }
        val skipped: Int get() = cases.count { it.status == CaseStatus.SKIPPED }
        val timeSeconds: Double get() = declaredTimeSeconds ?: cases.sumOf { it.timeSeconds }
    }

    data class AggregatedReport(
        val name: String,
        val filesScanned: List<String>,
        val suites: List<AggregatedSuite>,
    ) {
        val tests: Int get() = suites.sumOf { it.tests }
        val passed: Int get() = suites.sumOf { it.passed }
        val failures: Int get() = suites.sumOf { it.failures }
        val errors: Int get() = suites.sumOf { it.errors }
        val skipped: Int get() = suites.sumOf { it.skipped }
        val timeSeconds: Double get() = suites.sumOf { it.timeSeconds }
        val ok: Boolean get() = failures == 0 && errors == 0
    }

    /**
     * Resolves file paths, directories (recursive `*.xml`), and glob patterns into a sorted, deduplicated
     * list of JUnit XML files while excluding `excludePaths` (such as `-o merged.xml`).
     */
    fun discoverXmlFiles(inputs: List<String>, excludePaths: Set<Path> = emptySet()): List<Path> {
        val excluded = excludePaths.map { it.toAbsolutePath().normalize() }.toSet()
        val resolved = linkedSetOf<Path>()

        for (raw in inputs) {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) continue

            if (containsGlobMeta(trimmed)) {
                val matches = expandGlob(trimmed)
                for (p in matches) {
                    val abs = p.toAbsolutePath().normalize()
                    if (abs !in excluded && p.isRegularFile() && p.extension.equals("xml", ignoreCase = true)) {
                        resolved.add(abs)
                    }
                }
                continue
            }

            val candidate = Path.of(trimmed)
            if (!candidate.exists()) {
                throw SetupError("JUnit XML input path '$trimmed' does not exist.")
            }
            if (candidate.isDirectory()) {
                Files.walk(candidate).use { stream ->
                    stream
                        .filter { it.isRegularFile() && it.extension.equals("xml", ignoreCase = true) }
                        .map { it.toAbsolutePath().normalize() }
                        .filter { it !in excluded }
                        .sorted()
                        .forEach { resolved.add(it) }
                }
            } else if (candidate.isRegularFile()) {
                val abs = candidate.toAbsolutePath().normalize()
                if (abs !in excluded) resolved.add(abs)
            }
        }
        return resolved.toList()
    }

    fun aggregateFiles(
        files: List<Path>,
        name: String = "mcstage-aggregated",
    ): AggregatedReport {
        val suites = mutableListOf<AggregatedSuite>()
        for (file in files) {
            suites += parseFile(file)
        }
        return AggregatedReport(
            name = name,
            filesScanned = files.map { it.toString() },
            suites = suites,
        )
    }

    fun parseFile(file: Path): List<AggregatedSuite> {
        val xmlBytes = try {
            Files.readAllBytes(file)
        } catch (e: Exception) {
            throw SetupError("Could not read JUnit XML file $file: ${e.message}", e)
        }
        val doc = try {
            newSecureDocumentBuilder().parse(ByteArrayInputStream(xmlBytes))
        } catch (e: Exception) {
            throw SetupError("${file.name} is not valid XML: ${e.message?.lineSequence()?.firstOrNull() ?: "parse error"}", e)
        }

        val root = doc.documentElement
            ?: throw SetupError("${file.name} has no root XML element.")

        val suiteElements = mutableListOf<Element>()
        when (root.tagName) {
            "testsuite" -> collectSuiteElements(root, suiteElements)
            "testsuites" -> {
                for (child in childElements(root, "testsuite")) {
                    collectSuiteElements(child, suiteElements)
                }
            }

            else -> throw SetupError(
                "${file.name} is not a JUnit XML report (found <${root.tagName}>, expected <testsuites> or <testsuite>).",
            )
        }

        val sourceLabel = file.name
        return suiteElements.map { suiteEl ->
            val suiteName = suiteEl.getAttribute("name").ifBlank { file.name.substringBeforeLast('.') }
            val timestamp = suiteEl.getAttribute("timestamp").takeIf { it.isNotBlank() }
            val declaredTime = suiteEl.getAttribute("time").toDoubleOrNull()
            val cases = childElements(suiteEl, "testcase").map { caseEl ->
                parseTestCase(caseEl, suiteName, sourceLabel)
            }
            AggregatedSuite(
                name = suiteName,
                timestamp = timestamp,
                sourceFile = sourceLabel,
                cases = cases,
                declaredTimeSeconds = declaredTime,
            )
        }
    }

    fun renderXml(report: AggregatedReport): String = buildString {
        appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
        appendLine(
            """<testsuites name="${escapeAttr(report.name)}" tests="${report.tests}" """ +
                """failures="${report.failures}" errors="${report.errors}" """ +
                """skipped="${report.skipped}" time="${formatSeconds(report.timeSeconds)}">""",
        )
        for (suite in report.suites) {
            append(
                """  <testsuite name="${escapeAttr(suite.name)}" tests="${suite.tests}" """ +
                    """failures="${suite.failures}" errors="${suite.errors}" """ +
                    """skipped="${suite.skipped}" time="${formatSeconds(suite.timeSeconds)}"""",
            )
            suite.timestamp?.let { append(""" timestamp="${escapeAttr(it)}"""") }
            appendLine(">")
            for (tc in suite.cases) {
                renderTestCaseXml(tc)
            }
            appendLine("""  </testsuite>""")
        }
        append("""</testsuites>""")
    }

    fun renderJson(report: AggregatedReport): String {
        val obj = buildJsonObject {
            put("name", report.name)
            put("ok", report.ok)
            put("filesCount", report.filesScanned.size)
            put("suitesCount", report.suites.size)
            put("tests", report.tests)
            put("passed", report.passed)
            put("failures", report.failures)
            put("errors", report.errors)
            put("skipped", report.skipped)
            put("timeSeconds", formatSeconds(report.timeSeconds).toDouble())
            putJsonArray("files") {
                for (f in report.filesScanned) add(f)
            }
            putJsonArray("suites") {
                for (s in report.suites) {
                    add(
                        buildJsonObject {
                            put("name", s.name)
                            put("sourceFile", s.sourceFile)
                            put("tests", s.tests)
                            put("passed", s.passed)
                            put("failures", s.failures)
                            put("errors", s.errors)
                            put("skipped", s.skipped)
                            put("timeSeconds", formatSeconds(s.timeSeconds).toDouble())
                        },
                    )
                }
            }
            putJsonArray("unsuccessfulCases") {
                for (s in report.suites) {
                    for (c in s.cases) {
                        if (c.status == CaseStatus.FAILED || c.status == CaseStatus.ERRORED) {
                            add(
                                buildJsonObject {
                                    put("suite", s.name)
                                    put("className", c.className)
                                    put("name", c.name)
                                    put("status", c.status.name.lowercase())
                                    put("sourceFile", c.sourceFile)
                                    c.failureMessage?.let { put("message", it) }
                                },
                            )
                        }
                    }
                }
            }
        }
        return JSON.encodeToString(JsonObject.serializer(), obj)
    }

    fun renderMarkdown(report: AggregatedReport): String = buildString {
        val verdict = if (report.ok) "PASSED" else "FAILED"
        appendLine("## JUnit Matrix Summary: ${report.name} ($verdict)")
        appendLine()
        appendLine(
            "- **Total cases:** ${report.tests} (${report.passed} passed, ${report.failures} failed, " +
                "${report.errors} errors, ${report.skipped} skipped) across ${report.filesScanned.size} XML file(s) " +
                "in ${formatSeconds(report.timeSeconds)}s",
        )
        appendLine()
        if (report.suites.isNotEmpty()) {
            appendLine("| Suite | Source | Tests | Passed | Failed | Errors | Skipped | Time |")
            appendLine("| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |")
            for (s in report.suites) {
                appendLine(
                    "| ${escapeMdCell(s.name)} | `${escapeMdCell(s.sourceFile)}` | ${s.tests} | ${s.passed} | " +
                        "${s.failures} | ${s.errors} | ${s.skipped} | ${formatSeconds(s.timeSeconds)}s |",
                )
            }
        }
        val broken = report.suites.flatMap { it.cases }
            .filter { it.status == CaseStatus.FAILED || it.status == CaseStatus.ERRORED }
        if (broken.isNotEmpty()) {
            appendLine()
            appendLine("### Failures and Errors")
            appendLine()
            for (c in broken) {
                val msg = c.failureMessage?.takeIf { it.isNotBlank() } ?: c.status.name
                appendLine("- **[${c.status}]** `${c.name}` (${c.suiteName}, `${c.sourceFile}`): $msg")
            }
        }
    }.trimEnd()

    fun renderHtml(report: AggregatedReport): String {
        val verdict = if (report.ok) "PASSED" else "FAILED"
        val verdictColor = if (report.ok) "#22c55e" else "#ef4444"
        val suiteRows = report.suites.joinToString("\n") { s ->
            val ok = s.failures == 0 && s.errors == 0
            val badgeColor = if (ok) "#22c55e" else "#ef4444"
            """<tr><td><strong>${escapeAttr(s.name)}</strong></td><td><code>${escapeAttr(s.sourceFile)}</code></td><td style="text-align:right">${s.tests}</td><td style="text-align:right;color:#22c55e">${s.passed}</td><td style="text-align:right;color:${if (s.failures > 0) "#ef4444" else "#93a4c3"}">${s.failures}</td><td style="text-align:right;color:${if (s.errors > 0) "#ef4444" else "#93a4c3"}">${s.errors}</td><td style="text-align:right">${s.skipped}</td><td style="text-align:right">${formatSeconds(s.timeSeconds)}s</td><td><span style="color:$badgeColor;font-weight:700">${if (ok) "PASS" else "FAIL"}</span></td></tr>"""
        }
        val broken = report.suites.flatMap { it.cases }
            .filter { it.status == CaseStatus.FAILED || it.status == CaseStatus.ERRORED }
        val failureSection = if (broken.isEmpty()) {
            """<p style="color:#86efac;margin-top:16px">All aggregated test cases passed with zero failures or errors.</p>"""
        } else {
            val items = broken.joinToString("\n") { c ->
                val msg = c.failureMessage?.takeIf { it.isNotBlank() } ?: c.status.name
                """<li><strong style="color:#f87171">[${c.status}]</strong> <code>${escapeAttr(c.name)}</code> (${escapeAttr(c.suiteName)}, <code>${escapeAttr(c.sourceFile)}</code>): ${escapeAttr(msg)}</li>"""
            }
            """<h2 style="margin-top:24px">Failures &amp; Errors (${broken.size})</h2><ul>$items</ul>"""
        }
        return """<!doctype html>
<html lang="en"><head><meta charset="utf-8"><title>MCStage JUnit Matrix Report — ${escapeAttr(report.name)}</title>
<style>body{font-family:system-ui,sans-serif;background:#0b0f17;color:#e6edf7;margin:0;padding:28px;max-width:1100px;margin:0 auto}h1{margin:0 0 8px}code{font-family:ui-monospace,Consolas,monospace;font-size:12.5px}.pill{display:inline-block;padding:4px 12px;border-radius:999px;font-weight:700;background:rgba(255,255,255,0.06);border:1px solid $verdictColor;color:$verdictColor}.cards{display:grid;grid-template-columns:repeat(auto-fit,minmax(140px,1fr));gap:12px;margin:18px 0}.card{background:#121826;border:1px solid #26324d;border-radius:8px;padding:12px}.card small{color:#93a4c3;display:block;font-size:11px;text-transform:uppercase}.card strong{font-size:22px;font-family:ui-monospace,Consolas,monospace}table{width:100%;border-collapse:collapse;background:#121826;border:1px solid #26324d;border-radius:8px;overflow:hidden}th,td{padding:9px 12px;border-bottom:1px solid #26324d;font-size:13px;text-align:left}th{background:#172033;color:#93a4c3;font-size:11px;text-transform:uppercase}</style>
</head><body>
<h1>MCStage JUnit Matrix Report: ${escapeAttr(report.name)}</h1>
<p><span class="pill">$verdict</span> &nbsp; <span style="color:#93a4c3">${report.filesScanned.size} XML file(s) · ${report.suites.size} suite(s) · ${formatSeconds(report.timeSeconds)}s total</span></p>
<div class="cards">
<div class="card"><small>Total Cases</small><strong>${report.tests}</strong></div>
<div class="card"><small>Passed</small><strong style="color:#22c55e">${report.passed}</strong></div>
<div class="card"><small>Failed</small><strong style="color:${if (report.failures > 0) "#ef4444" else "#e6edf7"}">${report.failures}</strong></div>
<div class="card"><small>Errors</small><strong style="color:${if (report.errors > 0) "#ef4444" else "#e6edf7"}">${report.errors}</strong></div>
<div class="card"><small>Skipped</small><strong>${report.skipped}</strong></div>
</div>
<table><thead><tr><th>Suite</th><th>Source</th><th style="text-align:right">Tests</th><th style="text-align:right">Pass</th><th style="text-align:right">Fail</th><th style="text-align:right">Err</th><th style="text-align:right">Skip</th><th style="text-align:right">Time</th><th>Status</th></tr></thead><tbody>$suiteRows</tbody></table>
$failureSection
</body></html>"""
    }

    private fun StringBuilder.renderTestCaseXml(tc: AggregatedCase) {
        appendLine(
            """    <testcase classname="${escapeAttr(tc.className)}" name="${escapeAttr(tc.name)}" """ +
                """time="${formatSeconds(tc.timeSeconds)}">""",
        )
        when (tc.status) {
            CaseStatus.PASSED -> Unit
            CaseStatus.FAILED -> {
                val msg = tc.failureMessage ?: "Test assertion failed"
                val type = tc.failureType ?: "AssertionError"
                val body = tc.failureBody.orEmpty()
                if (body.isBlank()) {
                    appendLine("""      <failure message="${escapeAttr(msg)}" type="${escapeAttr(type)}"/>""")
                } else {
                    appendLine("""      <failure message="${escapeAttr(msg)}" type="${escapeAttr(type)}">${escapeText(body)}</failure>""")
                }
            }

            CaseStatus.ERRORED -> {
                val msg = tc.failureMessage ?: "Test execution error"
                val type = tc.failureType ?: "Error"
                val body = tc.failureBody.orEmpty()
                if (body.isBlank()) {
                    appendLine("""      <error message="${escapeAttr(msg)}" type="${escapeAttr(type)}"/>""")
                } else {
                    appendLine("""      <error message="${escapeAttr(msg)}" type="${escapeAttr(type)}">${escapeText(body)}</error>""")
                }
            }

            CaseStatus.SKIPPED -> {
                val msg = tc.failureMessage
                if (msg.isNullOrBlank()) {
                    appendLine("""      <skipped/>""")
                } else {
                    appendLine("""      <skipped message="${escapeAttr(msg)}"/>""")
                }
            }
        }
        tc.systemOut?.takeIf { it.isNotEmpty() }?.let {
            appendLine("""      <system-out>${escapeText(it)}</system-out>""")
        }
        tc.systemErr?.takeIf { it.isNotEmpty() }?.let {
            appendLine("""      <system-err>${escapeText(it)}</system-err>""")
        }
        appendLine("""    </testcase>""")
    }

    private fun collectSuiteElements(suiteEl: Element, out: MutableList<Element>) {
        val directCases = childElements(suiteEl, "testcase")
        val nestedSuites = childElements(suiteEl, "testsuite")
        if (directCases.isNotEmpty() || nestedSuites.isEmpty()) {
            out += suiteEl
        }
        for (nested in nestedSuites) {
            collectSuiteElements(nested, out)
        }
    }

    private fun parseTestCase(caseEl: Element, suiteName: String, sourceFile: String): AggregatedCase {
        val className = caseEl.getAttribute("classname").ifBlank { suiteName }
        val name = caseEl.getAttribute("name").ifBlank { "unnamed" }
        val time = caseEl.getAttribute("time").toDoubleOrNull() ?: 0.0

        val errorEl = childElements(caseEl, "error").firstOrNull()
        val failureEl = childElements(caseEl, "failure").firstOrNull()
        val skippedEl = childElements(caseEl, "skipped").firstOrNull()
        val sysOut = childElements(caseEl, "system-out").firstOrNull()?.textContent
        val sysErr = childElements(caseEl, "system-err").firstOrNull()?.textContent

        return when {
            errorEl != null -> AggregatedCase(
                suiteName = suiteName,
                className = className,
                name = name,
                timeSeconds = time,
                status = CaseStatus.ERRORED,
                failureMessage = errorEl.getAttribute("message").takeIf { it.isNotBlank() },
                failureType = errorEl.getAttribute("type").takeIf { it.isNotBlank() },
                failureBody = errorEl.textContent?.trim()?.takeIf { it.isNotEmpty() },
                systemOut = sysOut,
                systemErr = sysErr,
                sourceFile = sourceFile,
            )

            failureEl != null -> AggregatedCase(
                suiteName = suiteName,
                className = className,
                name = name,
                timeSeconds = time,
                status = CaseStatus.FAILED,
                failureMessage = failureEl.getAttribute("message").takeIf { it.isNotBlank() },
                failureType = failureEl.getAttribute("type").takeIf { it.isNotBlank() },
                failureBody = failureEl.textContent?.trim()?.takeIf { it.isNotEmpty() },
                systemOut = sysOut,
                systemErr = sysErr,
                sourceFile = sourceFile,
            )

            skippedEl != null -> AggregatedCase(
                suiteName = suiteName,
                className = className,
                name = name,
                timeSeconds = time,
                status = CaseStatus.SKIPPED,
                failureMessage = skippedEl.getAttribute("message").takeIf { it.isNotBlank() },
                systemOut = sysOut,
                systemErr = sysErr,
                sourceFile = sourceFile,
            )

            else -> AggregatedCase(
                suiteName = suiteName,
                className = className,
                name = name,
                timeSeconds = time,
                status = CaseStatus.PASSED,
                systemOut = sysOut,
                systemErr = sysErr,
                sourceFile = sourceFile,
            )
        }
    }

    private fun childElements(parent: Element, tagName: String): List<Element> {
        val children = parent.childNodes
        val list = mutableListOf<Element>()
        for (i in 0 until children.length) {
            val node = children.item(i)
            if (node.nodeType == Node.ELEMENT_NODE && (node as Element).tagName == tagName) {
                list += node
            }
        }
        return list
    }

    private fun newSecureDocumentBuilder() = DocumentBuilderFactory.newInstance().apply {
        setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        isXIncludeAware = false
        isExpandEntityReferences = false
    }.newDocumentBuilder()

    private fun containsGlobMeta(raw: String): Boolean =
        raw.any { it == '*' || it == '?' || it == '[' || it == '{' }

    private fun expandGlob(pattern: String): List<Path> {
        val normalized = pattern.replace('\\', '/')
        val firstMeta = normalized.indexOfFirst { it == '*' || it == '?' || it == '[' || it == '{' }
        val prefix = if (firstMeta > 0) normalized.substring(0, firstMeta).substringBeforeLast('/', "") else ""
        val baseDir = if (prefix.isEmpty()) {
            if (normalized.startsWith("/")) Path.of("/") else Path.of(".")
        } else {
            Path.of(prefix)
        }
        if (!baseDir.exists()) return emptyList()
        val absBase = baseDir.toAbsolutePath().normalize()
        val absPattern = if (Path.of(prefix.ifEmpty { "." }).isAbsolute || normalized.startsWith("/")) {
            normalized
        } else {
            Path.of("").toAbsolutePath().normalize().toString().replace('\\', '/').trimEnd('/') + "/" + normalized
        }
        val matcher = FileSystems.getDefault().getPathMatcher("glob:$absPattern")
        return Files.walk(absBase).use { stream ->
            stream
                .filter { it.isRegularFile() }
                .map { it.toAbsolutePath().normalize() }
                .filter { matcher.matches(it) }
                .sorted()
                .toList()
        }
    }

    private fun formatSeconds(seconds: Double): String =
        String.format(Locale.ROOT, "%.3f", seconds)

    private fun sanitizeXmlChars(input: String): String = buildString(input.length) {
        for (ch in input) {
            val code = ch.code
            val legal = code == 0x09 || code == 0x0A || code == 0x0D ||
                code in 0x20..0xD7FF || code in 0xE000..0xFFFD
            if (legal) append(ch)
        }
    }

    private fun escapeText(input: String): String =
        sanitizeXmlChars(input)
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")

    private fun escapeAttr(input: String): String =
        escapeText(input)
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
            .replace("\n", "&#10;")
            .replace("\r", "&#13;")

    private fun escapeMdCell(input: String): String =
        input.replace("|", "\\|").replace("\n", " ").replace("\r", "")

    /** Render a shields.io Endpoint Badge JSON summarizing aggregated JUnit XML test cases. */
    fun renderBadgeJson(report: AggregatedReport): String {
        val ok = report.ok
        val color = when {
            report.tests == 0 -> "lightgrey"
            ok -> "brightgreen"
            else -> "red"
        }
        val msg = if (ok) {
            "PASS (${report.passed}/${report.tests})"
        } else {
            "FAIL (${report.failures + report.errors}/${report.tests})"
        }
        return """{"schemaVersion":1,"label":"mcstage junit","message":"$msg","color":"$color"}"""
    }

    private companion object {
        val JSON = Json { prettyPrint = true }
    }
}
