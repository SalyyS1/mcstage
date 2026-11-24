package com.salyvn.mcstage.core

import com.salyvn.mcstage.cache.JarCache
import com.salyvn.mcstage.run.ScenarioFile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.exists

/**
 * Self-diagnostic environment check (`mcstage doctor` and `mcstage_doctor` MCP tool).
 *
 * Verifies Java runtime (>= 21), JAR cache health, loopback port availability, optional scenario
 * file validity, and optional loopback `mckit-bridge-plugin` reachability.
 */
object DoctorCheck {

    data class Report(
        val ok: Boolean,
        val mcstageVersion: String,
        val javaOk: Boolean,
        val javaVersion: String,
        val javaBinary: String,
        val portChecked: Int,
        val portAvailable: Boolean,
        val cacheDir: String,
        val cachedJarCount: Int,
        val cachedTotalBytes: Long,
        val scenarioPath: String?,
        val scenarioValid: Boolean?,
        val scenarioDetail: String?,
        val bridgeUrl: String?,
        val bridgeReachable: Boolean?,
    )

    private val prettyJson = Json { prettyPrint = true }

    fun run(
        javaOverride: String? = null,
        port: Int = 25565,
        scenarioFile: Path? = null,
        bridgeUrl: String? = null,
        cacheRoot: Path = JarCache.defaultRoot(),
    ): Report {
        val javaInfo = try {
            val rt = JavaRuntime.detect(javaOverride)
            val major = rt.major ?: 0
            Triple(major >= 21, rt.describe, rt.binary.toString())
        } catch (e: Exception) {
            Triple(false, e.message ?: "Java not found", javaOverride ?: "java")
        }

        val portFree = PortProbe.isBindable(port = port)

        val (jarCount, jarBytes) = scanCache(cacheRoot)

        var scenValid: Boolean? = null
        var scenDetail: String? = null
        if (scenarioFile != null) {
            if (scenarioFile.exists()) {
                try {
                    val sc = ScenarioFile.read(scenarioFile)
                    scenValid = true
                    scenDetail = "${sc.name} (${sc.cases.size} case(s), ${sc.steps.size} step(s))"
                } catch (e: Exception) {
                    scenValid = false
                    scenDetail = e.message ?: e::class.simpleName
                }
            } else {
                scenValid = false
                scenDetail = "Scenario file not found: $scenarioFile"
            }
        }

        val bridgeOk = bridgeUrl?.takeIf { it.isNotBlank() }?.let { probeBridge(it) }

        val overallOk = javaInfo.first && (scenValid != false)

        return Report(
            ok = overallOk,
            mcstageVersion = BuildInfo.version,
            javaOk = javaInfo.first,
            javaVersion = javaInfo.second,
            javaBinary = javaInfo.third,
            portChecked = port,
            portAvailable = portFree,
            cacheDir = cacheRoot.toString(),
            cachedJarCount = jarCount,
            cachedTotalBytes = jarBytes,
            scenarioPath = scenarioFile?.toString(),
            scenarioValid = scenValid,
            scenarioDetail = scenDetail,
            bridgeUrl = bridgeUrl?.takeIf { it.isNotBlank() },
            bridgeReachable = bridgeOk,
        )
    }

