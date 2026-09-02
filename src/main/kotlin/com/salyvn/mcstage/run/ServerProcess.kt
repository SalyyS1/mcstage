package com.salyvn.mcstage.run

import java.io.BufferedWriter
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** How a wait ended. Three outcomes, because "it never appeared" and "the server died" are not alike. */
sealed interface WaitResult {
    data class Matched(val line: LogLine) : WaitResult
    data class TimedOut(val waitedMillis: Long) : WaitResult
    data class Exited(val exitCode: Int) : WaitResult
}

enum class StopOutcome {
    /** The stop command was accepted and the process left on its own. */
    CLEAN,

    /** It was still running after the grace period and had to be terminated. */
    KILLED,

    /** It had already gone before anything was asked of it, which usually means it crashed. */
    ALREADY_EXITED,
}

/**
 * A server running as a child process, driven through the two pipes it already has.
 *
 * Output is read from the process itself rather than from `logs/latest.log`, for three reasons: nothing
 * is missed between the process starting and the log file being opened, lines that a crash prevents from
 * being flushed to the file are still seen, and stderr — where a JVM prints the reason it refused to
 * start at all — is in the same stream in the order it happened.
 *
 * Commands go in over stdin, so there is no remote console to enable, no port to open and no password to
 * invent for a throwaway instance.
 *
 * One thread reads the pipe into a queue and the caller is the only consumer, which is what keeps the
 * order of lines and the matching of them in one place.
 */
