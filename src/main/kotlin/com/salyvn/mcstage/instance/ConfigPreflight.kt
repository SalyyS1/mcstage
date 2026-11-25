package com.salyvn.mcstage.instance

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * Static preflight auditor for Minecraft `server.properties` and JVM startup flags (`MCS022`).
 *
 * Catches common staging/production hazards before booting a Paper/Folia/Purpur JVM:
 *  - Exposed offline-mode backends (`online-mode=false` on wildcard bind)
 *  - Unprotected or weak RCON passwords
 *  - Blocking `sync-chunk-writes=true` I/O stalls
 *  - Excessive view/simulation distances
 *  - Out-of-range `network-compression-threshold`
 *  - Suboptimal or undersized JVM heap / GC flags
 */
object ConfigPreflight {

    data class Issue(
        val code: String,
        val ruleId: String = "MCS022",
        val severity: String, // "error" | "warning" | "note"
        val key: String,
        val message: String,
        val recommendation: String,
    )

    data class Report(
        val ok: Boolean,
        val score: Int,
        val grade: String,
        val propertiesParsed: Int,
        val jvmFlagsAnalyzed: Int,
        val issues: List<Issue>,
    )

    fun parseProperties(raw: String): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        for (line in raw.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("!")) continue
            val sep = trimmed.indexOfFirst { it == '=' || it == ':' }
            if (sep <= 0) continue
            val k = trimmed.substring(0, sep).trim().lowercase()
            val v = trimmed.substring(sep + 1).trim()
            map[k] = v
        }
        return map
    }

    fun auditDirectory(serverDir: Path, jvmFlags: List<String> = emptyList()): Report {
        val propsPath = serverDir.resolve("server.properties")
        val raw = if (Files.isRegularFile(propsPath)) {
            Files.readString(propsPath, StandardCharsets.UTF_8)
        } else {
            ""
        }
        return audit(raw, jvmFlags)
    }

    fun audit(serverPropertiesText: String, jvmFlags: List<String> = emptyList()): Report {
        val props = parseProperties(serverPropertiesText)
        val issues = mutableListOf<Issue>()

        val onlineMode = props["online-mode"]?.lowercase()
        val serverIp = props["server-ip"]?.trim().orEmpty()
        val isLoopback = serverIp == "127.0.0.1" || serverIp == "::1" || serverIp.equals("localhost", ignoreCase = true)

        if (onlineMode == "false" && !isLoopback) {
            issues += Issue(
                code = "CFG-001",
                severity = "error",
                key = "online-mode",
                message = "online-mode=false while server-ip='${serverIp.ifEmpty { "0.0.0.0" }}' is not loopback; unauthenticated clients can spoof UUIDs if proxy firewalling is absent.",
                recommendation = "Bind server-ip=127.0.0.1 behind Velocity/BungeeCord or keep online-mode=true for public nodes.",
            )
        }

        val rconEnabled = props["enable-rcon"]?.lowercase() == "true"
        val rconPass = props["rcon.password"].orEmpty()
        if (rconEnabled) {
            if (rconPass.length < 10 || rconPass.equals("password", ignoreCase = true) || rconPass == "123456") {
                issues += Issue(
                    code = "CFG-002",
                    severity = "error",
                    key = "rcon.password",
                    message = "enable-rcon=true with a missing, short (<10 chars), or trivial rcon.password.",
                    recommendation = "Set a >=16-character random rcon.password and restrict rcon.port to 127.0.0.1.",
                )
            } else if (!isLoopback) {
                issues += Issue(
                    code = "CFG-002",
                    severity = "warning",
                    key = "enable-rcon",
                    message = "RCON is enabled on non-loopback server-ip='${serverIp.ifEmpty { "0.0.0.0" }}'. Source RCON is unencrypted plaintext.",
                    recommendation = "Firewall rcon.port at the OS/VPS layer or bind RCON strictly to loopback + SSH tunnel.",
                )
            }
        }

        if (props["sync-chunk-writes"]?.lowercase() == "true") {
            issues += Issue(
                code = "CFG-003",
                severity = "note",
                key = "sync-chunk-writes",
                message = "sync-chunk-writes=true forces synchronous fsync on every region chunk write, causing tick spikes on busy disks.",
                recommendation = "Set sync-chunk-writes=false on Paper/Purpur/Folia unless explicitly running crash-durability tests.",
            )
        }

        val viewDist = props["view-distance"]?.toIntOrNull()
        val simDist = props["simulation-distance"]?.toIntOrNull()
        if (viewDist != null && viewDist > 12) {
            issues += Issue(
                code = "CFG-004",
                severity = "warning",
                key = "view-distance",
                message = "view-distance=$viewDist exceeds 12 chunks, multiplying chunk memory and bandwidth quadratically.",
                recommendation = "Keep view-distance between 8 and 10 (or offload distant terrain via Chunky/Distant Horizons).",
            )
        }
        if (simDist != null && simDist > 10) {
            issues += Issue(
                code = "CFG-004",
                severity = "warning",
                key = "simulation-distance",
                message = "simulation-distance=$simDist exceeds 10 chunks, inflating entity/redstone tick load.",
                recommendation = "Keep simulation-distance between 5 and 8 for stable 20 TPS under multi-player load.",
            )
        }

        val netComp = props["network-compression-threshold"]?.toIntOrNull()
        if (netComp != null && (netComp < -1 || netComp > 512)) {
            issues += Issue(
                code = "CFG-005",
                severity = "warning",
                key = "network-compression-threshold",
                message = "network-compression-threshold=$netComp is outside the recommended [-1, 512] range.",
                recommendation = "Use 256 for standalone servers or -1 when sitting directly behind a local Velocity proxy.",
            )
        }

        if (jvmFlags.isNotEmpty()) {
            val hasModernGc = jvmFlags.any {
                it.contains("UseG1GC", ignoreCase = true) ||
                    it.contains("UseZGC", ignoreCase = true) ||
                    it.contains("UseShenandoahGC", ignoreCase = true)
            }
            if (!hasModernGc) {
                issues += Issue(
                    code = "CFG-006",
                    severity = "warning",
                    key = "jvm.gc",
                    message = "JVM flags do not explicitly select G1GC, Generational ZGC, or ShenandoahGC.",
                    recommendation = "Add -XX:+UseG1GC (Aikar's flags) or -XX:+UseZGC -XX:+ZGenerational on Java 21+.",
                )
            }
            val xmxFlag = jvmFlags.firstOrNull { it.startsWith("-Xmx") }
            val xmsFlag = jvmFlags.firstOrNull { it.startsWith("-Xms") }
            val xmxMb = xmxFlag?.let { parseJvmMemMb(it.removePrefix("-Xmx")) }
            val xmsMb = xmsFlag?.let { parseJvmMemMb(it.removePrefix("-Xms")) }
            if (xmxMb != null && xmxMb < 1024) {
                issues += Issue(
                    code = "CFG-006",
                    severity = "error",
                    key = "jvm.xmx",
                    message = "Maximum heap $xmxFlag ($xmxMb MB) is below 1024 MB; modern Paper/Folia servers risk immediate OOM.",
                    recommendation = "Allocate at least -Xms1G -Xmx1G for CI staging or >=4G for production survival worlds.",
                )
            } else if (xmxMb != null && xmsMb != null && xmsMb != xmxMb) {
                issues += Issue(
                    code = "CFG-006",
                    severity = "note",
                    key = "jvm.xms",
                    message = "Initial heap ($xmsFlag) differs from max heap ($xmxFlag), which can trigger GC pause spikes during heap expansion.",
                    recommendation = "Match -Xms and -Xmx (e.g., -Xms2G -Xmx2G) to prevent dynamic heap resize stalls.",
                )
            }
        }

        val penalty = issues.sumOf {
            when (it.severity) {
                "error" -> 25
                "warning" -> 12
                else -> 4
            }
        }
        val score = (100 - penalty).coerceIn(0, 100)
        val grade = when (score) {
            in 90..100 -> "A"
            in 78..89 -> "B"
            in 62..77 -> "C"
            in 45..61 -> "D"
            else -> "F"
        }
        val ok = issues.none { it.severity == "error" }

        return Report(
            ok = ok,
            score = score,
            grade = grade,
            propertiesParsed = props.size,
            jvmFlagsAnalyzed = jvmFlags.size,
            issues = issues,
        )
    }

    private fun parseJvmMemMb(raw: String): Long? {
        val s = raw.trim().lowercase()
        if (s.isEmpty()) return null
        return when {
            s.endsWith("g") -> s.dropLast(1).toLongOrNull()?.times(1024L)
            s.endsWith("m") -> s.dropLast(1).toLongOrNull()
            s.endsWith("k") -> s.dropLast(1).toLongOrNull()?.div(1024L)
            else -> s.toLongOrNull()?.div(1024L * 1024L)
        }
    }

    fun toJson(report: Report): JsonObject = buildJsonObject {
        put("ok", report.ok)
        put("score", report.score)
        put("grade", report.grade)
        put("properties_parsed", report.propertiesParsed)
        put("jvm_flags_analyzed", report.jvmFlagsAnalyzed)
        put("issue_count", report.issues.size)
        putJsonArray("issues") {
            for (iss in report.issues) {
                add(buildJsonObject {
                    put("code", iss.code)
                    put("ruleId", iss.ruleId)
                    put("severity", iss.severity)
                    put("key", iss.key)
                    put("message", iss.message)
                    put("recommendation", iss.recommendation)
                })
            }
        }
    }

    private fun htmlEsc(raw: String): String = raw
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    /** Render a self-contained dark-mode HTML Server Config Preflight Report (`preflight --html`). */
    fun renderHtml(report: Report): String {
        val statusCol = if (report.ok) "#22c55e" else "#ef4444"
        val rows = if (report.issues.isEmpty()) {
            """<tr><td colspan="5" style="color:#22c55e"><b>PASS &mdash; Zero MCS022 configuration or JVM flag hazards found.</b></td></tr>"""
        } else {
            report.issues.joinToString("\n") { iss ->
                val c = if (iss.severity.equals("error", ignoreCase = true)) "#ef4444" else "#f59e0b"
                """<tr><td><code>${htmlEsc(iss.code)}</code></td><td style="color:$c"><b>${htmlEsc(iss.severity.uppercase())}</b></td><td><code>${htmlEsc(iss.key)}</code></td><td>${htmlEsc(iss.message)}</td><td>${htmlEsc(iss.recommendation)}</td></tr>"""
            }
        }
        return """<!doctype html>
<html lang="en"><head><meta charset="utf-8"><title>mcstage Config Preflight Report (Grade ${htmlEsc(report.grade)})</title>
<style>
body{margin:0;padding:28px;background:#0b0f17;color:#e6edf7;font-family:system-ui,sans-serif}
.wrap{max-width:1040px;margin:0 auto}.card{background:#121826;border:1px solid #26324d;border-radius:10px;padding:18px;margin-bottom:16px}
table{width:100%;border-collapse:collapse;font-size:12.5px}th,td{padding:8px 10px;border-bottom:1px solid #1e293b;text-align:left;vertical-align:top}
th{color:#93a4c3}code{font-family:ui-monospace,monospace;color:#38bdf8}
</style></head><body><div class="wrap">
<div class="card"><h1>mcstage Server Config &amp; JVM Preflight Report (MCS022)</h1>
<p>Grade: <b style="color:$statusCol">${htmlEsc(report.grade)} (${report.score}/100)</b> &middot; Verdict: <b style="color:$statusCol">${if (report.ok) "PASS" else "FAIL"}</b> &middot; Properties Parsed: <b>${report.propertiesParsed}</b> &middot; JVM Flags: <b>${report.jvmFlagsAnalyzed}</b></p></div>
<div class="card"><table><thead><tr><th>Code</th><th>Severity</th><th>Key</th><th>Finding</th><th>Recommendation</th></tr></thead>
<tbody>$rows</tbody></table></div></div></body></html>"""
    }
}
