package com.salyvn.mcstage.cli

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.path
import com.salyvn.mcstage.core.BuildIndex
import com.salyvn.mcstage.core.Platform
import com.salyvn.mcstage.core.SetupError
import com.salyvn.mcstage.instance.CreateRequest
import com.salyvn.mcstage.instance.InstanceCreator

/**
 * Makes a directory a server can be started in: the jar, its digest checked, plus the smallest set of
 * properties that keeps a test instance off the network.
 */
class CreateCommand : McstageCommand(name = "create") {

    override fun help(context: Context) = "Create a server instance from a published build."

    private val dir by argument("dir", help = "Directory to create the instance in").path()

    private val platform by option("-p", "--platform", help = "paper, folia or purpur")
        .default(Platform.PAPER.id)

    private val version by option("-V", "--version", help = "A version, a family such as 1.21, or latest")
        .default(BuildIndex.LATEST)

    private val build by option("-b", "--build", help = "A specific build number (default: the newest)")

    private val port by option("--port", help = "Port to listen on").int().default(25565)

    private val bind by option(
        "--bind",
        help = "Address to bind to; the default keeps the instance off the network",
    ).default("127.0.0.1")

    private val memory by option("-m", "--memory", help = "Heap size, used for both -Xms and -Xmx")
        .default("2G")

    private val plugins by option("--plugin", help = "A plugin jar, a directory of them, or a glob")
        .multiple()

    private val properties by option("--set", help = "A server.properties entry, as key=value").multiple()

    private val acceptEula by option(
        "--accept-eula",
        help = "Write eula=true, recording your agreement to the Minecraft EULA",
    ).flag()

    private val prerelease by option("--pre", help = "Allow a release candidate to satisfy latest").flag()

    private val force by option("--force", help = "Replace an instance that is already there").flag()

    override fun execute() {
        val request = CreateRequest(
            platform = Platform.of(platform),
            version = version,
            build = build,
            port = port,
            bind = bind,
            memory = memory,
            acceptEula = acceptEula,
            plugins = plugins,
            properties = properties.associate { entry ->
                val separator = entry.indexOf('=')
                if (separator <= 0) throw SetupError("--set expects key=value, not '$entry'.")
                entry.substring(0, separator).trim() to entry.substring(separator + 1).trim()
            },
            allowPrerelease = prerelease,
            force = force,
        )

        val result = InstanceCreator(cache, indexes).create(dir, request) { echo(it) }
        val descriptor = result.descriptor

        echo("")
        echo("${descriptor.platform} ${descriptor.version} build ${descriptor.build} in $dir")
        echo("  jar        ${descriptor.jarFileName}")
        echo("  ${descriptor.checksum.substringBefore(':').padEnd(10)} ${descriptor.checksum.substringAfter(':')}")
        echo("  listening  ${descriptor.bind}:${descriptor.port}")
        echo("  heap       ${descriptor.memory}")
        if (result.plugins.isNotEmpty()) echo("  plugins    ${result.plugins.joinToString(", ")}")
        echo("  eula       " + if (descriptor.eulaAccepted) "accepted" else "not accepted")
        for (note in result.notes) echo("  note: $note")
    }
}
