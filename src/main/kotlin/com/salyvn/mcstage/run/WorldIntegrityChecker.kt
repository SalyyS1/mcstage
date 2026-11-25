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

    private fun escHtml(raw: String): String =
        raw.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    fun renderHtml(report: WorldIntegrityReport): String {
        val statusColor = if (report.ok) "#22c55e" else "#ef4444"
        val rows = if (report.issues.isEmpty()) {
            "<tr><td colspan=\"3\" style=\"color:#22c55e\">All world integrity checks passed (level.dat GZIP, session.lock, Anvil .mca 4096-byte sector headers).</td></tr>"
        } else {
            report.issues.joinToString("\n") { iss ->
                "<tr><td><b style=\"color:#ef4444\">${escHtml(iss.code)}</b></td><td><code>${escHtml(iss.path)}</code></td><td>${escHtml(iss.message)}</td></tr>"
            }
        }
        return buildString {
            append("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">")
            append("<title>mcstage World Anvil Integrity Report (MCS021)</title>")
            append("<style>body{margin:0;padding:28px;background:#0b0f17;color:#e6edf7;font-family:-apple-system,BlinkMacSystemFont,Segoe UI,sans-serif}.wrap{max-width:1040px;margin:0 auto}.card{background:#121826;border:1px solid #26324d;border-radius:10px;padding:18px;margin-bottom:16px}table{width:100%;border-collapse:collapse;font-size:13px}th,td{padding:8px 10px;border-bottom:1px solid #26324d;text-align:left}th{color:#93a4c3}code{font-family:ui-monospace,monospace;color:#38bdf8}</style>")
            append("</head><body><div class=\"wrap\"><div class=\"card\">")
            append("<h1>mcstage Anvil World Integrity Report (MCS021)</h1>")
            append("<p>Status: <b style=\"color:$statusColor\">${if (report.ok) "PASS" else "FAIL"}</b> &middot; World: <code>${escHtml(report.worldDir)}</code> &middot; level.dat: <b>${if (report.levelDatValid) "Valid GZIP" else "INVALID"}</b> &middot; session.lock: <b>${if (report.sessionLockPresent) "Present" else "Cleared"}</b> &middot; Regions: <b>${report.regionFilesScanned}</b> &middot; Valid Chunk Headers: <b>${report.validChunkHeaders}</b></p>")
            append("</div><div class=\"card\"><table><thead><tr><th>Code</th><th>Path</th><th>Detail</th></tr></thead><tbody>")
            append(rows)
            append("</tbody></table></div></div></body></html>")
        }
    }
}
