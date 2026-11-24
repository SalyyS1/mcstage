package com.salyvn.mcstage.run

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.fileSize
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

/**
 * Offline Minecraft world integrity validator (`MCS021`).
 *
 * Inspects a world directory (e.g. `world/`, `world_nether/`, `world_the_end/`) for:
 * - `level.dat` presence and valid GZIP magic header (`0x1F 0x8B`),
 * - stale `session.lock` checks (optional post-shutdown assertion),
 * - Anvil `.mca` region file header integrity: non-empty files must be at least 8192 bytes
 *   (location + timestamp tables) and a multiple of the 4096-byte Anvil sector size, with
 *   valid chunk sector offsets inside file bounds.
 */
object WorldIntegrityChecker {

    data class Issue(
        val path: String,
        val code: String,
        val message: String,
    )

    data class WorldIntegrityReport(
        val worldDir: String,
        val levelDatValid: Boolean,
        val sessionLockPresent: Boolean,
        val regionFilesScanned: Int,
        val validChunkHeaders: Int,
        val issues: List<Issue>,
    ) {
        val ok: Boolean get() = issues.isEmpty()
    }

    fun inspect(worldDir: Path, requireSessionLockCleared: Boolean = false): WorldIntegrityReport {
        val issues = mutableListOf<Issue>()
        if (!Files.isDirectory(worldDir)) {
            return WorldIntegrityReport(
                worldDir = worldDir.toString(),
                levelDatValid = false,
                sessionLockPresent = false,
                regionFilesScanned = 0,
                validChunkHeaders = 0,
                issues = listOf(Issue(worldDir.toString(), "MISSING_WORLD_DIR", "World directory does not exist.")),
            )
        }

        // 1. level.dat GZIP magic verification
        val levelDat = worldDir.resolve("level.dat")
        val levelDatValid = if (!levelDat.isRegularFile()) {
            issues += Issue(levelDat.toString(), "MISSING_LEVEL_DAT", "level.dat is missing from world directory.")
            false
        } else {
            val bytes = Files.readAllBytes(levelDat)
            val isGzip = bytes.size >= 18 && (bytes[0].toInt() and 0xFF) == 0x1F && (bytes[1].toInt() and 0xFF) == 0x8B
            if (!isGzip) {
                issues += Issue(
                    levelDat.toString(),
                    "CORRUPT_LEVEL_DAT",
                    "level.dat (${bytes.size} B) does not start with a valid GZIP NBT header (0x1F 0x8B).",
                )
            }
            isGzip
        }

        // 2. session.lock check
        val sessionLock = worldDir.resolve("session.lock")
        val sessionLockPresent = sessionLock.isRegularFile()
        if (requireSessionLockCleared && sessionLockPresent) {
            issues += Issue(
                sessionLock.toString(),
                "STALE_SESSION_LOCK",
                "session.lock still exists after shutdown; server may have crashed or hung during world save.",
            )
        }

        // 3. Anvil .mca region file header & 4096-byte sector alignment audit
        val regionDirs = listOf(
            worldDir.resolve("region"),
            worldDir.resolve("entities"),
            worldDir.resolve("poi"),
        ).filter { Files.isDirectory(it) }

        var regionFiles = 0
        var validChunkHeaders = 0

        for (rdir in regionDirs) {
            Files.list(rdir).use { stream ->
                stream.filter { it.isRegularFile() && it.extension.equals("mca", ignoreCase = true) }
                    .forEach { mca ->
                        regionFiles++
                        val size = mca.fileSize()
                        if (size == 0L) {
                            return@forEach // newly allocated empty region file is valid in Vanilla/Paper
                        }
                        if (size < 8192L) {
                            issues += Issue(
                                mca.toString(),
                                "TRUNCATED_ANVIL_HEADER",
                                "${mca.name} is only $size bytes (< 8192-byte Anvil location+timestamp header).",
                            )
                            return@forEach
                        }
                        if (size % 4096L != 0L) {
                            issues += Issue(
                                mca.toString(),
                                "UNALIGNED_ANVIL_SECTORS",
                                "${mca.name} size ($size B) is not a multiple of the 4096-byte Anvil sector size.",
                            )
                        }
                        val totalSectors = size / 4096L
                        val header = ByteArray(4096)
                        Files.newInputStream(mca).use { inp ->
                            var read = 0
                            while (read < 4096) {
                                val n = inp.read(header, read, 4096 - read)
                                if (n <= 0) break
                                read += n
                            }
                        }
                        for (idx in 0 until 1024) {
                            val base = idx * 4
                            val sectorOffset = ((header[base].toInt() and 0xFF) shl 16) or
                                ((header[base + 1].toInt() and 0xFF) shl 8) or
                                (header[base + 2].toInt() and 0xFF)
                            val sectorCount = header[base + 3].toInt() and 0xFF
                            if (sectorOffset == 0 && sectorCount == 0) continue
                            if (sectorOffset < 2 || sectorOffset.toLong() + sectorCount.toLong() > totalSectors) {
                                issues += Issue(
                                    mca.toString(),
                                    "OUT_OF_BOUNDS_CHUNK_SECTOR",
                                    "${mca.name} chunk slot #$idx points to sectors [$sectorOffset..${sectorOffset + sectorCount}] beyond $totalSectors total sectors.",
                                )
                                break
                            }
                            validChunkHeaders++
                        }
                    }
            }
        }

        return WorldIntegrityReport(
            worldDir = worldDir.toString(),
            levelDatValid = levelDatValid,
            sessionLockPresent = sessionLockPresent,
            regionFilesScanned = regionFiles,
            validChunkHeaders = validChunkHeaders,
            issues = issues,
        )
    }
}