class ServerProcess(
    private val command: List<String>,
    private val workingDirectory: Path,
    private val transcriptLimit: Int = 4000,
) {

    /** Lines, plus one sentinel when the pipe closes. Typed as Any so the sentinel cannot be a line. */
    private val queue = LinkedBlockingQueue<Any>()
    private val kept = ArrayDeque<LogLine>()
    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private var startedAt = 0L
    private var seen = 0
    private var reachedEnd = false

    /** Every line kept, oldest first. Long runs keep the most recent, which is what a failure needs. */
    val transcript: List<LogLine> get() = kept.toList()

    /** How many lines the server printed, including any dropped from the transcript. */
    val linesSeen: Int get() = seen

    val exitCode: Int? get() = process?.takeIf { !it.isAlive }?.exitValue()

    fun isAlive(): Boolean = process?.isAlive == true

    fun start() {
        check(process == null) { "This process was already started." }
        startedAt = System.nanoTime()
        val started = try {
            ProcessBuilder(command)
                .directory(workingDirectory.toFile())
                .redirectErrorStream(true)
                .start()
        } catch (e: IOException) {
            throw IOException("Could not start ${command.firstOrNull()}: ${e.message}", e)
        }
        process = started
        writer = started.outputStream.bufferedWriter(StandardCharsets.UTF_8)

        // UTF-8 both ways regardless of the platform's default, because a server writes UTF-8 and a
        // console decoded as anything else turns a plugin's name into noise on exactly one machine.
        val reader = started.inputStream.bufferedReader(StandardCharsets.UTF_8)
        val pump = Thread({
            try {
                reader.useLines { lines -> lines.forEach { queue.put(it) } }
            } catch (_: Exception) {
                // The pipe closed under us, which is what happens when the process is killed.
            } finally {
                queue.put(End)
            }
        }, "mcstage-log-pump")
        pump.isDaemon = true
        pump.start()
    }

    /**
     * Consumes output until a line matches.
     *
     * @param onLine called for every line consumed, matching or not, so that a scenario's forbidden
     *   patterns are checked against everything the server said and not only against what was awaited.
     */
    fun await(matcher: LineMatcher, timeoutMillis: Long, onLine: (LogLine) -> Unit): WaitResult {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (true) {
            if (reachedEnd) return WaitResult.Exited(waitForExit())
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) return WaitResult.TimedOut(timeoutMillis)

            val raw = queue.poll(remaining, TimeUnit.NANOSECONDS)
            if (raw == null) return WaitResult.TimedOut(timeoutMillis)
            if (raw === End) {
                reachedEnd = true
                return WaitResult.Exited(waitForExit())
            }

            val line = record(raw as String)
            onLine(line)
            if (matcher.matches(line.text)) return WaitResult.Matched(line)
        }
    }

    /** Consumes output for a fixed stretch of time, for a scenario that just wants to let a server run. */
    fun drain(millis: Long, onLine: (LogLine) -> Unit): WaitResult {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis)
        while (true) {
            if (reachedEnd) return WaitResult.Exited(waitForExit())
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) return WaitResult.TimedOut(millis)
            val raw = queue.poll(remaining, TimeUnit.NANOSECONDS) ?: continue
            if (raw === End) {
                reachedEnd = true
                return WaitResult.Exited(waitForExit())
            }
            onLine(record(raw as String))
        }
    }

    fun send(text: String) {
        val target = writer ?: error("The server was not started.")
        try {
            target.write(text)
            target.write("\n")
            target.flush()
        } catch (e: IOException) {
            throw IOException("The server stopped reading its console before '$text' was delivered.", e)
        }
    }

    /**
     * Asks the server to stop, then insists.
     *
     * The grace period is the interesting part: a server that will not leave after being asked has to be
     * killed for the run to end, and a run that ends that way is reported as having been killed rather
     * than as having stopped, because the two say different things about the plugin under test.
     */
    fun stop(stopCommand: String, timeoutMillis: Long, onLine: (LogLine) -> Unit): StopOutcome {
        val running = process ?: return StopOutcome.ALREADY_EXITED
        if (!running.isAlive) {
            drainRemaining(onLine)
            return StopOutcome.ALREADY_EXITED
        }

        val asked = runCatching { send(stopCommand) }.isSuccess
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        if (asked) {
            while (System.nanoTime() < deadline && !reachedEnd) {
                val remaining = deadline - System.nanoTime()
                val raw = queue.poll(remaining, TimeUnit.NANOSECONDS)
                if (raw == null) break
                if (raw === End) {
                    reachedEnd = true
                    break
                }
                onLine(record(raw as String))
            }
        }

        if (running.waitFor(EXIT_GRACE_MILLIS, TimeUnit.MILLISECONDS)) {
            drainRemaining(onLine)
            closeInput()
            return StopOutcome.CLEAN
        }

        running.destroy()
        if (!running.waitFor(EXIT_GRACE_MILLIS, TimeUnit.MILLISECONDS)) running.destroyForcibly()
        running.waitFor(EXIT_GRACE_MILLIS, TimeUnit.MILLISECONDS)
        drainRemaining(onLine)
        closeInput()
        return StopOutcome.KILLED
    }

    private fun drainRemaining(onLine: (LogLine) -> Unit) {
        while (!reachedEnd) {
            val raw = queue.poll(DRAIN_MILLIS, TimeUnit.MILLISECONDS) ?: return
            if (raw === End) {
                reachedEnd = true
                return
            }
            onLine(record(raw as String))
        }
    }

    private fun closeInput() {
        runCatching { writer?.close() }
        writer = null
    }

    private fun waitForExit(): Int {
        val running = process ?: return -1
        running.waitFor(EXIT_GRACE_MILLIS, TimeUnit.MILLISECONDS)
        return if (running.isAlive) -1 else running.exitValue()
    }

    private fun record(text: String): LogLine {
        seen++
        val line = LogLine(seen, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt), text)
        kept.addLast(line)
        while (kept.size > transcriptLimit) kept.removeFirst()
        return line
    }

    private companion object {
        /** Marks the end of the pipe. An object, so it can never be mistaken for a line of output. */
        object End

        const val EXIT_GRACE_MILLIS = 10_000L
        const val DRAIN_MILLIS = 250L
    }
}
