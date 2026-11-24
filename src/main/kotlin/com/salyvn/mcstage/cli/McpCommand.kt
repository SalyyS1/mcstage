package com.salyvn.mcstage.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.salyvn.mcstage.mcp.McstageMcpServer

/**
 * Starts `mcstage` as a JSON-RPC 2.0 stdio Model Context Protocol (MCP) server for AI coding agents.
 */
class McpCommand : CliktCommand(name = "mcp") {

    override fun help(context: Context) =
        "Run mcstage as a stdio MCP server (validate scenarios, analyze logs with MCS001-MCS019, plan Folia topologies)."

    override fun run() {
        McstageMcpServer().serve(System.`in`, System.out)
    }
}
