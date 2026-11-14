package com.salyvn.mcstage.cli

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.path
import com.salyvn.mcstage.core.JavaRuntime
import com.salyvn.mcstage.core.SetupError
import com.salyvn.mcstage.instance.Instance
import com.salyvn.mcstage.run.ScenarioFile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Validates a scenario YAML file offline without downloading or booting any servers: schema, step syntax,
 * regex patterns, instance path confinement, Java runtime availability, and (optionally) plugin globs.
 */
class ValidateCommand : McstageCommand(name = "validate") {

    override fun help(context: Context) =
        "Validate a scenario file, test matrix, Java runtime, and plugin globs without starting servers."

    private val file by argument("scenario", help = "Scenario YAML file to validate").path()

    private val checkPlugins by option(
        "--check-plugins",
        help = "Verify that every entry in plugins matches at least one built .jar on disk",
    ).flag()

    private val javaBinary by option(
        "--java",
        help = "Java binary or JDK home to validate",
    )

    private val json by option(
        "--json",
        help = "Emit machine-readable validation summary as JSON",
    ).flag()

    override fun execute() {
        val scenario = ScenarioFile.read(file)
        val java = JavaRuntime.detect(javaBinary ?: scenario.java)

        val matchedPlugins = linkedMapOf<String, List<String>>()
        if (checkPlugins) {
            for (spec in scenario.plugins) {
                val found = Instance.matchingPluginJars(spec).map { it.toString() }
                if (found.isEmpty()) {
                    throw SetupError("Plugin pattern '$spec' in ${file.fileName} did not match any .jar on disk.")
                }
                matchedPlugins[spec] = found
            }
        }

        if (json) {
            val payload = buildJsonObject {
                put("valid", true)
                put("scenario", scenario.name)
                put("file", file.toString())
                put("java", java.describe)
                put("javaBinary", java.binary.toString())
                put("memory", scenario.memory)
                put("stepsCount", scenario.steps.size)
                put("forbidCount", scenario.forbid.size)
                putJsonArray("cases") {
                    for (c in scenario.cases) add(c.label)
                }
                if (checkPlugins) {
                    putJsonArray("plugins") {
                        for ((_, jars) in matchedPlugins) {
                            for (j in jars) add(j)
                        }
                    }
                }
            }
            echo(JSON.encodeToString(JsonObject.serializer(), payload))
        } else {
            echo("Valid scenario '${scenario.name}' (${scenario.cases.size} case(s), ${scenario.steps.size} step(s))")
            echo("   cases: ${scenario.cases.joinToString(", ") { it.label }}")
            echo("   java:  ${java.describe} (${java.binary})")
            if (checkPlugins && matchedPlugins.isNotEmpty()) {
                val all = matchedPlugins.values.flatten()
                echo("   jars:  ${all.joinToString(", ")}")
            }
        }
    }

    private companion object {
        val JSON = Json { prettyPrint = true }
    }
}
