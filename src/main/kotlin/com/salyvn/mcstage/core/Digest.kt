package com.salyvn.mcstage.core

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** The digest a publisher printed next to a file, carried around with the name of its algorithm. */
data class Checksum(val algorithm: String, val hex: String) {

    /** Directory-safe form, used as the cache key. */
    val cacheKey: String get() = "${algorithm.lowercase()}/$hex"

    override fun toString() = "${algorithm.lowercase()}:$hex"

    companion object {
        fun sha256(hex: String) = Checksum("sha256", hex.lowercase())
        fun md5(hex: String) = Checksum("md5", hex.lowercase())
    }
}

/**
 * Hashing, kept in one place because every jar this tool downloads is checked twice: once when it
 * arrives and again every time it is taken back out of the cache. A cache that is not re-verified is
 * just a directory of files somebody could have swapped.
 */
object Digest {

    fun of(path: Path, algorithm: String): String {
        val digest = MessageDigest.getInstance(canonical(algorithm))
        Files.newInputStream(path).use { stream ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toHex()
    }

    fun matches(path: Path, checksum: Checksum): Boolean =
        of(path, checksum.algorithm).equals(checksum.hex, ignoreCase = true)

    /** MessageDigest wants "SHA-256", publishers write "sha256". */
    private fun canonical(algorithm: String) = when (algorithm.lowercase()) {
        "sha256", "sha-256" -> "SHA-256"
        "sha1", "sha-1" -> "SHA-1"
        "md5" -> "MD5"
        else -> algorithm
    }

    private fun ByteArray.toHex(): String {
        val out = StringBuilder(size * 2)
        for (byte in this) {
            val value = byte.toInt() and 0xFF
            out.append(HEX[value ushr 4]).append(HEX[value and 0x0F])
        }
        return out.toString()
    }

    private const val HEX = "0123456789abcdef"
}
