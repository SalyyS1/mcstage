package com.salyvn.mcstage.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.double
import com.github.ajalt.clikt.parameters.types.path
import com.salyvn.mcstage.report.ReportComparator
import java.nio.charset.StandardCharsets
import java.nio.file.Files

class CompareCommand : CliktCommand(name = "compare") {

    override fun help(context: Context) =
        "Compare two mcstage JSON reports (baseline vs current) and flag duration, outcome, or diagnostic regressions (MCS020)."

    private val baseline by argument(help = "Baseline mcstage JSON report").path(mustExist = true, canBeDir = false)
    private val current by argument(help = "Current mcstage JSON report").path(mustExist = true, canBeDir = false)

    private val maxSlowdownPct by option(
        "--max-slowdown-pct",
        help = "Maximum allowed elapsed duration increase percentage before flagging MCS020 (default: 25.0)",
    ).double().default(25.0)

    private val format by option(
        "--format",
        help = "Output format: markdown, html, json, or sarif (default: markdown)",
    ).choice("markdown", "html", "json", "sarif").default("markdown")

    private val htmlOut by option(
        "--html",
        help = "Optional path to also write a self-contained dark-mode HTML diff report",
    ).path()

    private val sarifOut by option(
        "--sarif",
        help = "Optional path to also write an OASIS SARIF v2.1.0 report",
    ).path()

    private val failOnRegression by option(
        "--fail-on-regression",
        help = "Exit with code 1 if any outcome, duration, or new-diagnostic regression is detected",
    ).flag(default = false)

    override fun run() {
        val baseText = Files.readString(baseline, StandardCharsets.UTF_8)
        val currText = Files.readString(current, StandardCharsets.UTF_8)
        val result = ReportComparator.compare(
            baselineJson = baseText,
            currentJson = currText,
            maxSlowdownPercent = maxSlowdownPct,
        )

        sarifOut?.let { path ->
            path.parent?.let { Files.createDirectories(it) }
            Files.writeString(path, ReportComparator.renderSarif(result), StandardCharsets.UTF_8)
        }

        htmlOut?.let { path ->
            path.parent?.let { Files.createDirectories(it) }
            Files.writeString(path, ReportComparator.renderHtml(result), StandardCharsets.UTF_8)
        }

        val rendered = when (format) {
            "html" -> ReportComparator.renderHtml(result)
            "json" -> ReportComparator.renderJson(result)
            "sarif" -> ReportComparator.renderSarif(result)
            else -> ReportComparator.renderMarkdown(result)
        }
        echo(rendered)

        if (failOnRegression && !result.ok) {
            throw ProgramResult(1)
        }
    }
}
