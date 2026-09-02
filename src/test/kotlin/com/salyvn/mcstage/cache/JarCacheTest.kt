package com.salyvn.mcstage.cache

import com.salyvn.mcstage.core.DownloadFailed
import com.salyvn.mcstage.core.Platform
import com.salyvn.mcstage.testing.FakePublisher
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class JarCacheTest {

    @TempDir
    lateinit var root: Path

    private val publisher = FakePublisher()

    private val ref = publisher.index.resolve(Platform.PAPER, "1.21.4", null)

    @Test
    fun `downloads once and then serves the same file`() {
        val cache = JarCache(root, publisher.http)

        val first = cache.ensure(ref)
        val second = cache.ensure(ref)

        assertEquals(first, second)
        assertEquals(1, publisher.downloads, "the second call should not have downloaded anything")
        assertTrue(first.toString().contains(ref.checksum.hex), "the digest belongs in the path: $first")
        assertEquals(ref.fileName, first.fileName.toString())
    }

    @Test
    fun `a cached file that no longer matches its digest is fetched again`() {
        val cache = JarCache(root, publisher.http)
        val path = cache.ensure(ref)
        Files.writeString(path, "something else entirely")

        val events = mutableListOf<String>()
        cache.ensure(ref) { events += it }

        assertEquals(2, publisher.downloads)
        assertTrue(events.any { it.contains("no longer matches") }, events.toString())
    }

    @Test
    fun `offline says what is missing instead of reaching out`() {
        val cache = JarCache(root, publisher.http, offline = true)

        val error = assertThrows<DownloadFailed> { cache.ensure(ref) }

        assertTrue(error.message!!.contains("--offline"), error.message!!)
        assertTrue(error.message!!.contains(ref.label), error.message!!)
        assertEquals(0, publisher.downloads)
    }

    @Test
    fun `listing reports what is there and clearing takes it away`() {
        val cache = JarCache(root, publisher.http)
        cache.ensure(ref)
        cache.ensure(publisher.index.resolve(Platform.PURPUR, "1.21.4", null))

        val entries = cache.entries()
        assertEquals(2, entries.size)
        assertEquals(listOf("paper", "purpur"), entries.map { it.platform })
        assertTrue(entries.all { it.sizeBytes > 0 })

        assertEquals(1, cache.clear(Platform.PAPER))
        assertEquals(listOf("purpur"), cache.entries().map { it.platform })
        assertEquals(1, cache.clear(null))
        assertTrue(cache.entries().isEmpty())
    }

    @Test
    fun `linking into an instance leaves the cached bytes reachable under both names`() {
        val cache = JarCache(root, publisher.http)
        val cached = cache.ensure(ref)
        val target = root.resolve("instance").resolve("server.jar")

        JarCache.link(cached, target)

        assertTrue(Files.exists(target))
        assertEquals(Files.readAllBytes(cached).size, Files.readAllBytes(target).size)
        assertTrue(Files.exists(cached), "the cache must survive being linked from")
    }
}
