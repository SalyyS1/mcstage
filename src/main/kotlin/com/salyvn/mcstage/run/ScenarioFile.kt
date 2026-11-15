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
        "inject-lag", "no-barrier-stall",
    )
    private val STEP_KEYS = STEP_ACTIONS + setOf("timeout", "match")

    private val INJECT_LAG_KEYS = setOf(
        "regions", "mode", "world", "spacing", "origin-x", "origin-y", "origin-z",
        "duration", "cadence", "command", "commands", "cleanup",
    )
    private val NO_BARRIER_STALL_KEYS = setOf(
        "max-stall", "window", "probe-command", "probe-expect",
    )

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

                step.containsKey("inject-lag") -> parseInjectLag(step, explicitTimeout, at)

                step.containsKey("no-barrier-stall") -> parseNoBarrierStall(step, explicitTimeout, at)

                else -> throw SetupError(
                    "$at does not say what to do. Use one of: ${
                        STEP_ACTIONS.sorted().joinToString(", ")
                    }.",
                )
            }
        }
    }

    private fun parseInjectLag(step: Map<*, *>, explicitTimeout: Duration?, at: String): Step.InjectLag {
        val raw = step["inject-lag"]
        if (raw !is Map<*, *>) {
            val dur = duration(raw, "inject-lag", at)
                ?: throw SetupError("$at: inject-lag needs a duration such as 2s or a configuration mapping.")
            if (dur.isZero || dur.isNegative) {
                throw SetupError("$at: inject-lag duration must be greater than 0ms.")
            }
            return Step.InjectLag(duration = dur)
        }

        for (k in raw.keys) {
            val keyName = k.toString()
            if (keyName !in INJECT_LAG_KEYS) {
                val hint = closestKey(keyName, INJECT_LAG_KEYS)?.let { " Did you mean '$it'?" }.orEmpty()
                throw SetupError(
                    "$at: inject-lag has an unknown key '$keyName'.$hint Known keys: ${
                        INJECT_LAG_KEYS.sorted().joinToString(", ")
                    }.",
                )
            }
        }

        val regions = integer(raw["regions"], "inject-lag.regions", at) ?: 2
        if (regions !in 1..FoliaRegionTopology.MAX_REGIONS) {
            throw SetupError("$at: inject-lag.regions must be between 1 and ${FoliaRegionTopology.MAX_REGIONS}, got $regions.")
        }
        val mode = raw["mode"]?.let { FoliaRegionTopology.Mode.of(text(it, "inject-lag.mode", at), at) }
            ?: FoliaRegionTopology.Mode.DISJOINT
        if (mode == FoliaRegionTopology.Mode.MERGE_SPLIT && regions < 2) {
            throw SetupError("$at: inject-lag mode 'merge-split' requires at least 2 regions, got $regions.")
        }
        val spacing = integer(raw["spacing"], "inject-lag.spacing", at) ?: FoliaRegionTopology.DEFAULT_SPACING_BLOCKS
        if (spacing < FoliaRegionTopology.MIN_DISJOINT_SPACING_BLOCKS) {
            throw SetupError(
                "$at: inject-lag.spacing must be at least ${FoliaRegionTopology.MIN_DISJOINT_SPACING_BLOCKS} blocks " +
                    "(3 Folia 16-chunk sections) so anchor regions do not auto-merge, got $spacing.",
            )
        }
        val world = raw["world"]?.let { text(it, "inject-lag.world", at).trim() }
            ?: "minecraft:overworld"
        if (world.isEmpty()) {
            throw SetupError("$at: inject-lag.world cannot be empty.")
        }

        val originX = integer(raw["origin-x"], "inject-lag.origin-x", at) ?: 0
        val originY = integer(raw["origin-y"], "inject-lag.origin-y", at) ?: 64
        val originZ = integer(raw["origin-z"], "inject-lag.origin-z", at) ?: 0
        val dur = duration(raw["duration"], "inject-lag.duration", at) ?: explicitTimeout ?: Duration.ofSeconds(1)
        if (dur.isZero || dur.isNegative) {
            throw SetupError("$at: inject-lag.duration must be greater than 0ms.")
        }
        val cadence = duration(raw["cadence"], "inject-lag.cadence", at) ?: Duration.ofMillis(250)
        if (cadence.isZero || cadence.isNegative) {
            throw SetupError("$at: inject-lag.cadence must be greater than 0ms.")
        }
        val customCommands = strings(raw["commands"] ?: raw["command"], "inject-lag.commands", at)
            .map { it.trim() }
            .also { list ->
                if (list.any { it.isEmpty() }) {
                    throw SetupError("$at: inject-lag command template cannot be blank.")
                }
            }
        val cleanup = if (raw.containsKey("cleanup")) booleanFlag(raw["cleanup"], "inject-lag.cleanup", at) else true

        return Step.InjectLag(
            regions = regions,
            mode = mode,
            world = world,
            spacingBlocks = spacing,
            originX = originX,
            originY = originY,
            originZ = originZ,
            duration = dur,
            cadence = cadence,
            commands = customCommands,
            cleanup = cleanup,
        )
    }

    private fun parseNoBarrierStall(step: Map<*, *>, explicitTimeout: Duration?, at: String): Step.NoBarrierStall {
        val raw = step["no-barrier-stall"]
        if (raw !is Map<*, *>) {
            val maxStall = duration(raw, "no-barrier-stall", at)
                ?: throw SetupError("$at: no-barrier-stall needs a max-stall duration such as 500ms or a mapping.")
            if (maxStall.isZero || maxStall.isNegative) {
                throw SetupError("$at: no-barrier-stall max-stall must be greater than 0ms.")
            }
            val window = explicitTimeout ?: Duration.ofSeconds(1)
            if (window.isNegative) {
                throw SetupError("$at: timeout cannot be negative.")
            }
            return Step.NoBarrierStall(maxStall = maxStall, window = window)
        }

        for (k in raw.keys) {
            val keyName = k.toString()
            if (keyName !in NO_BARRIER_STALL_KEYS) {
                val hint = closestKey(keyName, NO_BARRIER_STALL_KEYS)?.let { " Did you mean '$it'?" }.orEmpty()
                throw SetupError(
                    "$at: no-barrier-stall has an unknown key '$keyName'.$hint Known keys: ${
                        NO_BARRIER_STALL_KEYS.sorted().joinToString(", ")
                    }.",
                )
            }
        }

        val maxStall = duration(raw["max-stall"], "no-barrier-stall.max-stall", at) ?: Duration.ofMillis(500)
        if (maxStall.isZero || maxStall.isNegative) {
            throw SetupError("$at: no-barrier-stall.max-stall must be greater than 0ms.")
        }
        val window = duration(raw["window"], "no-barrier-stall.window", at) ?: explicitTimeout ?: Duration.ofSeconds(1)
        if (window.isNegative) {
            throw SetupError("$at: no-barrier-stall.window cannot be negative.")
        }
        val probeCmd = raw["probe-command"]?.let { text(it, "no-barrier-stall.probe-command", at).trim() }
            ?.also { if (it.isEmpty()) throw SetupError("$at: no-barrier-stall.probe-command cannot be blank.") }
        val probeExpect = raw["probe-expect"]?.let {
            parseMatcher(text(it, "no-barrier-stall.probe-expect", at), "no-barrier-stall.probe-expect", at)
        }
        if (probeExpect != null && probeCmd == null) {
            throw SetupError("$at: no-barrier-stall.probe-expect requires 'probe-command'.")
        }

        return Step.NoBarrierStall(
            maxStall = maxStall,
            window = window,
            probeCommand = probeCmd,
            probeExpect = probeExpect,
        )
    }

    private fun integer(node: Any?, what: String, where: String): Int? = when (node) {
        null -> null
        is Int -> node
        is Number -> node.toInt()
        is String -> node.trim().toIntOrNull()
            ?: throw SetupError("$where: $what must be an integer, got '$node'.")

        else -> throw SetupError("$where: $what must be an integer.")
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
