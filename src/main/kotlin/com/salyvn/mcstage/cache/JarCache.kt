package com.salyvn.mcstage.cache

import com.salyvn.mcstage.core.ArtifactRef
import com.salyvn.mcstage.core.Checksum
import com.salyvn.mcstage.core.Digest
import com.salyvn.mcstage.core.DownloadFailed
import com.salyvn.mcstage.core.Http
import com.salyvn.mcstage.core.Platform
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/** A jar already on disk, as `mcstage cache list` reports it. */
data class CachedJar(
    val platform: String,
    val checksum: Checksum,
    val fileName: String,
    val path: Path,
    val sizeBytes: Long,
)

/**
 * Server jars on disk, addressed by digest.
 *
 * Downloading a server jar is the slowest thing this tool does and the only thing it cannot do on a
 * train, so it happens once per build and never again. The digest is the identity: two runs that resolve
 * to the same build share one file, and a build that is re-resolved after the publisher replaced it
 * lands in a different directory instead of silently overwriting what was verified before.
 *
 * A cached jar is re-hashed every time it is handed out. Skipping that would make the cache a directory
 * of files whose contents are asserted rather than known, and the whole point of keeping the digest in
 * the path is that the claim can be checked.
 */
class JarCache(
    val root: Path = defaultRoot(),
    private val http: Http,
    val offline: Boolean = false,
) {

    /**
     * @param onEvent told what is happening, because a first run downloads tens of megabytes and
     *   silence for thirty seconds reads as a hang.
     */
    fun ensure(ref: ArtifactRef, onEvent: (String) -> Unit = {}): Path {
        val target = pathFor(ref)

        if (target.exists()) {
            onEvent("checking cached ${ref.fileName}")
            if (Digest.matches(target, ref.checksum)) return target
            // Either the file was truncated by a crash or something replaced it. Neither is worth
            // guessing about: it is not what the publisher signed, so it goes.
            onEvent("cached ${ref.fileName} no longer matches its ${ref.checksum.algorithm}; refetching")
            target.deleteIfExists()
        }

        if (offline) {
            throw DownloadFailed(
                "${ref.label} is not in the cache at $root and --offline was given, so it will not be " +
                    "downloaded.",
            )
        }

        val size = ref.sizeBytes?.let { " (%.1f MiB)".format(java.util.Locale.ROOT, it / MIB) } ?: ""
        onEvent("downloading ${ref.fileName}$size")
        http.download(ref.url, target, ref.checksum)
        onEvent("verified ${ref.checksum.algorithm} of ${ref.fileName}")
        return target
    }

    fun pathFor(ref: ArtifactRef): Path =
        root.resolve(ref.platform.id)
            .resolve("${ref.checksum.algorithm}-${ref.checksum.hex}")
            .resolve(ref.fileName)

    fun entries(): List<CachedJar> {
        if (!root.isDirectory()) return emptyList()
        val out = mutableListOf<CachedJar>()
        for (platformDir in root.listDirectoryEntries().filter { it.isDirectory() }) {
            for (digestDir in platformDir.listDirectoryEntries().filter { it.isDirectory() }) {
                val (algorithm, hex) = digestDir.name.split('-', limit = 2).let {
                    if (it.size == 2) it[0] to it[1] else "?" to digestDir.name
                }
                for (jar in digestDir.listDirectoryEntries().filter { it.isRegularFile() }) {
                    if (!jar.name.endsWith(".jar")) continue
                    out += CachedJar(
                        platform = platformDir.name,
                        checksum = Checksum(algorithm, hex),
                        fileName = jar.name,
                        path = jar,
                        sizeBytes = jar.fileSize(),
                    )
                }
            }
        }
        return out.sortedWith(compareBy({ it.platform }, { it.fileName }))
    }

    /** @return how many jars were removed. */
    fun clear(platform: Platform?): Int {
        val victims = entries().filter { platform == null || it.platform == platform.id }
        for (jar in victims) {
            jar.path.deleteIfExists()
            // Leave the tree tidy: an empty digest directory is not a cache entry.
            jar.path.parent?.takeIf { it.isDirectory() && it.listDirectoryEntries().isEmpty() }
                ?.deleteIfExists()
        }
        root.takeIf { it.isDirectory() }?.listDirectoryEntries()
            ?.filter { it.isDirectory() && it.listDirectoryEntries().isEmpty() }
            ?.forEach { it.deleteIfExists() }
        return victims.size
    }

    companion object {
        private const val MIB = (1L shl 20).toDouble()

        /**
         * `MCSTAGE_HOME` wins, so a CI runner can point the cache at a directory it already restores
         * between builds without every command growing a flag.
         */
        fun defaultRoot(): Path {
            val home = System.getenv("MCSTAGE_HOME")
            if (!home.isNullOrBlank()) return Paths.get(home).resolve("jars")
            return Paths.get(System.getProperty("user.home") ?: ".", ".mcstage", "jars")
        }

        /**
         * Puts the jar in the instance without spending a second copy of it where the filesystem
         * allows: a hard link is the same bytes under a second name, and the cache never writes to a
         * file in place, so nothing an instance does can corrupt the cached original.
         */
        fun link(source: Path, target: Path) {
            Files.createDirectories(target.parent)
            target.deleteIfExists()
            try {
                Files.createLink(target, source)
            } catch (_: Exception) {
                // Different volume, a filesystem without links, or Windows without the privilege.
                Files.copy(source, target)
            }
        }
    }
}
