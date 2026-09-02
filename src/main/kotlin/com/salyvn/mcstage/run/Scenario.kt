package com.salyvn.mcstage.run

import com.salyvn.mcstage.core.Platform
import java.time.Duration

/** One instruction in a scenario. Four kinds, which between them cover a console session. */
sealed interface Step {

    val describe: String

    /** Wait for a line. The only step that can fail on its own. */
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
