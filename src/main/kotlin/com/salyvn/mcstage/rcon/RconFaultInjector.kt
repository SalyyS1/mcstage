package com.salyvn.mcstage.rcon

import com.salyvn.mcstage.core.Durations
import com.salyvn.mcstage.run.LineMatcher
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ceil

/**
 * Deterministic 64-bit SplitMix64 PRNG used for reproducible RCON latency jitter, packet drops,
 * and wire-frame corruption schedules in CI.
 */
class SplitMix64(seed: Long) {
    private var state: Long = seed

    fun nextLong(): Long {
        state += -0x61c8864680b583ebL // 0x9E3779B97F4A7C15UL
        var z = state
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L // 0xBF58476D1CE4E5B9UL
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L // 0x94D049BB133111EBUL
        return z xor (z ushr 31)
    }

    /** Uniform double in [0.0, 1.0). */
    fun nextDouble(): Double = ((nextLong() ushr 11).toDouble()) / (1L shl 53).toDouble()

    /** Uniform non-negative long in [0, boundExclusive). */
    fun nextMillis(boundInclusive: Long): Long {
        if (boundInclusive <= 0L) return 0L
        val span = boundInclusive + 1L
        val raw = nextLong() and Long.MAX_VALUE
        return raw % span
    }
}

/**
 * Valve / Minecraft Source RCON binary wire packet:
 * `[length: int32 LE][requestId: int32 LE][type: int32 LE][utf8 payload][0x00][0x00]`
 */
data class RconPacket(
    val requestId: Int,
    val type: Int,
    val payload: String,
) {
    fun encode(): ByteArray {
        val body = payload.toByteArray(StandardCharsets.UTF_8)
        require(body.size <= MAX_CLIENT_PAYLOAD_BYTES) {
            "RCON payload (${body.size} bytes) exceeds $MAX_CLIENT_PAYLOAD_BYTES-byte wire limit."
        }
        val frameBodyLen = 4 + 4 + body.size + 2
        val buf = ByteBuffer.allocate(4 + frameBodyLen).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(frameBodyLen)
        buf.putInt(requestId)
        buf.putInt(type)
        buf.put(body)
        buf.put(0)
        buf.put(0)
        return buf.array()
    }

    companion object {
        const val TYPE_AUTH = 3
        const val TYPE_AUTH_OR_EXEC = 2
        const val TYPE_RESPONSE_VALUE = 0
        const val AUTH_FAILED_ID = -1
        const val MIN_FRAME_LENGTH = 10
        const val MAX_CLIENT_PAYLOAD_BYTES = 4096
        const val MAX_RESPONSE_FRAME_BYTES = 65536

        fun decode(input: InputStream, maxFrameBytes: Int = MAX_RESPONSE_FRAME_BYTES): RconPacket {
            val lenBytes = readFullyOrNull(input, 4) ?: throw EOFException("RCON stream closed before frame header")
            val length = ByteBuffer.wrap(lenBytes).order(ByteOrder.LITTLE_ENDIAN).int
            if (length < MIN_FRAME_LENGTH || length > maxFrameBytes) {
                throw RconWireException(
                    RconWireException.Kind.CORRUPT_FRAME,
                    "Malformed RCON frame length $length (expected $MIN_FRAME_LENGTH..$maxFrameBytes)",
                )
            }
            val body = readFullyExact(input, length)
            if (body[length - 2] != 0.toByte() || body[length - 1] != 0.toByte()) {
                throw RconWireException(
                    RconWireException.Kind.CORRUPT_FRAME,
                    "Malformed RCON frame missing trailing null padding bytes",
                )
            }
            val header = ByteBuffer.wrap(body, 0, 8).order(ByteOrder.LITTLE_ENDIAN)
            val requestId = header.int
            val type = header.int
            val text = String(body, 8, length - 10, StandardCharsets.UTF_8)
            return RconPacket(requestId = requestId, type = type, payload = text)
        }

        private fun readFullyOrNull(input: InputStream, count: Int): ByteArray? {
            val out = ByteArray(count)
            var off = 0
            while (off < count) {
                val n = input.read(out, off, count - off)
                if (n < 0) {
                    if (off == 0) return null
                    throw RconWireException(
                        RconWireException.Kind.CORRUPT_FRAME,
                        "Truncated RCON frame header ($off/$count bytes)",
                    )
                }
                off += n
            }
            return out
        }

        private fun readFullyExact(input: InputStream, count: Int): ByteArray {
            val out = ByteArray(count)
            var off = 0
            while (off < count) {
                val n = input.read(out, off, count - off)
                if (n < 0) {
                    throw RconWireException(
                        RconWireException.Kind.CORRUPT_FRAME,
                        "Truncated RCON frame payload ($off/$count bytes)",
                    )
                }
                off += n
            }
            return out
        }
    }
}

