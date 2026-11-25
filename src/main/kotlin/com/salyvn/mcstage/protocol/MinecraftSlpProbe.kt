package com.salyvn.mcstage.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.time.Duration

/**
 * Pure-JVM Minecraft Java Edition Server List Ping (SLP) protocol probe.
 *
 * Verifies that a booted Paper, Folia, or Purpur test server actually accepts TCP handshakes,
 * reports the expected version/MOTD, and responds within a strict latency budget — without
 * ever launching a heavy desktop Minecraft client.
 */
object MinecraftSlpProbe {

    data class Result(
        val reachable: Boolean,
        val latencyMillis: Long,
        val versionName: String? = null,
        val protocolVersion: Int? = null,
        val onlinePlayers: Int? = null,
        val maxPlayers: Int? = null,
        val motd: String? = null,
        val error: String? = null,
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun probe(
        host: String = "127.0.0.1",
        port: Int,
        protocolVersion: Int = 769,
        timeout: Duration = Duration.ofSeconds(3),
    ): Result {
        val started = System.nanoTime()
        val timeoutMs = timeout.toMillis().coerceIn(200L, 15_000L).toInt()
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                socket.soTimeout = timeoutMs

                val out = socket.getOutputStream()
                val handshakePayload = ByteArrayOutputStream().apply {
                    writeVarInt(this, 0x00)
                    writeVarInt(this, protocolVersion)
                    val hostBytes = host.toByteArray(StandardCharsets.UTF_8)
                    writeVarInt(this, hostBytes.size)
                    write(hostBytes)
                    DataOutputStream(this).writeShort(port)
                    writeVarInt(this, 1) // nextState = STATUS
                }.toByteArray()

                val handshakePacket = framePacket(handshakePayload)
                val statusRequest = framePacket(byteArrayOf(0x00))
                out.write(handshakePacket)
                out.write(statusRequest)
                out.flush()

                val input = socket.getInputStream()
                val packetLen = readVarInt(input)
                if (packetLen <= 0 || packetLen > 2_000_000) {
                    throw IllegalStateException("Unexpected SLP packet length: $packetLen")
                }
                val packetId = readVarInt(input)
                if (packetId != 0x00) {
                    throw IllegalStateException("Unexpected SLP response packet id: $packetId")
                }
                val jsonLen = readVarInt(input)
                if (jsonLen <= 0 || jsonLen > 2_000_000) {
                    throw IllegalStateException("Unexpected SLP JSON length: $jsonLen")
                }
                val raw = readExact(input, jsonLen).toString(StandardCharsets.UTF_8)
                val elapsedMs = ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(0L)
                val obj = json.parseToJsonElement(raw).jsonObject
                val versionObj = obj["version"] as? JsonObject
                val playersObj = obj["players"] as? JsonObject
                val descElem = obj["description"]
                val motdText = when (descElem) {
                    is JsonObject -> descElem["text"]?.jsonPrimitive?.contentOrNull ?: descElem.toString()
                    null -> null
                    else -> descElem.jsonPrimitive.contentOrNull ?: descElem.toString()
                }
                Result(
                    reachable = true,
                    latencyMillis = elapsedMs,
                    versionName = versionObj?.get("name")?.jsonPrimitive?.contentOrNull,
                    protocolVersion = versionObj?.get("protocol")?.jsonPrimitive?.intOrNull,
                    onlinePlayers = playersObj?.get("online")?.jsonPrimitive?.intOrNull,
                    maxPlayers = playersObj?.get("max")?.jsonPrimitive?.intOrNull,
                    motd = motdText,
                )
            }
        } catch (e: Exception) {
            val elapsedMs = ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(0L)
            Result(
                reachable = false,
                latencyMillis = elapsedMs,
                error = "${e::class.simpleName}: ${e.message ?: "probe failed"}",
            )
        }
    }

    /** Render a shields.io Endpoint Badge JSON summarizing live Minecraft Java SLP reachability & RTT ms (MCS018). */
    fun renderBadgeJson(res: Result): String {
        val color = when {
            !res.reachable -> "red"
            res.latencyMillis > 250L -> "yellow"
            else -> "brightgreen"
        }
        val msg = if (res.reachable) {
            val players = "${res.onlinePlayers ?: 0}/${res.maxPlayers ?: 0}"
            "ONLINE (${res.latencyMillis}ms · $players)"
        } else {
            "OFFLINE"
        }
        return """{"schemaVersion":1,"label":"mcstage server","message":"$msg","color":"$color"}"""
    }

    internal fun framePacket(payload: ByteArray): ByteArray = ByteArrayOutputStream().apply {
        writeVarInt(this, payload.size)
        write(payload)
    }.toByteArray()

    internal fun writeVarInt(out: ByteArrayOutputStream, value: Int) {
        var v = value
        while (true) {
            if ((v and 0x7F.inv()) == 0) {
                out.write(v)
                return
            }
            out.write((v and 0x7F) or 0x80)
            v = v ushr 7
        }
    }

    internal fun readVarInt(input: InputStream): Int {
        var numRead = 0
        var result = 0
        while (true) {
            val read = input.read()
            if (read == -1) throw EOFException("Unexpected EOF while reading Minecraft VarInt")
            result = result or ((read and 0x7F) shl (7 * numRead))
            numRead++
            if (numRead > 5) throw IllegalStateException("Minecraft VarInt exceeds 5 bytes")
            if ((read and 0x80) == 0) return result
        }
    }

    private fun readExact(input: InputStream, count: Int): ByteArray {
        val buf = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val n = input.read(buf, offset, count - offset)
            if (n == -1) throw EOFException("Unexpected EOF while reading SLP payload")
            offset += n
        }
        return buf
    }
}
