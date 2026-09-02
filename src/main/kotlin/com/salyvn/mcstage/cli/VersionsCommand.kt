package com.salyvn.mcstage.cli

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import com.salyvn.mcstage.core.BuildIndex
import com.salyvn.mcstage.core.Platform

/**
 * What each platform publishes, straight from its own API.
 *
 * Release candidates are hidden unless asked for, because they are not what `latest` resolves to and a
 * list that leads with one invites picking it by accident.
 */
class VersionsCommand : McstageCommand(name = "versions") {

    override fun help(context: Context) = "List the versions a platform publishes."

    private val platformName by argument("platform", help = "paper, folia or purpur; omit for all")
        .optional()

    private val limit by option("-n", "--limit", help = "How many versions to show per platform")
        .int().default(12)

    private val all by option("--all", help = "Show every version, including release candidates").flag()

    override fun execute() {
        val platforms = platformName?.let { listOf(Platform.of(it)) } ?: Platform.entries
        for (platform in platforms) {
            val versions = indexes.of(platform).versions(platform)
            val shown = versions
                .filter { all || !BuildIndex.isPrerelease(it) }
                .let { if (all) it else it.take(limit) }

            echo("${platform.displayName} (${versions.size} versions)")
            for (version in shown) {
                val note = if (BuildIndex.isPrerelease(version)) "  (pre-release)" else ""
                echo("  $version$note")
            }
            if (!all && shown.size < versions.size) {
                echo("  ... ${versions.size - shown.size} older, or pre-releases; --all lists them")
            }
            echo("")
        }
    }
}