class RconWireException(
    val kind: Kind,
    override val message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {
    enum class Kind {
        AUTH_REJECTED,
        REQUEST_ID_DESYNC,
        PACKET_DROPPED,
        CORRUPT_FRAME,
        TIMEOUT,
        IO_FAILURE,
    }
}

/**
 * Ephemeral loopback Source RCON TCP server (`127.0.0.1:0`) used to exercise full binary RCON framing,
 * authentication, fault injection, and retry recovery against any running `ServerProcess`.
 */
class LoopbackRconBridgeServer(
    private val password: String,
    private val handler: (String) -> String,
) : AutoCloseable {

    private val serverSocket = ServerSocket(0, 16, InetAddress.getLoopbackAddress())
    private val closed = AtomicBoolean(false)
    private val pool = Executors.newCachedThreadPool { r ->
        Thread(r, "mcstage-rcon-bridge").apply { isDaemon = true }
    }

    val port: Int get() = serverSocket.localPort

    fun start() {
        pool.execute {
            while (!closed.get()) {
                val client = try {
                    serverSocket.accept()
                } catch (_: Exception) {
                    break
                }
                pool.execute { serveClient(client) }
            }
        }
    }

    private fun serveClient(socket: Socket) {
        socket.use { sock ->
            sock.tcpNoDelay = true
            sock.soTimeout = 10_000
            val input = sock.getInputStream()
            val output = sock.getOutputStream()
            var authenticated = false

            while (!closed.get()) {
                val packet = try {
                    RconPacket.decode(input)
                } catch (_: EOFException) {
                    break
                } catch (_: Exception) {
                    break
                }

                when (packet.type) {
                    RconPacket.TYPE_AUTH -> {
                        if (packet.payload == password) {
                            authenticated = true
                            writePacket(output, RconPacket(packet.requestId, RconPacket.TYPE_AUTH_OR_EXEC, ""))
                        } else {
                            authenticated = false
                            writePacket(output, RconPacket(RconPacket.AUTH_FAILED_ID, RconPacket.TYPE_AUTH_OR_EXEC, ""))
                        }
                    }

                    RconPacket.TYPE_AUTH_OR_EXEC -> {
                        if (!authenticated) {
                            writePacket(output, RconPacket(RconPacket.AUTH_FAILED_ID, RconPacket.TYPE_RESPONSE_VALUE, ""))
                        } else {
                            val reply = try {
                                handler(packet.payload)
                            } catch (e: Exception) {
                                "error: ${e.message ?: "handler failed"}"
                            }
                            writePacket(output, RconPacket(packet.requestId, RconPacket.TYPE_RESPONSE_VALUE, reply))
                        }
                    }

                    else -> {
                        writePacket(output, RconPacket(RconPacket.AUTH_FAILED_ID, RconPacket.TYPE_RESPONSE_VALUE, ""))
                    }
                }
            }
        }
    }

    private fun writePacket(out: OutputStream, packet: RconPacket) {
        out.write(packet.encode())
        out.flush()
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            runCatching { serverSocket.close() }
            pool.shutdownNow()
        }
    }
}

