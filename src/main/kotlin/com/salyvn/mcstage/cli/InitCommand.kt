package com.salyvn.mcstage.cli

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.default
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.path
import com.salyvn.mcstage.core.SetupError
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists

/**
 * Writes a ready-to-run starter scenario file using one of four battle-tested presets:
 * `basic`, `folia-stress`, `rpg-bot-bridge`, or `ci-matrix`.
 */
class InitCommand : McstageCommand(name = "init") {

    override fun help(context: Context) =
        "Write an example scenario file (--preset basic|folia-stress|rpg-bot-bridge|ci-matrix)."

    private val file by argument("file", help = "Where to write it").path().default(Path.of("mcstage.yml"))

    private val preset by option(
        "--preset",
        help = "Starter template preset: basic, folia-stress, rpg-bot-bridge, or ci-matrix",
    ).choice("basic", "folia-stress", "rpg-bot-bridge", "ci-matrix").default("basic")

    private val force by option("--force", help = "Overwrite the file if it is already there").flag()

    override fun execute() {
        if (file.exists() && !force) {
            throw SetupError("$file already exists. Pass --force to overwrite it.")
        }
        file.parent?.let { Files.createDirectories(it) }
        Files.writeString(file, templateFor(preset))
        echo("Wrote $file (preset=$preset)")
        echo("Run it with: mcstage test $file --accept-eula")
    }

    internal companion object {
        fun templateFor(preset: String): String = when (preset) {
            "folia-stress" -> FOLIA_STRESS_TEMPLATE
            "rpg-bot-bridge" -> RPG_BOT_BRIDGE_TEMPLATE
            "ci-matrix" -> CI_MATRIX_TEMPLATE
            else -> BASIC_TEMPLATE
        }

        val BASIC_TEMPLATE = """
            # A scenario is a boot, a few console commands, and the lines that must show up.
            # Run it with: mcstage test mcstage.yml --accept-eula
            name: my plugin loads and answers

            # Every platform is tried against every version, so this is four servers.
            platforms: [paper, purpur]
            versions: ["latest", "1.21.4"]

            memory: 2G

            # A jar, a directory of jars, or a glob. The last build is usually what you want to test.
            plugins:
              - build/libs/*.jar

            # Extra server.properties entries, if the test needs them.
            properties:
              level-type: minecraft:flat

            # Checked against every line, for the whole run.
            forbid:
              - "Could not load 'plugins"
              - re:Exception in thread "Server thread"

            timeout: 30s

            steps:
              # Waits for the line the plugin manager logs as it enables your plugin.
              - enabled: MyPlugin
                timeout: 60s

              - send: "myplugin reload"
              - expect: "reload complete"

              # Let it tick for a moment, so anything forbidden has a chance to appear.
              - wait: 5s

        """.trimIndent()

        val FOLIA_STRESS_TEMPLATE = """
            # Folia multi-region stress, barrier-stall budget & regional crash-loop recovery gate.
            name: folia region stress and recovery gate
            platforms: [paper, folia]
            versions: ["latest"]
            memory: 2G
            crash-guard: true
            plugins:
              - build/libs/*.jar
            forbid:
              - "Could not load 'plugins"
              - re:Fatal exception ticking region
            timeout: 30s
            steps:
              - enabled: MyPlugin
                timeout: 60s
              - inject-lag:
                  regions: 3
                  mode: merge-split
                  spacing: 1024
                  duration: 2s
                  cadence: 250ms
              - no-barrier-stall:
                  max-stall: 400ms
                  probe-command: "list"
                timeout: 3s
              - assert-region-recovery:
                  max-faults: 1
                  loop-window: 5s
                  max-recovery: 1500ms
                  window: 2s
              - assert-heap:
                  max-percent: 85
        """.trimIndent()

        val RPG_BOT_BRIDGE_TEMPLATE = """
            # RPG server scenario combining headless SLP bot-probe (MCS018), companion mckit-bridge-plugin (MCS019)
            # and offline Anvil world integrity verification (MCS021).
            name: rpg server bot-probe and bridge telemetry gate
            platforms: [paper, folia]
            versions: ["latest"]
            memory: 2G
            crash-guard: true
            plugins:
              - build/libs/*.jar
            timeout: 30s
            steps:
              - enabled: MyRPGPlugin
                timeout: 60s
              - bot-probe:
                  max-latency: 400ms
              - assert-bridge:
                  url: "http://127.0.0.1:25590"
                  min-tps: 18.5
                  max-mspt: 45.0
                  max-errors: 0
              - assert-world-integrity:
                  world: "world"
        """.trimIndent()

        val CI_MATRIX_TEMPLATE = """
            # Multi-platform CI matrix designed for sharded GitHub Actions + SARIF v2.1.0 + HTML reports.
            name: multi-platform release verification matrix
            platforms: [paper, folia, purpur]
            versions: ["latest", "1.21.4"]
            memory: 2G
            crash-guard: true
            plugins:
              - build/libs/*.jar
            properties:
              online-mode: "false"
              server-ip: "127.0.0.1"
              sync-chunk-writes: "false"
            forbid:
              - "Could not load 'plugins"
              - re:Exception in thread "Server thread"
            timeout: 25s
            steps:
              - enabled: MyPlugin
                timeout: 60s
              - bot-probe:
                  max-latency: 500ms
              - send: "version"
              - expect: re:Checking version|This server is running
              - wait: 3s
        """.trimIndent()
    }
}
