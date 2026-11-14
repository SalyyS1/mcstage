package com.salyvn.mcstage.report

import com.salyvn.mcstage.run.CaseResult
import com.salyvn.mcstage.run.Outcome
import com.salyvn.mcstage.run.RunReport
import java.util.Locale

/**
 * Standard JUnit XML report (`--format junit`) so GitHub Actions test summary actions, GitLab CI, Jenkins,
 * and IDE test runners render every `(platform, version)` matrix case natively.
 */
class JunitXmlRunReporter {

    fun render(report: RunReport): String = buildString {
        appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
        val totalSeconds = formatSeconds(report.elapsed.toMillis())
        appendLine(
            """<testsuites name="${escapeAttr(report.scenario)}" tests="${report.cases.size}" """ +
                """failures="${report.failed}" errors="${report.errored}" time="$totalSeconds">""",
        )
        appendLine(
            """  <testsuite name="${escapeAttr(report.scenario)}" tests="${report.cases.size}" """ +
                """failures="${report.failed}" errors="${report.errored}" """ +
                """timestamp="${escapeAttr(report.startedAt.toString())}" time="$totalSeconds">""",
        )
        for (case in report.cases) {
            renderCase(case)
        }
        appendLine("""  </testsuite>""")
        append("""</testsuites>""")
    }

    private fun StringBuilder.renderCase(case: CaseResult) {
        val className = "mcstage.${case.case.platform.id}"
        val caseName = case.case.label
        val caseSeconds = formatSeconds(case.elapsed.toMillis())
        appendLine(
            """    <testcase classname="${escapeAttr(className)}" name="${escapeAttr(caseName)}" time="$caseSeconds">""",
        )
        when (case.outcome) {
            Outcome.PASSED -> Unit
            Outcome.FAILED -> {
                val message = case.failure ?: "Scenario assertion failed"
                appendLine("""      <failure message="${escapeAttr(message)}" type="ScenarioFailure">""")
                appendLine(escapeText(detailBody(case)))
                appendLine("""      </failure>""")
            }

            Outcome.ERROR -> {
                val message = case.failure ?: "Scenario setup error"
                appendLine("""      <error message="${escapeAttr(message)}" type="SetupError">""")
                appendLine(escapeText(detailBody(case)))
                appendLine("""      </error>""")
            }
        }
        if (case.facts.isNotEmpty() || case.steps.isNotEmpty()) {
            appendLine("""      <system-out>${escapeText(summaryOut(case))}</system-out>""")
        }
        appendLine("""    </testcase>""")
    }

    private fun detailBody(case: CaseResult): String = buildString {
        case.failure?.let { appendLine("Failure: $it") }
        if (case.steps.isNotEmpty()) {
            appendLine("Steps:")
            for (step in case.steps) {
                val mark = if (step.ok) "ok" else "FAIL"
                append("  [$mark] ${step.describe} (${step.elapsed.toMillis()}ms)")
                step.detail?.let { append(" - $it") }
                appendLine()
            }
        }
        if (case.tail.isNotEmpty()) {
            appendLine("Server log tail:")
            for (line in case.tail) appendLine("  $line")
        }
    }.trimEnd()

    private fun summaryOut(case: CaseResult): String = buildString {
        for ((k, v) in case.facts) {
            appendLine("$k: $v")
        }
        for (step in case.steps) {
            val mark = if (step.ok) "ok" else "FAIL"
            appendLine("[$mark] ${step.describe} (${step.elapsed.toMillis()}ms)")
        }
    }.trimEnd()

    private fun formatSeconds(millis: Long): String =
        String.format(Locale.ROOT, "%.3f", millis / 1000.0)

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
}
