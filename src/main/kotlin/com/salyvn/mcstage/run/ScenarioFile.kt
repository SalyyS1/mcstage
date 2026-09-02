package com.salyvn.mcstage.run

import com.salyvn.mcstage.core.Durations
import com.salyvn.mcstage.core.Platform
import com.salyvn.mcstage.core.SetupError
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.exists
import kotlin.io.path.name

/**
 * Reads a scenario file.
 *
 * A scenario is data, so it is parsed with SnakeYAML's SafeConstructor: no tags are resolved and no
 * objects are instantiated from the document. An unknown key is an error rather than something quietly
 * ignored, because a mistyped `timout:` that silently does nothing is worse than one that stops the run.
 */
object ScenarioFile {

    private val TOP_LEVEL = setOf(
        "name", "platform", "platforms", "version", "versions", "plugin", "plugins", "properties",
        "memory", "java", "jvm-args", "ready", "ready-timeout", "stop", "stop-timeout", "forbid",
        "steps", "timeout", "allow-prerelease",
    )

    private val STEP_KEYS = setOf("expect", "send", "wait", "enabled", "timeout")

    fun read(path: Path): Scenario {
        if (!path.exists()) throw SetupError("There is no scenario file at $path.")
        val text = Files.readString(path)
        val document = try {
            Yaml(SafeConstructor(LoaderOptions())).load<Any?>(text)
        } catch (e: Exception) {
            throw SetupError("${path.name} is not valid YAML: ${oneLine(e.message)}", e)
        }
        val root = document as? Map<*, *>
            ?: throw SetupError("${path.name} must be a YAML mapping of keys, not a ${kind(document)}.")

        return parse(root, fallbackName = path.name.substringBeforeLast('.'), where = path.name)
    }

    fun parse(root: Map<*, *>, fallbackName: String, where: String): Scenario {
        for (key in root.keys) {
            val name = key.toString()
            if (name !in TOP_LEVEL) {
                throw SetupError(
                    "$where has an unknown key '$name'. Known keys: ${TOP_LEVEL.sorted().joinToString(", ")}.",
                )
            }
        }

        val stepTimeout = duration(root["timeout"], "timeout", where) ?: Duration.ofSeconds(60)
        val steps = steps(root["steps"], stepTimeout, where)
        if (steps.isEmpty()) {
            throw SetupError("$where has no steps, so there is nothing to check. Add at least one expect.")
        }

        return Scenario(
            name = root["name"]?.toString() ?: fallbackName,
            platforms = platforms(root, where),
            versions = strings(root["versions"] ?: root["version"], "versions", where)
                .ifEmpty { listOf(com.salyvn.mcstage.core.BuildIndex.LATEST) },
            steps = steps,
            plugins = strings(root["plugins"] ?: root["plugin"], "plugins", where),
            properties = properties(root["properties"], where),
            memory = root["memory"]?.toString() ?: "2G",
            java = root["java"]?.toString(),
            jvmArgs = strings(root["jvm-args"], "jvm-args", where),
            ready = LineMatcher.parse(root["ready"]?.toString() ?: Platform.DEFAULT_READY),
            readyTimeout = duration(root["ready-timeout"], "ready-timeout", where)
                ?: Duration.ofSeconds(180),
            stopCommand = root["stop"]?.toString() ?: Platform.DEFAULT_STOP,
            stopTimeout = duration(root["stop-timeout"], "stop-timeout", where) ?: Duration.ofSeconds(60),
            forbid = strings(root["forbid"], "forbid", where).map { LineMatcher.parse(it) },
            allowPrerelease = root["allow-prerelease"] == true,
        )
    }

    private fun platforms(root: Map<*, *>, where: String): List<Platform> {
        val names = strings(root["platforms"] ?: root["platform"], "platforms", where)
        if (names.isEmpty()) return listOf(Platform.PAPER)
        return names.map {
            try {
                Platform.of(it)
            } catch (e: IllegalArgumentException) {
                throw SetupError("$where: ${e.message}")
            }
        }
    }

    private fun steps(node: Any?, defaultTimeout: Duration, where: String): List<Step> {
        if (node == null) return emptyList()
        val list = node as? List<*> ?: throw SetupError("$where: steps must be a list.")
        return list.mapIndexed { index, raw ->
            val at = "$where: steps[$index]"
            val step = raw as? Map<*, *>
                ?: throw SetupError("$at must be a mapping such as `- expect: \"Done (\"`.")
            for (key in step.keys) {
                if (key.toString() !in STEP_KEYS) {
                    throw SetupError(
                        "$at has an unknown key '$key'. A step is one of: ${
                            STEP_KEYS.minus("timeout").sorted().joinToString(", ")
                        }, with an optional timeout.",
                    )
                }
            }
            val timeout = duration(step["timeout"], "timeout", at) ?: defaultTimeout

            when {
                step.containsKey("expect") -> Step.Expect(
                    matcher = LineMatcher.parse(text(step["expect"], "expect", at)),
                    timeout = timeout,
                )

                step.containsKey("send") -> Step.Send(text(step["send"], "send", at))

                step.containsKey("enabled") -> Step.Enabled(
                    plugin = text(step["enabled"], "enabled", at),
                    timeout = timeout,
                )

                step.containsKey("wait") -> Step.Wait(
                    duration(step["wait"], "wait", at)
                        ?: throw SetupError("$at: wait needs a duration such as 5s."),
                )

                else -> throw SetupError(
                    "$at does not say what to do. Use one of: ${
                        STEP_KEYS.minus("timeout").sorted().joinToString(", ")
                    }.",
                )
            }
        }
    }

    private fun properties(node: Any?, where: String): Map<String, String> {
        if (node == null) return emptyMap()
        val map = node as? Map<*, *> ?: throw SetupError("$where: properties must be a mapping.")
        return map.entries.associate { (key, value) -> key.toString() to value?.toString().orEmpty() }
    }

    private fun strings(node: Any?, what: String, where: String): List<String> = when (node) {
        null -> emptyList()
        is String -> listOf(node)
        is Number, is Boolean -> listOf(node.toString())
        is List<*> -> node.map {
            it?.toString() ?: throw SetupError("$where: $what contains an empty entry.")
        }

        else -> throw SetupError("$where: $what must be a string or a list of strings.")
    }

    private fun text(node: Any?, what: String, where: String): String = when (node) {
        null -> throw SetupError("$where: $what needs a value.")
        is String -> node
        is Number, is Boolean -> node.toString()
        else -> throw SetupError("$where: $what must be a string.")
    }

    private fun duration(node: Any?, what: String, where: String): Duration? = when (node) {
        null -> null
        is Number -> Duration.ofMillis((node.toDouble() * 1000).toLong())
        is String -> try {
            Durations.parse(node, what)
        } catch (e: SetupError) {
            throw SetupError("$where: ${e.message}")
        }

        else -> throw SetupError("$where: $what must be a duration such as 30s.")
    }

    private fun kind(value: Any?) = when (value) {
        null -> "empty document"
        is List<*> -> "list"
        else -> value::class.simpleName ?: "value"
    }

    /** SnakeYAML reports a position over several lines; a finding reads better on one. */
    private fun oneLine(message: String?): String =
        message?.lineSequence()
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() && !it.all { character -> character == '^' } }
            ?.joinToString(" ")
            ?: "no detail"
}
