package com.salyvn.mcstage.run

import com.salyvn.mcstage.core.Durations
import com.salyvn.mcstage.core.Platform
import com.salyvn.mcstage.core.SetupError
import com.salyvn.mcstage.rcon.RconFaultProfile
import java.nio.file.Path
import java.time.Duration

/** One instruction in a scenario: console interaction, timing, or instance filesystem assertions. */
sealed interface Step {

    val describe: String

    /** Wait for a line. */
    data class Expect(val matcher: LineMatcher, val timeout: Duration) : Step {
        override val describe get() = "expect ${matcher.describe}"
    }

    /** Assert that no console line matches `matcher` over `duration` while draining server output. */
    data class NotExpect(val matcher: LineMatcher, val duration: Duration) : Step {
        override val describe get() = "expect nothing ${matcher.describe} for ${Durations.format(duration)}"
    }

    /** Type a command into the console. */
    data class Send(val command: String) : Step {
        override val describe get() = "send \"$command\""
    }

    /** Let the server run, reading its output, so that anything forbidden still gets noticed. */
    data class Wait(val duration: Duration) : Step {
        override val describe get() = "wait ${Durations.format(duration)}"
    }

    /**
     * Wait for a plugin to report itself enabled.
     *
     * Shorthand for the line Bukkit's plugin manager logs through the plugin's own logger as it enables
     * it, which is the earliest point at which the plugin's `onEnable` has been reached.
     */
    data class Enabled(val plugin: String, val timeout: Duration) : Step {
        val matcher: LineMatcher get() = LineMatcher.Pattern("Enabling ${Regex.escape(plugin)}")
        override val describe get() = "expect $plugin to enable"
    }

    /** Wait for a file relative to the server instance directory (e.g. `plugins/Warps/config.yml`) to exist. */
    data class FileExists(val relativePath: String, val timeout: Duration) : Step {
        override val describe get() = "expect file \"$relativePath\" to exist"
    }

    /** Assert that a confined relative path inside the instance directory does not exist (optionally across `duration`). */
    data class FileNotExists(val relativePath: String, val duration: Duration) : Step {
        override val describe: String
            get() = if (duration.isZero) {
                "expect file \"$relativePath\" not to exist"
            } else {
                "expect file \"$relativePath\" not to exist for ${Durations.format(duration)}"
            }
    }

    /** Wait for a file inside the server instance directory to contain a matching substring or `re:` pattern. */
    data class FileContains(
        val relativePath: String,
        val matcher: LineMatcher,
        val timeout: Duration,
    ) : Step {
        override val describe get() = "expect file \"$relativePath\" ${matcher.describe}"
    }

    /**
     * Drives Folia multi-region synthetic lag and region merge/split barrier stress across `regions`
     * anchor coordinates on `world` for `duration` at `cadence` intervals.
     */
    data class InjectLag(
        val regions: Int = 2,
        val mode: FoliaRegionTopology.Mode = FoliaRegionTopology.Mode.DISJOINT,
        val world: String = "minecraft:overworld",
        val spacingBlocks: Int = FoliaRegionTopology.DEFAULT_SPACING_BLOCKS,
        val originX: Int = 0,
        val originY: Int = 64,
        val originZ: Int = 0,
        val duration: Duration = Duration.ofSeconds(1),
        val cadence: Duration = Duration.ofMillis(250),
        val commands: List<String> = emptyList(),
        val cleanup: Boolean = true,
    ) : Step {
        override val describe: String
            get() = "inject ${mode.id} lag across $regions region(s) for ${Durations.format(duration)}"
    }

    /**
     * Asserts that no Folia region merge/split barrier stall, Moonrise chunk-task deadlock, synchronous
     * chunk load, or tick/probe latency spike exceeds `maxStall` across observed logs and `window`.
     */
    data class NoBarrierStall(
        val maxStall: Duration,
        val window: Duration,
        val probeCommand: String? = null,
        val probeExpect: LineMatcher? = null,
    ) : Step {
        override val describe: String
            get() = if (window.isZero) {
                "expect no chunk/region barrier stall > ${Durations.format(maxStall)}"
            } else {
                "expect no chunk/region barrier stall > ${Durations.format(maxStall)} for ${Durations.format(window)}"
            }
    }

    /**
     * Asserts that heap usage observed in JVM GC log output never exceeds [maxPercent] over [window].
     *
     * The assertion is evaluated by scanning every GC-related line the server emits during the window
     * and computing the peak heap-used / heap-max ratio. It fails when any observation exceeds the
     * configured threshold, letting scenario authors detect slow memory leaks without a profiler.
     *
     * JVM GC logging must be enabled for the lines to appear: Paper/Folia enable -Xlog:gc by default
     * in recent builds; for older builds, add `-verbose:gc` to [Scenario.jvmArgs].
     *
     * Example YAML:
     * ```yaml
     * steps:
     *   - assert-heap:
     *       max-percent: 80
     *       window: 30s
     * ```
     */
    data class AssertHeap(
        /** Maximum allowed heap usage, as a percentage of the configured heap maximum [0, 100]. */
        val maxPercent: Int,
        /** How long to watch GC output before passing. */
        val window: Duration,
    ) : Step {
        override val describe: String
            get() = "assert heap usage <= $maxPercent% for ${Durations.format(window)}"
    }

