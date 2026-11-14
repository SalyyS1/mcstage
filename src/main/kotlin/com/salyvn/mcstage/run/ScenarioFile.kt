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
        "crash-guard", "steps", "timeout", "allow-prerelease",
    )

    private val STEP_ACTIONS = setOf(
        "expect", "not-expect", "send", "wait", "enabled", "file-exists", "file-not-exists", "file-contains",
    )
    private val STEP_KEYS = STEP_ACTIONS + setOf("timeout", "match")

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
                val hint = closestKey(name, TOP_LEVEL)?.let { " Did you mean '$it'?" }.orEmpty()
                throw SetupError(
                    "$where has an unknown key '$name'.$hint Known keys: ${TOP_LEVEL.sorted().joinToString(", ")}.",
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
            ready = parseMatcher(root["ready"]?.toString() ?: Platform.DEFAULT_READY, "ready", where),
            readyTimeout = duration(root["ready-timeout"], "ready-timeout", where)
                ?: Duration.ofSeconds(180),
            stopCommand = root["stop"]?.toString() ?: Platform.DEFAULT_STOP,
            stopTimeout = duration(root["stop-timeout"], "stop-timeout", where) ?: Duration.ofSeconds(60),
            forbid = strings(root["forbid"], "forbid", where).mapIndexed { idx, spec ->
                parseMatcher(spec, "forbid[$idx]", where)
            },
            crashGuard = booleanFlag(root["crash-guard"], "crash-guard", where),
            allowPrerelease = booleanFlag(root["allow-prerelease"], "allow-prerelease", where),
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
                val k = key.toString()
                if (k !in STEP_KEYS) {
                    val hint = closestKey(k, STEP_KEYS)?.let { " Did you mean '$it'?" }.orEmpty()
                    throw SetupError(
                        "$at has an unknown key '$k'.$hint A step is one of: ${
                            STEP_ACTIONS.sorted().joinToString(", ")
                        }, with an optional timeout.",
                    )
                }
            }
            val presentActions = STEP_ACTIONS.filter { step.containsKey(it) }
            if (presentActions.size > 1) {
                throw SetupError("$at combines multiple actions (${presentActions.joinToString(", ")}); write one step per action.")
            }
            if (step.containsKey("match") && !step.containsKey("file-contains")) {
                throw SetupError("$at uses 'match' without 'file-contains'.")
            }

            val explicitTimeout = duration(step["timeout"], "timeout", at)
            val timeout = explicitTimeout ?: defaultTimeout

            when {
                step.containsKey("expect") -> Step.Expect(
                    matcher = parseMatcher(text(step["expect"], "expect", at), "expect", at),
                    timeout = timeout,
                )

                step.containsKey("not-expect") -> Step.NotExpect(
                    matcher = parseMatcher(text(step["not-expect"], "not-expect", at), "not-expect", at),
                    duration = explicitTimeout ?: Duration.ofSeconds(2),
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

                step.containsKey("file-exists") -> {
                    val rel = InstancePaths.validateRelative(text(step["file-exists"], "file-exists", at), at)
                    Step.FileExists(relativePath = rel, timeout = timeout)
                }

                step.containsKey("file-not-exists") -> {
                    val rel = InstancePaths.validateRelative(text(step["file-not-exists"], "file-not-exists", at), at)
                    Step.FileNotExists(relativePath = rel, duration = explicitTimeout ?: Duration.ZERO)
                }

                step.containsKey("file-contains") -> parseFileContains(step, timeout, at)

                else -> throw SetupError(
                    "$at does not say what to do. Use one of: ${
                        STEP_ACTIONS.sorted().joinToString(", ")
                    }.",
                )
            }
        }
    }

    private fun parseFileContains(step: Map<*, *>, timeout: Duration, at: String): Step.FileContains {
        val rawTarget = step["file-contains"]
        return if (rawTarget is Map<*, *>) {
            for (k in rawTarget.keys) {
                if (k.toString() !in setOf("path", "match")) {
                    throw SetupError("$at: file-contains mapping only accepts 'path' and 'match', got '$k'.")
                }
            }
            val rel = InstancePaths.validateRelative(text(rawTarget["path"], "file-contains.path", at), at)
            val pattern = text(rawTarget["match"] ?: step["match"], "file-contains.match", at)
            Step.FileContains(rel, parseMatcher(pattern, "file-contains.match", at), timeout)
        } else {
            val rel = InstancePaths.validateRelative(text(rawTarget, "file-contains", at), at)
            val pattern = text(step["match"], "match", at)
            Step.FileContains(rel, parseMatcher(pattern, "match", at), timeout)
        }
    }

    private fun parseMatcher(spec: String, what: String, where: String): LineMatcher = try {
        LineMatcher.parse(spec)
    } catch (e: Exception) {
        throw SetupError("$where: invalid regular expression in $what: ${oneLine(e.message)}", e)
    }

    private fun booleanFlag(node: Any?, what: String, where: String): Boolean = when (node) {
        null -> false
        is Boolean -> node
        else -> throw SetupError("$where: $what must be true or false.")
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

    private fun oneLine(message: String?): String =
        message?.lineSequence()
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() && !it.all { character -> character == '^' } }
            ?.joinToString(" ")
            ?: "no detail"

    private fun closestKey(input: String, candidates: Set<String>): String? =
        candidates
            .map { it to levenshtein(input.lowercase(), it.lowercase()) }
            .filter { (_, dist) -> dist <= 2 }
            .minByOrNull { it.second }
            ?.first

    private fun levenshtein(a: String, b: String): Int {
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = dp[0]
            dp[0] = i
            for (j in 1..b.length) {
                val temp = dp[j]
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + cost)
                prev = temp
            }
        }
        return dp[b.length]
    }
}
