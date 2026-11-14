package com.salyvn.mcstage.run

import com.salyvn.mcstage.core.Platform
import com.salyvn.mcstage.core.SetupError
import java.nio.file.Path
import java.time.Duration

/** One instruction in a scenario: console interaction, timing, or instance filesystem assertions. */
sealed interface Step {

    val describe: String

    /** Wait for a line. */
    data class Expect(val matcher: LineMatcher, val timeout: Duration) : Step {
        override val describe get() = "expect ${matcher.describe}"
    }

    /** Type a command into the console. */
    data class Send(val command: String) : Step {
        override val describe get() = "send \"$command\""
    }

    /** Let the server run, reading its output, so that anything forbidden still gets noticed. */
    data class Wait(val duration: Duration) : Step {
        override val describe get() = "wait ${com.salyvn.mcstage.core.Durations.format(duration)}"
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

    /** Wait for a file inside the server instance directory to contain a matching substring or `re:` pattern. */
    data class FileContains(
        val relativePath: String,
        val matcher: LineMatcher,
        val timeout: Duration,
    ) : Step {
        override val describe get() = "expect file \"$relativePath\" ${matcher.describe}"
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
