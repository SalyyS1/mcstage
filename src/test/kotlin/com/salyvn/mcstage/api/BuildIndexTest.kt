package com.salyvn.mcstage.api

import com.salyvn.mcstage.core.Checksum
import com.salyvn.mcstage.core.DownloadFailed
import com.salyvn.mcstage.core.Http
import com.salyvn.mcstage.core.Platform
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.file.Path

/**
 * The two build indexes, run against the shapes their live services actually returned on 2026-08-25.
 *
 * The payloads below are trimmed copies of real responses rather than something invented, so that a test
 * passing means the parser handles what the service sends, not what the parser's author assumed it sends.
 */
class BuildIndexTest {

    private fun http(vararg answers: Pair<String, String?>) = object : Http() {
        val byPath = answers.toMap()
        override fun getString(url: String): String? {
            val match = byPath.entries.firstOrNull { url.endsWith(it.key) }
                ?: error("nothing stubbed for $url")
            return match.value
        }

        override fun download(url: String, target: Path, expected: Checksum?, onProgress: (Long) -> Unit) =
            error("no download in this test")
    }

    @Test
    fun `fill lists versions newest first across families and resolves a build`() {
        val client = FillClient(
            http(
                "/projects" to FILL_PROJECTS,
                "/builds/latest" to FILL_BUILD,
            ),
        )

        assertEquals(listOf("26.2", "26.2-rc-2", "1.21.11", "1.21.10"), client.versions(Platform.PAPER))

        val ref = client.resolve(Platform.PAPER, "1.21.4")
        assertEquals("232", ref.build)
        assertEquals("paper-1.21.4-232.jar", ref.fileName)
        assertEquals("sha256", ref.checksum.algorithm)
        assertEquals(51437498L, ref.sizeBytes)
        // fill addresses the file by its own digest, which is why the cache can be keyed by one.
        assertTrue(ref.url.contains(ref.checksum.hex), ref.url)
    }

    @Test
    fun `fill refusing to name a digest stops the jar from being installed`() {
        val client = FillClient(http("/builds/latest" to FILL_BUILD_WITHOUT_DIGEST))

        val error = assertThrows<DownloadFailed> { client.resolve(Platform.PAPER, "1.21.4") }
        assertTrue(error.message!!.contains("no sha256"), error.message!!)
    }

    @Test
    fun `a project fill does not publish is named rather than guessed at`() {
        val client = FillClient(http("/projects" to FILL_PROJECTS))
        val error = assertThrows<DownloadFailed> { client.versions(Platform.FOLIA) }
        assertTrue(error.message!!.contains("folia"), error.message!!)
    }

    @Test
    fun `purpur is reversed into newest first and verified by md5`() {
        val client = PurpurClient(http("/purpur" to PURPUR_PROJECT, "/2416" to PURPUR_BUILD))

        assertEquals(listOf("26.2", "1.21.11", "1.14.1"), client.versions(Platform.PURPUR))

        val ref = client.resolve(Platform.PURPUR, "1.21.4", "2416")
        assertEquals("md5", ref.checksum.algorithm)
        assertEquals("e3832b46efa4a2a56d31bc7ce26df6f0", ref.checksum.hex)
        assertEquals("purpur-1.21.4-2416.jar", ref.fileName)
        assertTrue(ref.url.endsWith("/purpur/1.21.4/2416/download"), ref.url)
    }

    @Test
    fun `a purpur build that did not succeed is not offered as a download`() {
        val client = PurpurClient(http("/latest" to PURPUR_FAILED_BUILD))
        val error = assertThrows<DownloadFailed> { client.resolve(Platform.PURPUR, "1.21.4") }
        assertTrue(error.message!!.contains("FAILURE"), error.message!!)
    }

    @Test
    fun `latest means the newest stable version and a family means the newest inside it`() {
        val client = FillClient(http("/projects" to FILL_PROJECTS))

        assertEquals("26.2", client.resolveVersion(Platform.PAPER, "latest"))
        assertEquals("26.2-rc-2", client.resolveVersion(Platform.PAPER, "26.2-rc-2"))
        assertEquals("1.21.11", client.resolveVersion(Platform.PAPER, "1.21"))

        val error = assertThrows<NoSuchElementException> { client.resolveVersion(Platform.PAPER, "1.7.10") }
        assertTrue(error.message!!.contains("26.2"), error.message!!)
    }

    @Test
    fun `a version series that has only a release candidate is skipped until one is asked for`() {
        val client = FillClient(
            http(
                "/projects" to """
                    {"projects":[{"project":{"id":"paper","name":"Paper"},
                     "versions":{"27.1":["27.1-rc-1"],"26.2":["26.2"]}}]}
                """.trimIndent(),
            ),
        )

        assertEquals("26.2", client.resolveVersion(Platform.PAPER, "latest"))
        assertEquals("27.1-rc-1", client.resolveVersion(Platform.PAPER, "latest", allowPrerelease = true))
        assertEquals("27.1-rc-1", client.resolveVersion(Platform.PAPER, "27.1", allowPrerelease = true))

        // Without the flag there is no stable 27.1 to have, and saying so beats quietly using the rc.
        assertThrows<NoSuchElementException> { client.resolveVersion(Platform.PAPER, "27.1") }
    }

    private companion object {
        val FILL_PROJECTS = """
            {"projects":[
              {"project":{"id":"paper","name":"Paper"},
               "versions":{"26.2":["26.2","26.2-rc-2"],"1.21":["1.21.11","1.21.10"]}},
              {"project":{"id":"velocity","name":"Velocity"},
               "versions":{"3.0.0":["3.5.1"]}}
            ]}
        """.trimIndent()

        val FILL_BUILD = """
            {"id":232,"time":"2025-01-01T00:00:00Z","channel":"STABLE","commits":[],
             "downloads":{"server:default":{
               "name":"paper-1.21.4-232.jar","size":51437498,
               "checksums":{"sha256":"5ee4f542f628a14c644410b08c94ea42e772ef4d29fe92973636b6813d4eaffc"},
               "url":"https://fill-data.papermc.io/v1/objects/5ee4f542f628a14c644410b08c94ea42e772ef4d29fe92973636b6813d4eaffc/paper-1.21.4-232.jar"}}}
        """.trimIndent()

        val FILL_BUILD_WITHOUT_DIGEST = """
            {"id":232,"downloads":{"server:default":{"name":"paper.jar","url":"https://example.invalid/p.jar",
             "checksums":{}}}}
        """.trimIndent()

        val PURPUR_PROJECT = """
            {"project":"purpur","metadata":{"current":"26.2"},"versions":["1.14.1","1.21.11","26.2"]}
        """.trimIndent()

        val PURPUR_BUILD = """
            {"project":"purpur","version":"1.21.4","build":"2416","result":"SUCCESS",
             "timestamp":1744500645780,"md5":"e3832b46efa4a2a56d31bc7ce26df6f0"}
        """.trimIndent()

        val PURPUR_FAILED_BUILD = """
            {"project":"purpur","version":"1.21.4","build":"2417","result":"FAILURE","md5":null}
        """.trimIndent()
    }
}
