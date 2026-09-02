package com.salyvn.mcstage.report

import com.salyvn.mcstage.core.Durations
import com.salyvn.mcstage.run.CaseResult
import com.salyvn.mcstage.run.Outcome
import com.salyvn.mcstage.run.RunReport
import com.salyvn.mcstage.run.StepResult

/**
 * Plain-text output. ASCII only: this ends up in CI logs, Windows consoles and pasted into chat, and
 * anything fancier survives none of those.
 */
class ConsoleRunReporter(private val color: Boolean, private val verbose: Boolean = false) {

    fun render(report: RunReport): String = buildString {
        appendLine("scenario: ${report.scenario}")
        appendLine()
        for (case in report.cases) {
            appendCase(case)
            appendLine()
        }
        appendLine(summaryLine(report))
    }

    fun summaryLine(report: RunReport): String {
        val parts = buildList {
            if (report.passed > 0) add("${report.passed} passed")
            if (report.failed > 0) add("${report.failed} failed")
            if (report.errored > 0) add("${report.errored} could not run")
        }
        val body = if (parts.isEmpty()) "nothing ran" else parts.joinToString(", ")
        return paint("Summary: $body in ${Durations.format(report.elapsed)}", outcomeColor(worst(report)))
    }

    private fun StringBuilder.appendCase(case: CaseResult) {
        val verdict = when (case.outcome) {
            Outcome.PASSED -> "PASS"
            Outcome.FAILED -> "FAIL"
            Outcome.ERROR -> "SKIP"
        }
        appendLine(
            paint(
                "${case.case.label.padEnd(28)} $verdict  ${Durations.format(case.elapsed)}",
                outcomeColor(case.outcome),
            ),
        )

        if (case.facts.isNotEmpty()) {
            val width = case.facts.keys.maxOf { it.length }
            for ((key, value) in case.facts) appendLine("    ${key.padEnd(width)}  $value")
        }

        for (step in case.steps) appendStep(step)

        case.failure?.let { appendLine(paint("    failure: $it", Ansi.RED)) }

        if (case.tail.isNotEmpty()) {
            appendLine("    last ${case.tail.size} lines of server output:")
            for (line in case.tail) appendLine("      $line")
        }
    }

    private fun StringBuilder.appendStep(step: StepResult) {
        val mark = if (step.ok) "ok  " else "FAIL"
        appendLine("    ${step.describe.padEnd(44)} $mark ${Durations.format(step.elapsed).padStart(7)}")
        step.detail?.takeIf { !step.ok }?.let { appendLine("      $it") }
        if (verbose) step.matched?.let { appendLine("      matched: ${it.trim()}") }
    }

    private fun worst(report: RunReport) = when {
        report.failed > 0 -> Outcome.FAILED
        report.errored > 0 -> Outcome.ERROR
        else -> Outcome.PASSED
    }

    private fun outcomeColor(outcome: Outcome) = when (outcome) {
        Outcome.PASSED -> Ansi.GREEN
        Outcome.FAILED -> Ansi.RED
        Outcome.ERROR -> Ansi.YELLOW
    }

    private fun paint(text: String, code: String) = if (color) "$code$text${Ansi.RESET}" else text

    /** Escape sequences built from the code point, so this source file stays pure ASCII. */
    private object Ansi {
        private val CSI = Char(27).toString() + "["
        val RESET = CSI + "0m"
        val RED = CSI + "31m"
        val GREEN = CSI + "32m"
        val YELLOW = CSI + "33m"
    }
}
