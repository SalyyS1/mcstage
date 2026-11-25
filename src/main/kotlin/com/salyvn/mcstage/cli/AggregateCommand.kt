package com.salyvn.mcstage.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.path
import com.salyvn.mcstage.core.SetupError
import com.salyvn.mcstage.report.JunitXmlAggregator
import java.nio.file.Files

/**
 * Merges JUnit XML test reports from sharded `mcstage test` matrix runs or Gradle test tasks into one
 * consolidated `<testsuites>` XML file, JSON summary, or GitHub Actions Markdown report.
 */
class AggregateCommand : CliktCommand(name = "aggregate") {

    override fun help(context: Context) =
        "Aggregate multiple JUnit XML reports (files, directories, or globs) into a single report."

    private val inputs by argument(
        "inputs",
        help = "JUnit XML files, directories, or glob patterns to merge",
    ).multiple(required = true)

    private val name by option(
        "--name",
        help = "Name attribute on the merged <testsuites> root",
    ).default("mcstage-matrix")

    private val format by option(
        "--format",
        help = "Primary output format",
    ).choice("junit", "json", "markdown", "html").default("junit")

    private val output by option(
        "-o",
        "--output",
        help = "Write the merged primary report to this file",
    ).path()

    private val htmlOutput by option(
        "--html",
        help = "Write a self-contained dark-mode HTML JUnit matrix report to this path",
    ).path()

    private val summaryMarkdown by option(
        "--summary-markdown",
        help = "Write a GitHub Flavored Markdown summary table to this path (for example \$GITHUB_STEP_SUMMARY)",
    ).path()

    private val failOnFailure by option(
        "--fail-on-failure",
        help = "Exit with code 1 when any aggregated test case failed or errored",
    ).flag()

    private val allowEmpty by option(
        "--allow-empty",
        help = "Succeed with an empty report instead of exiting 2 when no XML files match",
    ).flag()

    override fun run() {
        try {
            val aggregator = JunitXmlAggregator()
            val excluded = setOfNotNull(output, summaryMarkdown, htmlOutput)
            val files = aggregator.discoverXmlFiles(inputs, excludePaths = excluded)
            if (files.isEmpty() && !allowEmpty) {
                throw SetupError("No JUnit XML files matched inputs: ${inputs.joinToString(", ")}.")
            }

            val report = aggregator.aggregateFiles(files, name = name)
            val primaryText = when (format) {
                "json" -> aggregator.renderJson(report)
                "markdown" -> aggregator.renderMarkdown(report)
                "html" -> aggregator.renderHtml(report)
                else -> aggregator.renderXml(report)
            }

            htmlOutput?.let { htmlPath ->
                htmlPath.parent?.let { Files.createDirectories(it) }
                val html = if (format == "html") primaryText else aggregator.renderHtml(report)
                Files.writeString(htmlPath, html)
            }

            summaryMarkdown?.let { mdPath ->
                mdPath.parent?.let { Files.createDirectories(it) }
                val md = if (format == "markdown") primaryText else aggregator.renderMarkdown(report)
                Files.writeString(mdPath, md + "\n")
            }

            output?.let { target ->
                target.parent?.let { Files.createDirectories(it) }
                Files.writeString(target, primaryText)
                echo(
                    "Aggregated ${report.tests} test case(s) across ${files.size} file(s) to $target " +
                        "(${report.passed} passed, ${report.failures} failed, ${report.errors} errors, ${report.skipped} skipped)",
                )
            } ?: echo(primaryText)

            if (failOnFailure && !report.ok) {
                throw ProgramResult(1)
            }
        } catch (e: SetupError) {
            echo(e.message ?: "Could not aggregate JUnit XML reports.", err = true)
            throw ProgramResult(2)
        }
    }
}
