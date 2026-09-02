package com.salyvn.mcstage.instance

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class ServerPropertiesTest {

    @TempDir
    lateinit var dir: Path

    @Test
    fun `keeps the keys it does not know about, and writes the same bytes twice`() {
        val file = dir.resolve("server.properties")
        Files.writeString(
            file,
            """
            #Minecraft server properties
            allow-flight=true
            some-future-key=42
            server-port=25565
            """.trimIndent(),
        )

        val properties = ServerProperties.read(file)
        properties["server-port"] = "25599"
        properties.write(file)
        val first = Files.readString(file)

        ServerProperties.read(file).write(file)
        assertEquals(first, Files.readString(file), "writing an unchanged file should change nothing")

        val reread = ServerProperties.read(file)
        assertEquals("true", reread["allow-flight"])
        assertEquals("42", reread["some-future-key"])
        assertEquals("25599", reread["server-port"])
    }

    @Test
    fun `a value that a properties file cannot carry literally survives a round trip`() {
        val file = dir.resolve("motd.properties")
        val properties = ServerProperties.empty()
        properties["motd"] = "may xay dung o Viet Nam — test"
        properties["path"] = """C:\servers\test"""
        properties.write(file)

        val text = Files.readString(file)
        assertTrue(text.contains("\\u2014"), "a non-ascii character should be escaped: $text")
        assertTrue(text.contains("""C:\\servers\\test"""), text)

        val reread = ServerProperties.read(file)
        assertEquals("may xay dung o Viet Nam — test", reread["motd"])
        assertEquals("""C:\servers\test""", reread["path"])
    }

    @Test
    fun `comments and blank lines are not entries, and a colon separates too`() {
        val file = dir.resolve("odd.properties")
        Files.writeString(file, "# a comment\n\n! another\nlevel-name: world\nempty=\n")

        val properties = ServerProperties.read(file)
        assertEquals("world", properties["level-name"])
        assertEquals("", properties["empty"])
        assertEquals(setOf("level-name", "empty"), properties.asMap().keys)
    }

    @Test
    fun `a header is written as comment lines above the entries`() {
        val file = dir.resolve("headed.properties")
        val properties = ServerProperties.empty()
        properties["a"] = "1"
        properties.write(file, header = "written by a test\nsecond line")

        val lines = Files.readAllLines(file)
        assertEquals("# written by a test", lines[0])
        assertEquals("# second line", lines[1])
        assertEquals("a=1", lines[2])
    }
}
