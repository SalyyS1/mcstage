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
