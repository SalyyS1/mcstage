package com.salyvn.mcstage.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.default
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.path
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * Generates a ready-to-commit GitHub Actions workflow YAML with Java 21, ~/.mcstage JAR
 * caching, dark-mode HTML matrix artifact upload, and optional SARIF v2.1.0 Code Scanning.
 */
class GithubActionCommand : CliktCommand(name = "github-action") {

    override fun help(context: Context) =
        "Generate a ready-to-commit .github/workflows/mcstage.yml CI workflow for real-server plugin QA."

    private val scenarioPath by argument("scenario", help = "Path to mcstage.yml (default: mcstage.yml)").default("mcstage.yml")
    private val buildTool by option("--build-tool", help = "Build tool: gradle or maven (default: gradle)").default("gradle")
    private val noSarif by option("--no-sarif", help = "Omit SARIF Code Scanning upload step").flag()
    private val output by option("-o", "--output", help = "Optional output path (prints to stdout if omitted)").path()

    override fun run() {
        val yaml = renderWorkflowYaml(
            scenarioPath = scenarioPath,
            buildTool = buildTool,
            includeSarif = !noSarif,
        )
        val out = output
        if (out != null) {
            out.parent?.let { Files.createDirectories(it) }
            Files.writeString(out, yaml, StandardCharsets.UTF_8)
            echo("Wrote GitHub Actions workflow to $out")
        } else {
            echo(yaml)
        }
    }

    companion object {
        fun renderWorkflowYaml(
            scenarioPath: String = "mcstage.yml",
            buildTool: String = "gradle",
            includeSarif: Boolean = true,
        ): String {
            val buildCmd = if (buildTool.equals("maven", ignoreCase = true)) {
                "mvn -B package"
            } else {
                "./gradlew build --no-daemon"
            }
            return buildString {
                appendLine("name: MCStage Real-Server Plugin QA")
                appendLine("on: [push, pull_request]")
                if (includeSarif) {
                    appendLine("permissions:")
                    appendLine("  contents: read")
                    appendLine("  security-events: write")
                }
                appendLine("jobs:")
                appendLine("  mcstage-matrix:")
                appendLine("    runs-on: ubuntu-latest")
                appendLine("    steps:")
                appendLine("      - uses: actions/checkout@v4")
                appendLine("      - uses: actions/setup-java@v4")
                appendLine("        with: { distribution: temurin, java-version: '21' }")
                appendLine("      - uses: actions/cache@v4")
                appendLine("        with: { path: ~/.mcstage, key: mcstage-jars-\${{ runner.os }}-\${{ hashFiles('$scenarioPath') }} }")
                appendLine("      - name: Build plugin")
                appendLine("        run: $buildCmd")
                appendLine("      - name: Run mcstage real-server matrix")
                appendLine("        run: java -jar mcstage.jar run $scenarioPath --format html --html mcstage-report.html --sarif mcstage.sarif")
                appendLine("      - uses: actions/upload-artifact@v4")
                appendLine("        if: always()")
                appendLine("        with: { name: mcstage-html-report, path: mcstage-report.html }")
                if (includeSarif) {
                    appendLine("      - uses: github/codeql-action/upload-sarif@v3")
                    appendLine("        if: always()")
                    appendLine("        with: { sarif_file: mcstage.sarif }")
                }
            }
        }
    }
}
