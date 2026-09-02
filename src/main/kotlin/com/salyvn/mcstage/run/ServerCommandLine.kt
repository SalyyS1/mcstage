package com.salyvn.mcstage.run

import com.salyvn.mcstage.core.JavaRuntime
import com.salyvn.mcstage.core.SetupError
import com.salyvn.mcstage.instance.Instance

/**
 * How a server is launched. There is one real implementation; the seam exists so the runner's own
 * behaviour — timeouts, forbidden lines, a shutdown that has to be forced — can be tested against a
 * process that misbehaves on purpose, without waiting on a real server to do it.
 */
fun interface ServerLauncher {
    fun commandFor(instance: Instance, scenario: Scenario, java: JavaRuntime): List<String>

    companion object {
        val REAL = ServerLauncher { instance, scenario, java ->
            ServerCommandLine.of(instance, scenario.memory, scenario.jvmArgs, java)
        }
    }
}

/**
 * The command line that starts a server.
 *
 * The console is pinned to UTF-8 in both directions. Without that, the encoding of a server's output
 * depends on the machine's locale, and a plugin name with an accent in it arrives as mojibake on exactly
 * one developer's laptop.
 */
object ServerCommandLine {

    private val MEMORY = Regex("""\d+[KkMmGg]""")

    private val ENCODING = listOf("file.encoding", "stdout.encoding", "stderr.encoding")

    fun of(instance: Instance, memory: String, jvmArgs: List<String>, java: JavaRuntime): List<String> {
        val heap = memory.trim()
        if (!heap.matches(MEMORY)) {
            throw SetupError("'$heap' is not a memory size. Write something like 2G or 1536M.")
        }

        return buildList {
            add(java.binary.toString())
            // Matching the initial heap to the maximum is what Paper's own documentation recommends, and
            // on a throwaway instance it also means the heap size in a report is the real one.
            add("-Xms$heap")
            add("-Xmx$heap")
            addAll(
                ENCODING.filter { property -> jvmArgs.none { it.startsWith("-D$property") } }
                    .map { "-D$it=UTF-8" },
            )
            addAll(jvmArgs)
            add("-jar")
            add(instance.jar.fileName.toString())
            // Without this the server opens a window, which a test run has no use for and a CI runner has
            // no display for.
            add("--nogui")
        }
    }
}
