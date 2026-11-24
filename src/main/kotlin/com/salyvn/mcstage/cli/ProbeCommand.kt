package com.salyvn.mcstage.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.default
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.long
import com.salyvn.mcstage.protocol.MinecraftSlpProbe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Duration

/**
 * CLI subcommand for zero-dependency Minecraft Java Edition Server List Ping (SLP) probing (`MCS018`).
 */
class ProbeCommand : CliktCommand(name = "probe") {

    override fun help(context: Context) =
        "Ping a live Minecraft Java Edition server using pure-JVM SLP wire protocol (version, protocol, players, MOTD, RTT ms)."

    private val host by argument("host", help = "Server host or IP (default: 127.0.0.1)").default("127.0.0.1")
    private val port by option("--port", "-p", help = "Server TCP port (default: 25565)").int().default(25565)
    private val timeoutMs by option("--timeout-ms", help = "Connect/read timeout in ms (default: 3000)").long().default(3000L)
    private val json by option("--json", help = "Emit machine-readable JSON output").flag()

    override fun run() {
        val res = MinecraftSlpProbe.probe(
            host = host,
            port = port,
            timeout = Duration.ofMillis(timeoutMs.coerceIn(200L, 15_000L)),
        )
        if (json) {
            val payload = buildJsonObject {
                put("reachable", res.reachable)
                put("host", host)
                put("port", port)
                put("latency_ms", res.latencyMillis)
                res.versionName?.let { put("version", it) }
                res.protocolVersion?.let { put("protocol", it) }
                res.onlinePlayers?.let { put("online_players", it) }
                res.maxPlayers?.let { put("max_players", it) }
                res.motd?.let { put("motd", it) }
                res.error?.let { put("error", it) }
            }
            echo(PRETTY_JSON.encodeToString(payload))
        } else if (res.reachable) {
            echo("SLP Probe $host:$port -> REACHABLE (${res.latencyMillis} ms)")
            echo("  Version  : ${res.versionName ?: "?"} (protocol ${res.protocolVersion ?: "?"})")
            echo("  Players  : ${res.onlinePlayers ?: 0}/${res.maxPlayers ?: 0}")
            if (!res.motd.isNullOrBlank()) {
                echo("  MOTD     : ${res.motd}")
            }
        } else {
            echo("SLP Probe $host:$port -> UNREACHABLE (${res.error ?: "unknown error"})", err = true)
        }
        if (!res.reachable) {
            throw ProgramResult(1)
        }
    }

    private companion object {
        val PRETTY_JSON = Json { prettyPrint = true }
    }
}