data class RconFaultProfile(
    val commands: List<String> = listOf("list"),
    val iterations: Int = 4,
    val baseLatency: Duration = Duration.ofMillis(20),
    val jitter: Duration = Duration.ofMillis(10),
    val dropRate: Double = 0.2,
    val corruptRate: Double = 0.0,
    val maxRetries: Int = 3,
    val retryBackoff: Duration = Duration.ofMillis(20),
    val attemptTimeout: Duration = Duration.ofMillis(500),
    val maxP95Latency: Duration = Duration.ofMillis(400),
    val seed: Long = 42L,
    val port: Int? = null,
    val password: String = "mcstage-rcon",
    val expectResponse: LineMatcher? = null,
)

data class RconFaultReport(
    val ok: Boolean,
    val totalCommands: Int,
    val succeededCommands: Int,
    val totalAttempts: Int,
    val droppedPackets: Int,
    val corruptedFrames: Int,
    val retriedCommands: Int,
    val p50Millis: Double,
    val p95Millis: Double,
    val maxMillis: Double,
    val lastResponse: String? = null,
    val failureRuleId: String? = null,
    val failureDetail: String? = null,
) {
    fun summaryNote(): String = String.format(
        Locale.ROOT,
        "%d/%d RCON cmd(s) ok (%d attempt(s), %d drop(s), %d corrupt, p50=%.1fms, p95=%.1fms)",
        succeededCommands,
        totalCommands,
        totalAttempts,
        droppedPackets,
        corruptedFrames,
        p50Millis,
        p95Millis,
    )
}

/**
 * Executes an RCON command workload against `127.0.0.1:targetPort` while injecting deterministic
 * latency, jitter, packet drops, and wire-frame corruption from [profile].
 */
object RconFaultInjector {

