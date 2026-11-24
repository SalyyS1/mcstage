package com.salyvn.mcstage

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.versionOption
import com.salyvn.mcstage.cli.AggregateCommand
import com.salyvn.mcstage.cli.CacheCommand
import com.salyvn.mcstage.cli.CompareCommand
import com.salyvn.mcstage.cli.CreateCommand
import com.salyvn.mcstage.cli.InitCommand
import com.salyvn.mcstage.cli.McpCommand
import com.salyvn.mcstage.cli.RunCommand
import com.salyvn.mcstage.cli.TestCommand
import com.salyvn.mcstage.cli.ValidateCommand
import com.salyvn.mcstage.cli.VersionsCommand
import com.salyvn.mcstage.core.BuildInfo

/**
 * Entry point.
 *
 * Everything here starts a real server: the published jar, the real plugin, the real console. Nothing is
 * mocked, because the failures worth catching before a release (a plugin that will not enable on one
 * version, a command that throws, a shutdown that hangs) are the ones a mock cannot have.
 */
class McstageCli : CliktCommand(name = "mcstage") {

    override fun help(context: Context) =
        "Boot real Minecraft servers, install your plugin, drive the console and assert on the log."

    override fun run() = Unit
}

fun main(args: Array<String>) = McstageCli()
    .versionOption(BuildInfo.version, names = setOf("--version"))
    .subcommands(
        TestCommand(),
        ValidateCommand(),
        AggregateCommand(),
        CompareCommand(),
        RunCommand(),
        CreateCommand(),
        VersionsCommand(),
        CacheCommand(),
        InitCommand(),
        McpCommand(),
    )
    .main(args)
