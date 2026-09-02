package com.salyvn.mcstage.testing

import com.salyvn.mcstage.run.ServerLauncher
import java.nio.file.Path

/**
 * Starts [FakeServer] with the JVM the tests are already running on.
 *
 * The java binary and the classpath both have to come from the test JVM: the binary because the one on
 * PATH may be any version, and the classpath because the build is the only thing that knows where the
 * test classes were compiled to. The build passes it in as a system property.
 */
object FakeServerLauncher {

    private val javaBinary: String by lazy {
        val name = if (System.getProperty("os.name").startsWith("Windows", true)) "java.exe" else "java"
        Path.of(System.getProperty("java.home"), "bin", name).toString()
    }

    private val classpath: String by lazy {
        System.getProperty("mcstage.test.classpath")
            ?: error("mcstage.test.classpath was not set; see the test task in build.gradle.kts.")
    }

    fun command(vararg flags: String): List<String> =
        listOf(javaBinary, "-Dfile.encoding=UTF-8", "-cp", classpath, FakeServer::class.java.name) + flags

    fun launcher(vararg flags: String) = ServerLauncher { _, _, _ -> command(*flags) }
}