    /**
     * Asserts that Folia regional tick threads isolate faults without entering a regional crash-loop
     * (`> maxFaults` in the same region within `loopWindow`) and recover within `maxRecovery` (`MCS015`).
     */
    data class AssertRegionRecovery(
        val maxFaults: Int = 1,
        val loopWindow: Duration = Duration.ofSeconds(5),
        val maxRecovery: Duration = Duration.ofSeconds(2),
        val window: Duration = Duration.ofSeconds(1),
        val requireRecovery: Boolean = true,
        val faultCommand: String? = null,
        val recoveryExpect: LineMatcher? = null,
        val probeCommand: String? = null,
    ) : Step {
        override val describe: String
            get() = "assert Folia region recovery (max-faults <= $maxFaults, recovery <= ${Durations.format(maxRecovery)}) for ${Durations.format(window)}"
    }

    /**
     * Exercises Source RCON wire framing under deterministic latency, jitter, packet loss, and frame-corruption
     * fault injection while enforcing retry recovery (`MCS017`) and p95 latency budgets (`MCS016`).
     */
    data class RconFault(
        val profile: RconFaultProfile,
    ) : Step {
        override val describe: String
            get() {
                val dropPct = (profile.dropRate * 100.0).toInt()
                return "inject RCON fault (${profile.iterations} cmd(s), latency=${Durations.format(profile.baseLatency)}, drop=$dropPct%, max-p95=${Durations.format(profile.maxP95Latency)})"
            }
    }

    /**
     * Performs a lightweight pure-JVM Minecraft Java Edition SLP TCP handshake against the live test server
     * (zero Minecraft desktop client required), enforcing latency and optional version/MOTD expectations (`MCS018`).
     */
    data class BotProbe(
        val maxLatency: Duration = Duration.ofMillis(500),
        val protocolVersion: Int = 769,
        val expectVersion: LineMatcher? = null,
        val expectMotd: LineMatcher? = null,
        val port: Int? = null,
        val timeout: Duration = Duration.ofSeconds(3),
    ) : Step {
        override val describe: String
            get() = "headless bot SLP probe (max-latency <= ${Durations.format(maxLatency)})"
    }

    /**
     * Queries the optional loopback `mckit-bridge-plugin` HTTP telemetry API (`/api/v1/metrics` and `/api/v1/errors`)
     * to assert TPS/MSPT budgets and error ceilings (`MCS019`).
     */
    data class AssertBridge(
        val baseUrl: String = "http://127.0.0.1:25590",
        val token: String = "",
        val minTps: Double = 18.0,
        val maxMspt: Double = 45.0,
        val maxErrors: Int = 0,
        val player: String? = null,
        val expectGuiTitle: LineMatcher? = null,
        val timeout: Duration = Duration.ofSeconds(3),
    ) : Step {
        override val describe: String
            get() = buildString {
                append("assert bridge telemetry (tps >= $minTps, mspt <= ${maxMspt}ms, errors <= $maxErrors")
                if (!player.isNullOrBlank() && expectGuiTitle != null) {
                    append(", gui[$player] matches ${expectGuiTitle.describe}")
                }
                append(")")
            }
    }
}

/** Ensures scenario file assertions never escape the server instance directory. */
object InstancePaths {
    fun validateRelative(raw: String, where: String): String {
        val normalized = raw.trim().replace('\\', '/')
        if (normalized.isEmpty()) {
            throw SetupError("$where: file path cannot be empty.")
        }
        if (normalized.startsWith("/") || Regex("^[A-Za-z]:").containsMatchIn(normalized)) {
            throw SetupError("$where: file path '$raw' must be relative to the server instance directory.")
        }
        val segments = normalized.split('/')
        if (segments.any { it.isEmpty() || it == "." || it == ".." }) {
            throw SetupError("$where: file path '$raw' must stay inside the server instance directory.")
        }
        return normalized
    }

    fun resolveInside(instanceDir: Path, relativePath: String): Path {
        val root = instanceDir.toAbsolutePath().normalize()
        val resolved = root.resolve(relativePath).normalize()
        if (!resolved.startsWith(root) || resolved == root) {
            throw SetupError("File path '$relativePath' escapes instance directory $root.")
        }
        return resolved
    }
}

/**
 * A test to run, as read from a YAML file.
 *
 * A scenario names platforms and versions rather than one of each, because the question a plugin author
 * actually has is "does this still work on the versions I support", and answering it one command at a
 * time is how a version gets forgotten.
 */
data class Scenario(
    val name: String,
    val platforms: List<Platform>,
    val versions: List<String>,
    val steps: List<Step>,
    val plugins: List<String> = emptyList(),
    val properties: Map<String, String> = emptyMap(),
    val memory: String = "2G",
    val java: String? = null,
    val jvmArgs: List<String> = emptyList(),
    val ready: LineMatcher = LineMatcher.parse(Platform.DEFAULT_READY),
    val readyTimeout: Duration = Duration.ofSeconds(180),
    val stopCommand: String = Platform.DEFAULT_STOP,
    val stopTimeout: Duration = Duration.ofSeconds(60),
    val forbid: List<LineMatcher> = emptyList(),
    val crashGuard: Boolean = false,
    val allowPrerelease: Boolean = false,
) {

    /** Every platform and version pair this scenario asks for, in the order they were written. */
    val cases: List<Case> get() = platforms.flatMap { platform -> versions.map { Case(platform, it) } }

    data class Case(val platform: Platform, val version: String) {
        val label: String get() = "${platform.id} $version"

        /** A directory name that is the same for the same case, so its world survives between runs. */
        val slug: String get() = "${platform.id}-${version.replace(Regex("[^A-Za-z0-9._-]"), "_")}"
    }
}
