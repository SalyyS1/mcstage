package com.salyvn.mcstage.cli

import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.path
import com.salyvn.mcstage.report.ConsoleRunReporter
import com.salyvn.mcstage.report.JsonRunReporter
import com.salyvn.mcstage.report.JunitXmlRunReporter
import com.salyvn.mcstage.run.CaseResult
import com.salyvn.mcstage.run.LogLine
import com.salyvn.mcstage.run.RunListener
import com.salyvn.mcstage.run.RunReport
import com.salyvn.mcstage.run.Scenario
import com.salyvn.mcstage.run.StepResult
import java.nio.file.Files

/**
 * Shared plumbing for the two commands that start servers: how progress is printed while they run, how
 * the report is rendered afterwards, and what the exit code becomes.
 *
 * Exit codes are the contract with CI: 0 when every case passed, 1 when a server disagreed with the
 * scenario, and 2 when nothing could be tested. A job that cannot tell "the plugin is broken" from "the
 * download failed" will eventually ignore both.
 */
abstract class ScenarioCommand(name: String) : McstageCommand(name) {

    protected val format by option("--format", help = "Output format").choice("console", "json", "junit")
        .default("console")

    protected val output by option("-o", "--output", help = "Write the report to this file").path()

    protected val tail by option("--tail", help = "Print the server's own output as it arrives").flag()

    protected val noColor by option("--no-color", help = "Disable ANSI colour").flag()

    protected val verbose by option("-v", "--verbose", help = "Show what each step matched on").flag()

    protected val javaBinary by option(
        "--java",
        help = "The java binary or JDK home to run the server with",
    )

    protected val basePort by option(
        "--port",
        help = "Port for the first case; later cases take the next ones",
    ).int().default(25565)

    protected fun listener(): RunListener = object : RunListener {
        override fun onCaseStart(case: Scenario.Case) = echo("== ${case.label}")
        override fun onEvent(message: String) = echo("   $message")
        override fun onLine(line: LogLine) {
            if (tail) echo("   | ${line.text}")
        }

        override fun onStep(result: StepResult) {
            if (!tail) echo("   ${if (result.ok) "ok  " else "FAIL"} ${result.describe}")
        }

        override fun onCaseEnd(result: CaseResult) = echo("")
    }

    protected fun emit(report: RunReport) {
        val text = when (format) {
            "json" -> JsonRunReporter().render(report)
            "junit" -> JunitXmlRunReporter().render(report)
            else -> ConsoleRunReporter(color = !noColor && output == null, verbose = verbose).render(report)
        }

        output?.let { target ->
            target.parent?.let { Files.createDirectories(it) }
            Files.writeString(target, text)
            echo("Wrote $format report to $target")
            echo(ConsoleRunReporter(color = !noColor).summaryLine(report))
        } ?: echo(text)

        if (!report.ok) throw ProgramResult(if (report.failed > 0) 1 else 2)
    }
}
