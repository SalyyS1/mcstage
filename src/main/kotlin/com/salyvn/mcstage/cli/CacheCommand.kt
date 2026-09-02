package com.salyvn.mcstage.cli

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.salyvn.mcstage.core.Platform

/**
 * What has been downloaded, and a way to throw it away.
 *
 * Worth having because the cache is the only state mcstage keeps outside a project, and a directory of
 * server jars is the kind of thing people find years later and wonder about.
 */
class CacheCommand : McstageCommand(name = "cache") {

    override fun help(context: Context) = "List or clear the downloaded server jars."

    private val clear by option("--clear", help = "Delete the jars instead of listing them").flag()

    private val platformName by option(
        "-p",
        "--platform",
        help = "Only this platform's jars",
    )

    override fun execute() {
        val platform = platformName?.let { Platform.of(it) }
        val entries = cache.entries().filter { platform == null || it.platform == platform.id }

        if (clear) {
            val removed = cache.clear(platform)
            echo("removed $removed ${if (removed == 1) "jar" else "jars"} from ${cache.root}")
            return
        }

        echo("cache: ${cache.root}")
        if (entries.isEmpty()) {
            echo("  empty")
            return
        }
        val width = entries.maxOf { it.fileName.length }
        for (entry in entries) {
            val size = "%.1f MiB".format(java.util.Locale.ROOT, entry.sizeBytes / MIB)
            echo("  ${entry.fileName.padEnd(width)}  ${size.padStart(9)}  ${entry.checksum}")
        }
        val total = entries.sumOf { it.sizeBytes } / MIB
        echo("  ${entries.size} ${if (entries.size == 1) "jar" else "jars"}, %.1f MiB".format(java.util.Locale.ROOT, total))
    }

    private companion object {
        const val MIB = (1L shl 20).toDouble()
    }
}
