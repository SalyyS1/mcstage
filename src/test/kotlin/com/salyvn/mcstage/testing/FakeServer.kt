package com.salyvn.mcstage.testing

import java.util.concurrent.TimeUnit

/**
 * A process that behaves like a server, for testing the parts of mcstage that talk to one.
 *
 * It exists because the interesting failures are the ones a real server almost never produces on demand:
 * dying during start-up, ignoring the stop command, printing a stack trace at the wrong moment. Waiting
 * for a real server to do those on cue is not a test suite.
 *
 * The lines it prints follow the layout a Paper console prints, because that is what the matchers in the
 * tool are written against.
 */
object FakeServer {

    private val started = System.nanoTime()

    @JvmStatic
    fun main(args: Array<String>) {
        val flags = args.toSet()
        val readyDelay = args.firstOrNull { it.startsWith("slow-ready=") }
            ?.removePrefix("slow-ready=")?.toLongOrNull() ?: 0

        log("INFO", "Starting minecraft server version 1.21.4")
        log("INFO", "Loading properties")

        if ("warn" in flags) log("WARN", "Something worth mentioning happened")

        if ("die-before-ready" in flags) {
            log("ERROR", "FAILED TO BIND TO PORT!")
            System.out.flush()
            kotlin.system.exitProcess(1)
        }

        if (readyDelay > 0) TimeUnit.MILLISECONDS.sleep(readyDelay)

        log("INFO", "Done (${"%.3f".format(elapsedSeconds())}s)! For help, type \"help\"")

        if ("enable-plugin" in flags) log("INFO", "[TestPlugin] Enabling TestPlugin v1.0.0")
        if ("folia-violation" in flags) {
            log("ERROR", "Thread failed main thread check: Cannot read world asynchronously")
            println("java.lang.IllegalStateException: TickThread.ensureTickThread")
            println("\tat io.papermc.paper.util.TickThread.ensureTickThread(TickThread.java:57)")
            System.out.flush()
        }
        if ("write-crash" in flags) {
            val crashDir = java.nio.file.Path.of("crash-reports")
            java.nio.file.Files.createDirectories(crashDir)
            val crashFile = crashDir.resolve("crash-2026-11-15_03.30.00-server.txt")
            java.nio.file.Files.writeString(
                crashFile,
                """
                ---- Minecraft Crash Report ----
                Time: 2026-11-15 03:30:00
                Description: Exception ticking world

                java.lang.NullPointerException: chunk holder missing
                    at com.example.TestPlugin.tick(TestPlugin.java:44)
                """.trimIndent() + "\n",
            )
            log("ERROR", "This crash report has been saved to: ${crashFile.toAbsolutePath()}")
        }
        if ("throw" in flags) {
            log("ERROR", "Could not pass event PlayerJoinEvent to TestPlugin v1.0.0")
            println("java.lang.IllegalStateException: broken on purpose")
            println("\tat com.example.TestPlugin.onJoin(TestPlugin.java:12)")
            System.out.flush()
        }

        val input = System.`in`.bufferedReader()
        while (true) {
            val line = input.readLine() ?: break
            val command = line.trim()
            if (command.isEmpty()) continue
            log("INFO", "issued server command: /$command")

            if (command == "stop") {
                if ("hang-on-stop" in flags) {
                    log("INFO", "Ignoring stop, on purpose")
                    while (true) TimeUnit.SECONDS.sleep(1)
                }
                log("INFO", "Stopping server")
                System.out.flush()
                kotlin.system.exitProcess(0)
            }
            if (command == "boom") {
                log("ERROR", "java.lang.RuntimeException: asked for it")
                System.out.flush()
                kotlin.system.exitProcess(3)
            }
            if (command == "emit-barrier-stall") {
                log("ERROR", "[ThreadedRegionizer] Region merge barrier stalled waiting on chunk (48, 0)")
                continue
            }
            if (command.startsWith("emit-region-fault ")) {
                val mode = command.removePrefix("emit-region-fault ").trim()
                println("[12:00:05 ERROR]: [Region Scheduler Thread #2/ERROR]: Exception ticking region section(3, -1)")
                if (mode == "recover") {
                    TimeUnit.MILLISECONDS.sleep(25)
                    log("INFO", "Recovered region section(3, -1) and resumed ticking")
                }
                System.out.flush()
                continue
            }
            if (command == "emit-region-crash-loop") {
                for (i in 1..3) {
                    println("[12:00:05 ERROR]: Exception ticking region chunk (32, -16) on tick #$i")
                }
                System.out.flush()
                continue
            }
            if (command.startsWith("emit-lag ")) {
                val ms = command.removePrefix("emit-lag ").trim().toLongOrNull() ?: 1000L
                val ticks = maxOf(1L, ms / 50L)
                log("WARN", "Can't keep up! Is the server overloaded? Running ${ms}ms or $ticks ticks behind")
                continue
            }
            if (command.startsWith("slow-cmd ")) {
                val parts = command.removePrefix("slow-cmd ").trim().split(' ', limit = 2)
                val delayMs = parts.getOrNull(0)?.toLongOrNull() ?: 0L
                val reply = parts.getOrNull(1) ?: "pong"
                if (delayMs > 0) TimeUnit.MILLISECONDS.sleep(delayMs)
                log("INFO", reply)
                continue
            }
            if (command.startsWith("write ")) {
                val rest = command.removePrefix("write ")
                val splitAt = rest.indexOf(' ')
                if (splitAt > 0) {
                    val rel = rest.substring(0, splitAt)
                    val body = rest.substring(splitAt + 1)
                    val out = java.nio.file.Path.of(rel)
                    out.parent?.let { java.nio.file.Files.createDirectories(it) }
                    java.nio.file.Files.writeString(out, body + "\n")
                    log("INFO", "wrote $rel")
                }
            }
        }
        // End of input, which a console that has been closed looks like.
        log("INFO", "Stopping server")
        System.out.flush()
        kotlin.system.exitProcess(0)
    }

    private fun log(level: String, message: String) {
        val stamp = java.time.LocalTime.now().withNano(0)
        println("[$stamp $level]: $message")
        System.out.flush()
    }

    private fun elapsedSeconds() = (System.nanoTime() - started) / 1_000_000_000.0
}
