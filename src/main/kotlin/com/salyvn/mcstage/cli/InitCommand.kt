package com.salyvn.mcstage.cli

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.path
import com.salyvn.mcstage.core.SetupError
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists

/**
 * Writes a scenario to start from.
 *
 * A first scenario is mostly a question of which keys exist, and looking that up is a worse first
 * experience than having a file that already runs.
 */
class InitCommand : McstageCommand(name = "init") {

    override fun help(context: Context) = "Write an example scenario file."

    private val file by argument("file", help = "Where to write it").path().default(Path.of("mcstage.yml"))

    private val force by option("--force", help = "Overwrite the file if it is already there").flag()

    override fun execute() {
        if (file.exists() && !force) {
            throw SetupError("$file already exists. Pass --force to overwrite it.")
        }
        file.parent?.let { Files.createDirectories(it) }
        Files.writeString(file, TEMPLATE)
        echo("Wrote $file")
        echo("Run it with: mcstage test $file --accept-eula")
    }

    private companion object {
        val TEMPLATE = """
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
    }
}