    private fun scanCache(root: Path): Pair<Int, Long> {
        if (!Files.isDirectory(root)) return 0 to 0L
        var count = 0
        var bytes = 0L
        Files.walk(root, 4).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".jar") }
                .forEach { p ->
                    count++
                    bytes += runCatching { Files.size(p) }.getOrDefault(0L)
                }
        }
        return count to bytes
    }

    private fun probeBridge(baseUrl: String): Boolean = try {
        val client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(600))
            .build()
        val req = HttpRequest.newBuilder()
            .uri(URI.create("${baseUrl.trimEnd('/')}/api/v1/health"))
            .timeout(Duration.ofMillis(600))
            .GET()
            .build()
        val resp = client.send(req, HttpResponse.BodyHandlers.discarding())
        resp.statusCode() in 200..299 || resp.statusCode() == 401
    } catch (_: Exception) {
        false
    }

    fun toJson(r: Report): JsonObject = buildJsonObject {
        put("ok", r.ok)
        put("mcstage_version", r.mcstageVersion)
        putJsonObject("java") {
            put("ok", r.javaOk)
            put("version", r.javaVersion)
            put("binary", r.javaBinary)
        }
        putJsonObject("port") {
            put("checked", r.portChecked)
            put("available", r.portAvailable)
        }
        putJsonObject("cache") {
            put("dir", r.cacheDir)
            put("jar_count", r.cachedJarCount)
            put("total_bytes", r.cachedTotalBytes)
        }
        r.scenarioPath?.let { path ->
            putJsonObject("scenario") {
                put("path", path)
                put("valid", r.scenarioValid ?: false)
                r.scenarioDetail?.let { put("detail", it) }
            }
        }
        r.bridgeUrl?.let { url ->
            putJsonObject("bridge") {
                put("url", url)
                put("reachable", r.bridgeReachable ?: false)
            }
        }
    }

    fun renderJson(r: Report): String = prettyJson.encodeToString(JsonObject.serializer(), toJson(r))

    fun renderText(r: Report): String = buildString {
        appendLine("mcstage doctor (v${r.mcstageVersion}) — ${if (r.ok) "READY" else "ISSUES FOUND"}")
        appendLine("  [${if (r.javaOk) "OK" else "FAIL"}] Java:  ${r.javaVersion} (${r.javaBinary})")
        appendLine("  [${if (r.portAvailable) "OK" else "BUSY"}] Port:  127.0.0.1:${r.portChecked} (${if (r.portAvailable) "free" else "in use; mcstage will auto-allocate"})")
        appendLine("  [INFO] Cache: ${r.cachedJarCount} jar(s), ${r.cachedTotalBytes / (1024 * 1024)} MiB at ${r.cacheDir}")
        r.scenarioPath?.let {
            appendLine("  [${if (r.scenarioValid == true) "OK" else "FAIL"}] Scenario: $it — ${r.scenarioDetail.orEmpty()}")
        }
        r.bridgeUrl?.let {
            appendLine("  [${if (r.bridgeReachable == true) "OK" else "OFFLINE"}] Bridge: $it")
        }
    }

    private fun htmlEsc(raw: String): String = raw
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    /** Render a self-contained dark-mode HTML Environment Readiness Report for `mcstage doctor --html`. */
    fun renderHtml(r: Report): String {
        val statusColor = if (r.ok) "#22c55e" else "#ef4444"
        val statusText = if (r.ok) "READY" else "ISSUES FOUND"
        val scenRow = r.scenarioPath?.let { path ->
            val ok = r.scenarioValid == true
            """<tr><td>Scenario YAML</td><td style="color:${if (ok) "#22c55e" else "#ef4444"}"><b>${if (ok) "VALID" else "INVALID"}</b></td><td><code>${htmlEsc(path)}</code> &mdash; ${htmlEsc(r.scenarioDetail.orEmpty())}</td></tr>"""
        }.orEmpty()
        val bridgeRow = r.bridgeUrl?.let { url ->
            val ok = r.bridgeReachable == true
            """<tr><td>mckit-bridge-plugin</td><td style="color:${if (ok) "#22c55e" else "#f59e0b"}"><b>${if (ok) "REACHABLE" else "OFFLINE"}</b></td><td><code>${htmlEsc(url)}</code></td></tr>"""
        }.orEmpty()
        return """<!doctype html>
<html lang="en"><head><meta charset="utf-8"><title>mcstage doctor report (v${htmlEsc(r.mcstageVersion)})</title>
<style>
body{margin:0;padding:28px;background:#0b0f17;color:#e6edf7;font-family:system-ui,sans-serif}
.wrap{max-width:920px;margin:0 auto}.card{background:#121826;border:1px solid #26324d;border-radius:10px;padding:18px;margin-bottom:16px}
table{width:100%;border-collapse:collapse;font-size:13px}th,td{padding:9px 10px;border-bottom:1px solid #1e293b;text-align:left}
th{color:#93a4c3}code{font-family:ui-monospace,monospace;color:#38bdf8}
</style></head><body><div class="wrap">
<div class="card"><h1>mcstage Environment Readiness Report</h1>
<p>Version: <code>v${htmlEsc(r.mcstageVersion)}</code> &middot; Status: <b style="color:$statusColor">$statusText</b></p></div>
<div class="card"><table><thead><tr><th>Check</th><th>Status</th><th>Detail</th></tr></thead><tbody>
<tr><td>Java Runtime (&ge; 21)</td><td style="color:${if (r.javaOk) "#22c55e" else "#ef4444"}"><b>${if (r.javaOk) "OK" else "FAIL"}</b></td><td>${htmlEsc(r.javaVersion)} (<code>${htmlEsc(r.javaBinary)}</code>)</td></tr>
<tr><td>Loopback Port</td><td style="color:${if (r.portAvailable) "#22c55e" else "#f59e0b"}"><b>${if (r.portAvailable) "FREE" else "BUSY"}</b></td><td><code>127.0.0.1:${r.portChecked}</code></td></tr>
<tr><td>Server JAR Cache</td><td style="color:#38bdf8"><b>${r.cachedJarCount} JAR(s)</b></td><td>${r.cachedTotalBytes / (1024 * 1024)} MiB at <code>${htmlEsc(r.cacheDir)}</code></td></tr>
$scenRow
$bridgeRow
</tbody></table></div></div></body></html>"""
    }
}
