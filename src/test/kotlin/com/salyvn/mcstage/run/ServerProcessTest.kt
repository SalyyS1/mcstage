package com.salyvn.mcstage.run

import com.salyvn.mcstage.testing.FakeServerLauncher
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Exercises the pipes rather than the logic: a real child process, its real stdout, its real stdin.
 *
 * These are the tests that would have caught the thing this design was least sure of — whether commands
 * written to a process that is not attached to a terminal arrive at all.
 */
class ServerProcessTest {

    @TempDir
    lateinit var dir: Path

    private val ready = LineMatcher.parse(com.salyvn.mcstage.core.Platform.DEFAULT_READY)

    @Test
    fun `reads the ready line and accepts a command over stdin`() {
        val server = ServerProcess(FakeServerLauncher.command(), dir)
        server.start()

        val boot = server.await(ready, 20_000) {}
        assertTrue(boot is WaitResult.Matched, "expected the ready line, got $boot")

        server.send("say hello")
        val echoed = server.await(LineMatcher.parse("issued server command: /say hello"), 10_000) {}
        assertTrue(echoed is WaitResult.Matched, "the command did not reach the console: $echoed")

        assertEquals(StopOutcome.CLEAN, server.stop("stop", 15_000) {})
        assertEquals(0, server.exitCode)
    }

    @Test
    fun `a server that ignores stop is killed and says so`() {
        val server = ServerProcess(FakeServerLauncher.command("hang-on-stop"), dir)
        server.start()
        server.await(ready, 20_000) {}

        assertEquals(StopOutcome.KILLED, server.stop("stop", 2_000) {})
        assertTrue(server.exitCode != 0, "a killed process should not report success")
    }

    @Test
    fun `a server that dies during start-up reports its exit code instead of timing out`() {
        val server = ServerProcess(FakeServerLauncher.command("die-before-ready"), dir)
        server.start()

        val outcome = server.await(ready, 20_000) {}
        assertTrue(outcome is WaitResult.Exited, "expected an exit, got $outcome")
        assertEquals(1, (outcome as WaitResult.Exited).exitCode)
    }

    @Test
    fun `waiting for something that never appears times out without hanging`() {
        val server = ServerProcess(FakeServerLauncher.command(), dir)
        server.start()
        server.await(ready, 20_000) {}

        val outcome = server.await(LineMatcher.parse("this will never be printed"), 700) {}
        assertTrue(outcome is WaitResult.TimedOut, "expected a timeout, got $outcome")
        server.stop("stop", 10_000) {}
    }

    @Test
    fun `the transcript keeps the lines in the order they arrived`() {
        val server = ServerProcess(FakeServerLauncher.command("warn"), dir)
        server.start()
        server.await(ready, 20_000) {}
        server.stop("stop", 15_000) {}

        val texts = server.transcript.map { it.text }
        val starting = texts.indexOfFirst { it.contains("Starting minecraft server") }
        val warning = texts.indexOfFirst { it.contains("WARN") }
        val done = texts.indexOfFirst { it.contains("Done (") }

        assertTrue(starting in 0 until warning, "start-up should come first: $texts")
        assertTrue(warning < done, "the warning was printed before the ready line: $texts")
        assertNotNull(server.transcript.first().elapsedMillis)
        assertEquals(server.transcript.size, server.linesSeen)
    }
}
