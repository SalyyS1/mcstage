package com.salyvn.mcstage.testing

import com.salyvn.mcstage.api.BuildIndexes
import com.salyvn.mcstage.core.ArtifactRef
import com.salyvn.mcstage.core.BuildIndex
import com.salyvn.mcstage.core.Checksum
import com.salyvn.mcstage.core.Digest
import com.salyvn.mcstage.core.DownloadFailed
import com.salyvn.mcstage.core.Http
import com.salyvn.mcstage.core.Platform
import java.nio.file.Files
import java.nio.file.Path

/**
 * A publisher that exists only in memory: a fixed list of versions and one file whose digest is real.
 *
 * The digest being real is the point. It means the cache's verification runs for what it is rather than
 * being stubbed out, which is the part of the download path most worth having covered.
 */
class FakePublisher(private val bytes: ByteArray = "a jar, for testing".toByteArray()) {

    val checksum: Checksum by lazy {
        val temporary = Files.createTempFile("mcstage-fake", ".jar")
        Files.write(temporary, bytes)
        val hex = Digest.of(temporary, "sha256")
        Files.deleteIfExists(temporary)
        Checksum.sha256(hex)
    }

    var downloads = 0
        private set

    val http: Http = object : Http() {
        override fun getString(url: String): String? = null

        override fun download(url: String, target: Path, expected: Checksum?, onProgress: (Long) -> Unit) {
            if (url == CORRUPT_URL) {
                Files.createDirectories(target.parent)
                Files.write(target, "not what was published".toByteArray())
                throw DownloadFailed("$url did not match its published digest.")
            }
            downloads++
            Files.createDirectories(target.parent)
            Files.write(target, bytes)
        }
    }

    val indexes: BuildIndexes = object : BuildIndexes(http) {
        override fun of(platform: Platform): BuildIndex = index
    }

    val index: BuildIndex = object : BuildIndex {
        override fun versions(platform: Platform) =
            listOf("1.21.5", "1.21.5-rc1", "1.21.4", "1.21.1", "1.20.6")

        override fun resolve(platform: Platform, version: String, build: String?) = ArtifactRef(
            platform = platform,
            version = version,
            build = build ?: "232",
            fileName = "${platform.id}-$version-${build ?: "232"}.jar",
            url = "https://example.invalid/${platform.id}-$version.jar",
            checksum = checksum,
            sizeBytes = bytes.size.toLong(),
        )
    }

    companion object {
        const val CORRUPT_URL = "https://example.invalid/corrupt.jar"
    }
}
