package com.salyvn.mcstage.instance

import com.salyvn.mcstage.cache.JarCache
import com.salyvn.mcstage.core.Platform
import com.salyvn.mcstage.core.SetupError
import com.salyvn.mcstage.testing.FakePublisher
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class InstanceCreatorTest {

    @TempDir
    lateinit var work: Path

    private val publisher = FakePublisher()

    private fun creator() = InstanceCreator(JarCache(work.resolve("cache"), publisher.http), publisher.indexes)

    private fun request(
        acceptEula: Boolean = true,
        version: String = "latest",
        plugins: List<String> = emptyList(),
        properties: Map<String, String> = emptyMap(),
        bind: String = "127.0.0.1",
    ) = CreateRequest(
        platform = Platform.PAPER,
        version = version,
        acceptEula = acceptEula,
        plugins = plugins,
        properties = properties,
        bind = bind,
    )

    @Test
    fun `creates an instance that is not reachable from the network`() {
        val result = creator().create(work.resolve("server"), request())
        val properties = ServerProperties.read(result.instance.propertiesFile)

        assertEquals("127.0.0.1", properties["server-ip"])
        assertEquals("false", properties["online-mode"])
        assertEquals("false", properties["enable-rcon"])
        assertEquals("false", properties["enable-query"])
        assertEquals("25565", properties["server-port"])
        // A named seed is what makes two runs of a scenario the same world.
        assertEquals("mcstage", properties["level-seed"])
        assertTrue(result.notes.isEmpty(), result.notes.toString())
    }

    @Test
    fun `says so when offline authentication is paired with an address the network can reach`() {
        val result = creator().create(work.resolve("open"), request(bind = "0.0.0.0"))

        assertTrue(
            result.notes.any { it.contains("0.0.0.0") && it.contains("any name") },
            result.notes.toString(),
        )
    }

    @Test
    fun `latest skips a release candidate unless one was asked for`() {
        val stable = creator().create(work.resolve("stable"), request())
        assertEquals("1.21.5", stable.descriptor.version)

        val exact = creator().create(work.resolve("rc"), request(version = "1.21.5-rc1"))
        assertEquals("1.21.5-rc1", exact.descriptor.version)
    }

    @Test
    fun `a family name resolves to the newest version in it`() {
        val result = creator().create(work.resolve("family"), request(version = "1.20"))
        assertEquals("1.20.6", result.descriptor.version)
    }

    @Test
    fun `a version that is not published is refused with what is available`() {
        val error = assertThrows<NoSuchElementException> {
            creator().create(work.resolve("nope"), request(version = "1.9.4"))
        }
        assertTrue(error.message!!.contains("1.21.5"), error.message!!)
    }

    @Test
    fun `the eula is only accepted when it was asked for, and the instance says which`() {
        val untouched = creator().create(work.resolve("no-eula"), request(acceptEula = false))
        assertFalse(untouched.instance.eulaAccepted())
        assertFalse(untouched.descriptor.eulaAccepted)
        assertTrue(untouched.notes.any { it.contains("--accept-eula") }, untouched.notes.toString())

        val accepted = creator().create(work.resolve("eula"), request(acceptEula = true))
        assertTrue(accepted.instance.eulaAccepted())
        assertTrue(Files.readString(accepted.instance.eulaFile).contains("eula=true"))
    }

    @Test
    fun `plugins are installed from a file, a directory and a glob`() {
        val source = work.resolve("libs")
        Files.createDirectories(source)
        for (name in listOf("one-1.0.jar", "two-2.0.jar", "notes.txt")) {
            Files.writeString(source.resolve(name), name)
        }

        val fromGlob = creator().create(
            work.resolve("globbed"),
            // Written as text on purpose: a glob is not a path, and on Windows it cannot become one.
            request(plugins = listOf("$source/*.jar")),
        )
        assertEquals(listOf("one-1.0.jar", "two-2.0.jar"), fromGlob.plugins)

        val fromDirectory = creator().create(
            work.resolve("directory"),
            request(plugins = listOf(source.toString())),
        )
        assertEquals(listOf("one-1.0.jar", "two-2.0.jar"), fromDirectory.plugins)

        val error = assertThrows<SetupError> {
            creator().create(work.resolve("missing"), request(plugins = listOf("nowhere/*.jar")))
        }
        assertTrue(error.message!!.contains("No plugin jar matched"), error.message!!)
    }

    @Test
    fun `re-creating keeps edits but moves the port, and the descriptor round trips`() {
        val dir = work.resolve("reused")
        creator().create(dir, request())
        val properties = ServerProperties.read(Instance(dir).propertiesFile)
        properties["view-distance"] = "12"
        properties["motd"] = "mine"
        properties.write(Instance(dir).propertiesFile)

        val again = creator().create(dir, request().copy(port = 25599))
        val after = ServerProperties.read(again.instance.propertiesFile)

        assertEquals("12", after["view-distance"], "an edit should survive")
        assertEquals("mine", after["motd"])
        assertEquals("25599", after["server-port"], "the port is mcstage's to set")
        assertEquals(again.descriptor, Instance(dir).descriptor())
        assertEquals("sha256:${publisher.checksum.hex}", again.descriptor.checksum)
    }

    @Test
    fun `offline uses an instance that is already there instead of resolving it again`() {
        val dir = work.resolve("offline")
        creator().create(dir, request())

        val cache = JarCache(work.resolve("cache"), publisher.http, offline = true)
        val events = mutableListOf<String>()
        val again = InstanceCreator(cache, publisher.indexes).create(dir, request()) { events += it }

        assertFalse(again.resolved, "nothing should have been resolved or fetched")
        assertEquals("1.21.5", again.descriptor.version)
        assertTrue(events.any { it.startsWith("offline:") }, events.toString())
    }

    @Test
    fun `an instance of another platform is not overwritten without force`() {
        val dir = work.resolve("clash")
        creator().create(dir, request())

        val error = assertThrows<SetupError> {
            creator().create(dir, CreateRequest(platform = Platform.PURPUR, acceptEula = true))
        }
        assertTrue(error.message!!.contains("--force"), error.message!!)

        val forced = creator().create(
            dir,
            CreateRequest(platform = Platform.PURPUR, acceptEula = true, force = true),
        )
        assertEquals("purpur", forced.descriptor.platform)
    }
}