    fun execute(
        targetPort: Int,
        profile: RconFaultProfile,
        sleepFn: (Long) -> Unit = { ms -> if (ms > 0L) Thread.sleep(ms) },
    ): RconFaultReport {
        val rng = SplitMix64(profile.seed)
        val workload = buildList {
            for (i in 0 until profile.iterations) {
                add(profile.commands[i % profile.commands.size])
            }
        }

        var totalAttempts = 0
        var droppedPackets = 0
        var corruptedFrames = 0
        var retriedCommands = 0
        var succeeded = 0
        var lastResponse: String? = null
        val commandRtts = mutableListOf<Double>()
        var requestSeq = 100

        var activeSocket: Socket? = null
        fun closeActive() {
            runCatching { activeSocket?.close() }
            activeSocket = null
        }

        fun ensureAuthenticatedSocket(timeoutMs: Int): Socket {
            activeSocket?.takeIf { !it.isClosed && it.isConnected }?.let { sock ->
                sock.soTimeout = timeoutMs
                return sock
            }
            val sock = Socket()
            sock.tcpNoDelay = true
            sock.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), targetPort), timeoutMs)
            sock.soTimeout = timeoutMs
            val authReqId = ++requestSeq
            sock.getOutputStream().write(RconPacket(authReqId, RconPacket.TYPE_AUTH, profile.password).encode())
            sock.getOutputStream().flush()
            val authResp = RconPacket.decode(sock.getInputStream())
            if (authResp.requestId == RconPacket.AUTH_FAILED_ID) {
                sock.close()
                throw RconWireException(
                    RconWireException.Kind.AUTH_REJECTED,
                    "RCON authentication rejected by server (requestId=-1)",
                )
            }
            if (authResp.requestId != authReqId) {
                sock.close()
                throw RconWireException(
                    RconWireException.Kind.REQUEST_ID_DESYNC,
                    "RCON auth response ID mismatch (expected $authReqId, got ${authResp.requestId})",
                )
            }
            activeSocket = sock
            return sock
        }

        try {
            for ((cmdIdx, command) in workload.withIndex()) {
                val cmdStartedNanos = System.nanoTime()
                var cmdDone = false
                var lastErr: RconWireException? = null

                for (attempt in 0..profile.maxRetries) {
                    if (attempt > 0) {
                        retriedCommands++
                        sleepFn(profile.retryBackoff.toMillis())
                    }
                    totalAttempts++

                    val injectedDelayMs = profile.baseLatency.toMillis() + rng.nextMillis(profile.jitter.toMillis())
                    val attemptTimeoutMs = maxOf(1, profile.attemptTimeout.toMillis().toInt())

                    // 1. Check whether injected latency alone exceeds per-attempt timeout.
                    if (injectedDelayMs > profile.attemptTimeout.toMillis()) {
                        sleepFn(profile.attemptTimeout.toMillis())
                        closeActive()
                        lastErr = RconWireException(
                            RconWireException.Kind.TIMEOUT,
                            "RCON command '$command' (seq #${cmdIdx + 1}) timed out at ${injectedDelayMs}ms " +
                                "(attempt-timeout ${Durations.format(profile.attemptTimeout)})",
                        )
                        continue
                    }

                    if (injectedDelayMs > 0L) {
                        sleepFn(injectedDelayMs)
                    }

                    // 2. Deterministic packet drop injection.
                    if (profile.dropRate > 0.0 && rng.nextDouble() < profile.dropRate) {
                        droppedPackets++
                        lastErr = RconWireException(
                            RconWireException.Kind.PACKET_DROPPED,
                            "Injected RCON packet loss on '$command' (seq #${cmdIdx + 1}, attempt ${attempt + 1}/${profile.maxRetries + 1})",
                        )
                        continue
                    }

                    // 3. Deterministic wire-frame corruption / desync injection.
                    if (profile.corruptRate > 0.0 && rng.nextDouble() < profile.corruptRate) {
                        corruptedFrames++
                        // Send an intentionally truncated/corrupted frame over the live socket if connected, then reset.
                        runCatching {
                            val sock = ensureAuthenticatedSocket(attemptTimeoutMs)
                            val corruptBytes = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                                .putInt(4) // illegal frame length < 10
                                .putInt(-99)
                                .array()
                            sock.getOutputStream().write(corruptBytes)
                            sock.getOutputStream().flush()
                        }
                        closeActive()
                        lastErr = RconWireException(
                            RconWireException.Kind.CORRUPT_FRAME,
                            "Injected malformed RCON wire frame on '$command' (seq #${cmdIdx + 1}, attempt ${attempt + 1}/${profile.maxRetries + 1})",
                        )
                        continue
                    }

                    // 4. Execute real RCON wire frame exchange.
                    try {
                        val sock = ensureAuthenticatedSocket(attemptTimeoutMs)
                        val reqId = ++requestSeq
                        val out = sock.getOutputStream()
                        out.write(RconPacket(reqId, RconPacket.TYPE_AUTH_OR_EXEC, command).encode())
                        out.flush()
                        val resp = RconPacket.decode(sock.getInputStream())
                        if (resp.requestId == RconPacket.AUTH_FAILED_ID) {
                            closeActive()
                            throw RconWireException(
                                RconWireException.Kind.AUTH_REJECTED,
                                "RCON session lost authentication on '$command' (requestId=-1)",
                            )
                        }
                        if (resp.requestId != reqId) {
                            closeActive()
                            throw RconWireException(
                                RconWireException.Kind.REQUEST_ID_DESYNC,
                                "RCON response requestId desynchronized on '$command' (expected $reqId, got ${resp.requestId})",
                            )
                        }

                        if (profile.expectResponse != null && !profile.expectResponse.matches(resp.payload)) {
                            val rttSoFar = (System.nanoTime() - cmdStartedNanos) / 1_000_000.0
                            commandRtts += rttSoFar
                            return buildReport(
                                ok = false,
                                totalCommands = workload.size,
                                succeeded = succeeded,
                                attempts = totalAttempts,
                                drops = droppedPackets,
                                corrupts = corruptedFrames,
                                retries = retriedCommands,
                                rtts = commandRtts,
                                lastResponse = resp.payload,
                                failureRuleId = "MCS017",
                                failureDetail = "RCON response '${resp.payload.trim()}' did not match ${profile.expectResponse.describe}",
                            )
                        }

                        lastResponse = resp.payload
                        val totalCmdRttMs = (System.nanoTime() - cmdStartedNanos) / 1_000_000.0
                        commandRtts += totalCmdRttMs
                        succeeded++
                        cmdDone = true
                        break
                    } catch (e: RconWireException) {
                        closeActive()
                        if (e.kind == RconWireException.Kind.AUTH_REJECTED) {
                            // Auth rejection with wrong password will not recover via retry.
                            return buildReport(
                                ok = false,
                                totalCommands = workload.size,
                                succeeded = succeeded,
                                attempts = totalAttempts,
                                drops = droppedPackets,
                                corrupts = corruptedFrames,
                                retries = retriedCommands,
                                rtts = commandRtts,
                                lastResponse = lastResponse,
                                failureRuleId = "MCS017",
                                failureDetail = e.message,
                            )
                        }
                        lastErr = e
                    } catch (e: SocketTimeoutException) {
                        closeActive()
                        lastErr = RconWireException(
                            RconWireException.Kind.TIMEOUT,
                            "RCON socket read timed out after ${Durations.format(profile.attemptTimeout)} on '$command'",
                            e,
                        )
                    } catch (e: Exception) {
                        closeActive()
                        lastErr = RconWireException(
                            RconWireException.Kind.IO_FAILURE,
                            "RCON transport error on '$command': ${e.message ?: e::class.simpleName}",
                            e,
                        )
                    }
                }

                if (!cmdDone) {
                    val err = lastErr ?: RconWireException(RconWireException.Kind.PACKET_DROPPED, "RCON retries exhausted")
                    val ruleId = when (err.kind) {
                        RconWireException.Kind.TIMEOUT -> "MCS016"
                        else -> "MCS017"
                    }
                    return buildReport(
                        ok = false,
                        totalCommands = workload.size,
                        succeeded = succeeded,
                        attempts = totalAttempts,
                        drops = droppedPackets,
                        corrupts = corruptedFrames,
                        retries = retriedCommands,
                        rtts = commandRtts,
                        lastResponse = lastResponse,
                        failureRuleId = ruleId,
                        failureDetail = "${err.message} after ${profile.maxRetries + 1} attempt(s)",
                    )
                }
            }
        } finally {
            closeActive()
        }

        val p95 = percentile(commandRtts, 0.95)
        if (p95 > profile.maxP95Latency.toMillis().toDouble()) {
            val formatted = String.format(Locale.ROOT, "%.1f", p95)
            return buildReport(
                ok = false,
                totalCommands = workload.size,
                succeeded = succeeded,
                attempts = totalAttempts,
                drops = droppedPackets,
                corrupts = corruptedFrames,
                retries = retriedCommands,
                rtts = commandRtts,
                lastResponse = lastResponse,
                failureRuleId = "MCS016",
                failureDetail = "RCON p95 latency ${formatted}ms exceeded max-p95 ${Durations.format(profile.maxP95Latency)}",
            )
        }

        return buildReport(
            ok = true,
            totalCommands = workload.size,
            succeeded = succeeded,
            attempts = totalAttempts,
            drops = droppedPackets,
            corrupts = corruptedFrames,
            retries = retriedCommands,
            rtts = commandRtts,
            lastResponse = lastResponse,
            failureRuleId = null,
            failureDetail = null,
        )
    }

    fun percentile(samples: List<Double>, quantile: Double): Double {
        if (samples.isEmpty()) return 0.0
        val sorted = samples.sorted()
        val rank = (ceil(quantile * sorted.size).toInt() - 1).coerceIn(0, sorted.size - 1)
        return sorted[rank]
    }

    private fun buildReport(
        ok: Boolean,
        totalCommands: Int,
        succeeded: Int,
        attempts: Int,
        drops: Int,
        corrupts: Int,
        retries: Int,
        rtts: List<Double>,
        lastResponse: String?,
        failureRuleId: String?,
        failureDetail: String?,
    ): RconFaultReport = RconFaultReport(
        ok = ok,
        totalCommands = totalCommands,
        succeededCommands = succeeded,
        totalAttempts = attempts,
        droppedPackets = drops,
        corruptedFrames = corrupts,
        retriedCommands = retries,
        p50Millis = percentile(rtts, 0.50),
        p95Millis = percentile(rtts, 0.95),
        maxMillis = rtts.maxOrNull() ?: 0.0,
        lastResponse = lastResponse,
        failureRuleId = failureRuleId,
        failureDetail = failureDetail,
    )
}
