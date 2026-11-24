package com.salyvn.mcstage.run

import com.salyvn.mcstage.core.Durations
import com.salyvn.mcstage.core.Platform
import com.salyvn.mcstage.core.SetupError
import com.salyvn.mcstage.rcon.RconFaultProfile
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
        "name", "extends", "platform", "platforms", "version", "versions", "plugin", "plugins", "properties",
        "memory", "java", "jvm-args", "ready", "ready-timeout", "stop", "stop-timeout", "forbid",
        "crash-guard", "steps", "timeout", "allow-prerelease",
    )

    private val STEP_ACTIONS = setOf(
        "expect", "not-expect", "send", "wait", "enabled", "file-exists", "file-not-exists", "file-contains",
        "inject-lag", "no-barrier-stall", "assert-heap", "assert-region-recovery", "rcon-fault",
        "bot-probe", "assert-bridge", "assert-world-integrity",
    )

    private val BOT_PROBE_KEYS = setOf("max-latency", "protocol", "expect-version", "expect-motd", "port", "timeout")
    private val ASSERT_BRIDGE_KEYS = setOf("url", "token", "min-tps", "max-mspt", "max-errors", "player", "expect-gui-title", "timeout")
    private val ASSERT_WORLD_KEYS = setOf("world", "require-session-lock-cleared")

    private val ASSERT_HEAP_KEYS = setOf("max-percent", "window")
    private val ASSERT_REGION_RECOVERY_KEYS = setOf(
        "max-faults", "loop-window", "max-recovery", "window", "require-recovery",
        "fault-command", "recovery-expect", "probe-command",
    )
    private val RCON_FAULT_KEYS = setOf(
        "command", "commands", "iterations", "latency", "jitter", "drop-rate", "corrupt-rate",
        "max-retries", "retry-backoff", "attempt-timeout", "max-p95", "seed", "port", "password",
        "expect-response",
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

        // Resolve scenario inheritance: if the scenario has an `extends:` key, load the base file
        // and use it as the foundation. Keys in the child override the base, and list-valued keys
        // (plugins, jvm-args, forbid) are appended to the base list rather than replaced.
        val extendsPath = root["extends"]?.toString()
        if (extendsPath != null) {
            val basePath = (path.parent ?: path.fileSystem.getPath(".")).resolve(extendsPath)
            if (!basePath.exists()) {
                throw SetupError("${path.name}: extends: '$extendsPath' not found at $basePath.")
            }
            if (basePath.toAbsolutePath().normalize() == path.toAbsolutePath().normalize()) {
                throw SetupError("${path.name}: extends: a scenario cannot extend itself.")
            }
            val base = read(basePath)
            return parseWithBase(root, base, fallbackName = path.name.substringBeforeLast('.'), where = path.name)
        }

        return parse(root, fallbackName = path.name.substringBeforeLast('.'), where = path.name)
    }

    /**
     * Parse a scenario that extends a base.
     *
     * Inheritance rules:
     * - Scalar keys (memory, java, ready, ready-timeout, stop, stop-timeout, crash-guard, allow-prerelease):
     *   child overrides base when present, inherits base when absent.
     * - List keys (plugins, jvm-args, forbid): child entries are **appended** to the base list.
     * - `platforms`, `versions`: child overrides base when present (at least one platform/version pair
     *   must come from either the child or the base).
     * - `steps`: always taken from the child (the child owns the test assertions).
     * - `name`: child overrides base when present.
     */
    private fun parseWithBase(root: Map<*, *>, base: Scenario, fallbackName: String, where: String): Scenario {
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

        // Platforms: child overrides base entirely when present; falls back to base.
        val platforms = root["platforms"] ?: root["platform"]
        val resolvedPlatforms = if (platforms != null) {
            strings(platforms, "platforms", where).map { name ->
                try { Platform.of(name) } catch (e: IllegalArgumentException) { throw SetupError("$where: ${e.message}") }
            }
        } else {
            base.platforms
        }

        // Versions: same override logic as platforms.
        val versions = root["versions"] ?: root["version"]
        val resolvedVersions = if (versions != null) {
            strings(versions, "versions", where).ifEmpty { listOf(com.salyvn.mcstage.core.BuildIndex.LATEST) }
        } else {
            base.versions
        }

        return Scenario(
            name = root["name"]?.toString() ?: fallbackName,
            platforms = resolvedPlatforms,
            versions = resolvedVersions,
            steps = steps,
            // List keys: child entries appended to base.
            plugins = base.plugins + strings(root["plugins"] ?: root["plugin"], "plugins", where),
            properties = base.properties + properties(root["properties"], where),
            memory = root["memory"]?.toString() ?: base.memory,
            java = root["java"]?.toString() ?: base.java,
            jvmArgs = base.jvmArgs + strings(root["jvm-args"], "jvm-args", where),
            ready = if (root.containsKey("ready")) {
                parseMatcher(root["ready"]!!.toString(), "ready", where)
            } else {
                base.ready
            },
            readyTimeout = duration(root["ready-timeout"], "ready-timeout", where) ?: base.readyTimeout,
            stopCommand = root["stop"]?.toString() ?: base.stopCommand,
            stopTimeout = duration(root["stop-timeout"], "stop-timeout", where) ?: base.stopTimeout,
            forbid = base.forbid + strings(root["forbid"], "forbid", where).mapIndexed { idx, spec ->
                parseMatcher(spec, "forbid[$idx]", where)
            },
            crashGuard = if (root.containsKey("crash-guard")) booleanFlag(root["crash-guard"], "crash-guard", where) else base.crashGuard,
            allowPrerelease = if (root.containsKey("allow-prerelease")) booleanFlag(root["allow-prerelease"], "allow-prerelease", where) else base.allowPrerelease,
        )
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

                step.containsKey("assert-heap") -> parseAssertHeap(step, explicitTimeout, at)

                step.containsKey("assert-region-recovery") -> parseAssertRegionRecovery(step, explicitTimeout, at)

                step.containsKey("rcon-fault") -> parseRconFault(step, explicitTimeout, at)

                step.containsKey("bot-probe") -> parseBotProbe(step, explicitTimeout, at)

                step.containsKey("assert-bridge") -> parseAssertBridge(step, explicitTimeout, at)

                step.containsKey("assert-world-integrity") -> parseAssertWorldIntegrity(step, at)

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

    private fun parseAssertHeap(step: Map<*, *>, explicitTimeout: Duration?, at: String): Step.AssertHeap {
        val raw = step["assert-heap"]
        val rawMap = raw as? Map<*, *>
            ?: throw SetupError("$at: assert-heap must be a mapping with max-percent and window.")
        for (k in rawMap.keys) {
            if (k.toString() !in ASSERT_HEAP_KEYS) {
                val hint = closestKey(k.toString(), ASSERT_HEAP_KEYS)?.let { " Did you mean '$it'?" }.orEmpty()
                throw SetupError(
                    "$at: assert-heap has an unknown key '$k'.$hint Known keys: ${ASSERT_HEAP_KEYS.sorted().joinToString(", ")}.",
                )
            }
        }
        val maxPct = integer(rawMap["max-percent"], "assert-heap.max-percent", at)
            ?: throw SetupError("$at: assert-heap.max-percent is required (0-100).")
        if (maxPct < 0 || maxPct > 100) {
            throw SetupError("$at: assert-heap.max-percent must be between 0 and 100, got $maxPct.")
        }
        val window = explicitTimeout
            ?: duration(rawMap["window"], "assert-heap.window", at)
            ?: Duration.ofSeconds(60)
        if (window.isZero || window.isNegative) {
            throw SetupError("$at: assert-heap.window must be greater than 0ms.")
        }
        return Step.AssertHeap(maxPercent = maxPct, window = window)
    }

    private fun parseAssertRegionRecovery(
        step: Map<*, *>,
        explicitTimeout: Duration?,
        at: String,
    ): Step.AssertRegionRecovery {
        val raw = step["assert-region-recovery"]
        if (raw !is Map<*, *>) {
            val win = duration(raw, "assert-region-recovery", at)
                ?: throw SetupError("$at: assert-region-recovery needs a window duration such as 2s or a mapping.")
            if (win.isZero || win.isNegative) {
                throw SetupError("$at: assert-region-recovery window must be greater than 0ms.")
            }
            return Step.AssertRegionRecovery(window = win)
        }

        for (k in raw.keys) {
            val keyName = k.toString()
            if (keyName !in ASSERT_REGION_RECOVERY_KEYS) {
                val hint = closestKey(keyName, ASSERT_REGION_RECOVERY_KEYS)?.let { " Did you mean '$it'?" }.orEmpty()
                throw SetupError(
                    "$at: assert-region-recovery has an unknown key '$keyName'.$hint Known keys: ${
                        ASSERT_REGION_RECOVERY_KEYS.sorted().joinToString(", ")
                    }.",
                )
            }
        }

        val maxFaults = integer(raw["max-faults"], "assert-region-recovery.max-faults", at) ?: 1
        if (maxFaults < 0 || maxFaults > 100) {
            throw SetupError("$at: assert-region-recovery.max-faults must be between 0 and 100, got $maxFaults.")
        }
        val loopWindow = duration(raw["loop-window"], "assert-region-recovery.loop-window", at) ?: Duration.ofSeconds(5)
        if (loopWindow.isZero || loopWindow.isNegative) {
            throw SetupError("$at: assert-region-recovery.loop-window must be greater than 0ms.")
        }
        val maxRecovery = duration(raw["max-recovery"], "assert-region-recovery.max-recovery", at) ?: Duration.ofSeconds(2)
        if (maxRecovery.isZero || maxRecovery.isNegative) {
            throw SetupError("$at: assert-region-recovery.max-recovery must be greater than 0ms.")
        }
        val window = duration(raw["window"], "assert-region-recovery.window", at)
            ?: explicitTimeout
            ?: Duration.ofSeconds(1)
        if (window.isZero || window.isNegative) {
            throw SetupError("$at: assert-region-recovery.window must be greater than 0ms.")
        }
        val requireRecovery = if (raw.containsKey("require-recovery")) {
            booleanFlag(raw["require-recovery"], "assert-region-recovery.require-recovery", at)
        } else {
            true
        }
        val faultCmd = raw["fault-command"]?.let { text(it, "assert-region-recovery.fault-command", at).trim() }
            ?.also { if (it.isEmpty()) throw SetupError("$at: assert-region-recovery.fault-command cannot be blank.") }
        val recoveryExpect = raw["recovery-expect"]?.let {
            parseMatcher(text(it, "assert-region-recovery.recovery-expect", at), "assert-region-recovery.recovery-expect", at)
        }
        val probeCmd = raw["probe-command"]?.let { text(it, "assert-region-recovery.probe-command", at).trim() }
            ?.also { if (it.isEmpty()) throw SetupError("$at: assert-region-recovery.probe-command cannot be blank.") }

        return Step.AssertRegionRecovery(
            maxFaults = maxFaults,
            loopWindow = loopWindow,
            maxRecovery = maxRecovery,
            window = window,
            requireRecovery = requireRecovery,
            faultCommand = faultCmd,
            recoveryExpect = recoveryExpect,
            probeCommand = probeCmd,
        )
    }

    private fun parseRconFault(step: Map<*, *>, explicitTimeout: Duration?, at: String): Step.RconFault {
        val raw = step["rcon-fault"]
        if (raw !is Map<*, *>) {
            val cmd = text(raw, "rcon-fault", at).trim()
            if (cmd.isEmpty()) throw SetupError("$at: rcon-fault command cannot be blank.")
            return Step.RconFault(RconFaultProfile(commands = listOf(cmd)))
        }

        for (k in raw.keys) {
            val keyName = k.toString()
            if (keyName !in RCON_FAULT_KEYS) {
                val hint = closestKey(keyName, RCON_FAULT_KEYS)?.let { " Did you mean '$it'?" }.orEmpty()
                throw SetupError(
                    "$at: rcon-fault has an unknown key '$keyName'.$hint Known keys: ${
                        RCON_FAULT_KEYS.sorted().joinToString(", ")
                    }.",
                )
            }
        }

        val commands = strings(raw["commands"] ?: raw["command"], "rcon-fault.commands", at)
            .map { it.trim() }
            .ifEmpty { listOf("list") }
        if (commands.any { it.isEmpty() }) {
            throw SetupError("$at: rcon-fault command cannot be blank.")
        }
        val iterations = integer(raw["iterations"], "rcon-fault.iterations", at) ?: 4
        if (iterations !in 1..200) {
            throw SetupError("$at: rcon-fault.iterations must be between 1 and 200, got $iterations.")
        }
        val latency = duration(raw["latency"], "rcon-fault.latency", at) ?: Duration.ofMillis(20)
        if (latency.isNegative) {
            throw SetupError("$at: rcon-fault.latency cannot be negative.")
        }
        val jitter = duration(raw["jitter"], "rcon-fault.jitter", at) ?: Duration.ofMillis(10)
        if (jitter.isNegative) {
            throw SetupError("$at: rcon-fault.jitter cannot be negative.")
        }
        val dropRate = ratio(raw["drop-rate"], "rcon-fault.drop-rate", at) ?: 0.2
        val corruptRate = ratio(raw["corrupt-rate"], "rcon-fault.corrupt-rate", at) ?: 0.0
        val maxRetries = integer(raw["max-retries"], "rcon-fault.max-retries", at) ?: 3
        if (maxRetries !in 0..10) {
            throw SetupError("$at: rcon-fault.max-retries must be between 0 and 10, got $maxRetries.")
        }
        val retryBackoff = duration(raw["retry-backoff"], "rcon-fault.retry-backoff", at) ?: Duration.ofMillis(20)
        if (retryBackoff.isNegative) {
            throw SetupError("$at: rcon-fault.retry-backoff cannot be negative.")
        }
        val attemptTimeout = duration(raw["attempt-timeout"], "rcon-fault.attempt-timeout", at)
            ?: explicitTimeout
            ?: Duration.ofMillis(500)
        if (attemptTimeout.isZero || attemptTimeout.isNegative) {
            throw SetupError("$at: rcon-fault.attempt-timeout must be greater than 0ms.")
        }
        val maxP95 = duration(raw["max-p95"], "rcon-fault.max-p95", at) ?: Duration.ofMillis(400)
        if (maxP95.isZero || maxP95.isNegative) {
            throw SetupError("$at: rcon-fault.max-p95 must be greater than 0ms.")
        }
        val seed = longInteger(raw["seed"], "rcon-fault.seed", at) ?: 42L
        val port = integer(raw["port"], "rcon-fault.port", at)?.also {
            if (it !in 1..65535) throw SetupError("$at: rcon-fault.port must be between 1 and 65535, got $it.")
        }
        val password = raw["password"]?.let { text(it, "rcon-fault.password", at) } ?: "mcstage-rcon"
        val expectResp = raw["expect-response"]?.let {
            parseMatcher(text(it, "rcon-fault.expect-response", at), "rcon-fault.expect-response", at)
        }

        return Step.RconFault(
            RconFaultProfile(
                commands = commands,
                iterations = iterations,
                baseLatency = latency,
                jitter = jitter,
                dropRate = dropRate,
                corruptRate = corruptRate,
                maxRetries = maxRetries,
                retryBackoff = retryBackoff,
                attemptTimeout = attemptTimeout,
                maxP95Latency = maxP95,
                seed = seed,
                port = port,
                password = password,
                expectResponse = expectResp,
            ),
        )
    }

    private fun parseBotProbe(step: Map<*, *>, explicitTimeout: Duration?, at: String): Step.BotProbe {
        val raw = step["bot-probe"]
        if (raw == null || raw is Boolean) {
            return Step.BotProbe(timeout = explicitTimeout ?: Duration.ofSeconds(3))
        }
        if (raw !is Map<*, *>) {
            val dur = duration(raw, "bot-probe", at)
                ?: throw SetupError("$at: bot-probe expects a max-latency duration or configuration mapping.")
            return Step.BotProbe(maxLatency = dur, timeout = explicitTimeout ?: Duration.ofSeconds(3))
        }
        for (k in raw.keys) {
            val keyName = k.toString()
            if (keyName !in BOT_PROBE_KEYS) {
                val hint = closestKey(keyName, BOT_PROBE_KEYS)?.let { " Did you mean '$it'?" }.orEmpty()
                throw SetupError("$at: bot-probe has an unknown key '$keyName'.$hint Known keys: ${BOT_PROBE_KEYS.sorted().joinToString(", ")}.")
            }
        }
        val maxLatency = duration(raw["max-latency"], "bot-probe.max-latency", at) ?: Duration.ofMillis(500)
        if (maxLatency.isZero || maxLatency.isNegative) {
            throw SetupError("$at: bot-probe.max-latency must be greater than 0ms.")
        }
        val protocol = integer(raw["protocol"], "bot-probe.protocol", at) ?: 769
        val port = integer(raw["port"], "bot-probe.port", at)?.also {
            if (it !in 1..65535) throw SetupError("$at: bot-probe.port must be between 1 and 65535, got $it.")
        }
        val expectVersion = raw["expect-version"]?.let { parseMatcher(text(it, "bot-probe.expect-version", at), "bot-probe.expect-version", at) }
        val expectMotd = raw["expect-motd"]?.let { parseMatcher(text(it, "bot-probe.expect-motd", at), "bot-probe.expect-motd", at) }
        val timeout = duration(raw["timeout"], "bot-probe.timeout", at) ?: explicitTimeout ?: Duration.ofSeconds(3)
        return Step.BotProbe(
            maxLatency = maxLatency,
            protocolVersion = protocol,
            expectVersion = expectVersion,
            expectMotd = expectMotd,
            port = port,
            timeout = timeout,
        )
    }

    private fun parseAssertBridge(step: Map<*, *>, explicitTimeout: Duration?, at: String): Step.AssertBridge {
        val raw = step["assert-bridge"]
        if (raw !is Map<*, *>) {
            throw SetupError("$at: assert-bridge expects a mapping with optional url, token, min-tps, max-mspt, max-errors.")
        }
        for (k in raw.keys) {
            val keyName = k.toString()
            if (keyName !in ASSERT_BRIDGE_KEYS) {
                val hint = closestKey(keyName, ASSERT_BRIDGE_KEYS)?.let { " Did you mean '$it'?" }.orEmpty()
                throw SetupError("$at: assert-bridge has an unknown key '$keyName'.$hint Known keys: ${ASSERT_BRIDGE_KEYS.sorted().joinToString(", ")}.")
            }
        }
        val url = raw["url"]?.let { text(it, "assert-bridge.url", at) } ?: "http://127.0.0.1:25590"
        if (!url.startsWith("http://127.0.0.1") && !url.startsWith("http://localhost")) {
            throw SetupError("$at: assert-bridge.url must target loopback (127.0.0.1 or localhost).")
        }
        val token = raw["token"]?.toString().orEmpty()
        val minTps = raw["min-tps"]?.toString()?.toDoubleOrNull() ?: 18.0
        val maxMspt = raw["max-mspt"]?.toString()?.toDoubleOrNull() ?: 45.0
        val maxErrors = integer(raw["max-errors"], "assert-bridge.max-errors", at) ?: 0
        val player = raw["player"]?.let { text(it, "assert-bridge.player", at) }
        val expectGuiTitle = raw["expect-gui-title"]?.let {
            parseMatcher(text(it, "assert-bridge.expect-gui-title", at), "assert-bridge.expect-gui-title", at)
        }
        val timeout = duration(raw["timeout"], "assert-bridge.timeout", at) ?: explicitTimeout ?: Duration.ofSeconds(3)
        return Step.AssertBridge(
            baseUrl = url.trimEnd('/'),
            token = token,
            minTps = minTps,
            maxMspt = maxMspt,
            maxErrors = maxErrors,
            player = player,
            expectGuiTitle = expectGuiTitle,
            timeout = timeout,
        )
    }

    private fun parseAssertWorldIntegrity(step: Map<*, *>, at: String): Step.AssertWorldIntegrity {
        val raw = step["assert-world-integrity"]
        if (raw is String) {
            return Step.AssertWorldIntegrity(worldDir = raw.trim().ifEmpty { "world" })
        }
        if (raw !is Map<*, *>) {
            throw SetupError("$at: assert-world-integrity expects a world folder string or mapping (world, require-session-lock-cleared).")
        }
        for (k in raw.keys) {
            val keyName = k.toString()
            if (keyName !in ASSERT_WORLD_KEYS) {
                throw SetupError("$at: assert-world-integrity has unknown key '$keyName'. Known keys: ${ASSERT_WORLD_KEYS.sorted().joinToString(", ")}.")
            }
        }
        val worldDir = raw["world"]?.let { text(it, "assert-world-integrity.world", at) } ?: "world"
        val reqCleared = raw["require-session-lock-cleared"]?.let { booleanFlag(it, "assert-world-integrity.require-session-lock-cleared", at) } ?: false
        return Step.AssertWorldIntegrity(worldDir = worldDir, requireSessionLockCleared = reqCleared)
    }

    private fun ratio(node: Any?, what: String, where: String): Double? {
        if (node == null) return null
        val value = when (node) {
            is Number -> node.toDouble()
            is String -> node.trim().removeSuffix("%").toDoubleOrNull()?.let {
                if (node.trim().endsWith("%")) it / 100.0 else it
            } ?: throw SetupError("$where: $what must be a ratio in [0.0, 1.0], got '$node'.")
            else -> throw SetupError("$where: $what must be a ratio in [0.0, 1.0].")
        }
        if (!value.isFinite() || value < 0.0 || value > 1.0) {
            throw SetupError("$where: $what must be between 0.0 and 1.0, got $value.")
        }
        return value
    }

    private fun longInteger(node: Any?, what: String, where: String): Long? = when (node) {
        null -> null
        is Long -> node
        is Number -> node.toLong()
        is String -> node.trim().toLongOrNull()
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
