package com.salyvn.mcstage.instance

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile

/**
 * Pre-boot Minecraft Plugin JAR Manifest & Bytecode Inspector (`MCS023`).
 *
 * Inspects `paper-plugin.yml` and/or `plugin.yml` inside a `.jar` archive before booting Paper/Folia:
 *  - Verifies descriptor exists and parses cleanly via SafeConstructor
 *  - Verifies the declared `main` class `.class` entry actually exists inside the JAR
 *  - Reads the JVM classfile major version (bytes 6..7) to catch UnsupportedClassVersionError early
 *  - Checks `folia-supported` and `api-version` readiness
 */
object PluginJarInspector {

    data class Issue(
        val code: String,
        val ruleId: String = "MCS023",
        val severity: String,
        val message: String,
    )

    data class InspectionReport(
        val ok: Boolean,
        val jarPath: String,
        val descriptorType: String,
        val pluginName: String?,
        val pluginVersion: String?,
        val mainClass: String?,
        val mainClassPresent: Boolean,
        val mainClassJavaMajor: Int?,
        val apiVersion: String?,
        val foliaSupported: Boolean,
        val dependencies: List<String>,
        val issues: List<Issue>,
    )

    fun inspect(jarPath: Path, targetFolia: Boolean = false, hostJavaMajor: Int = Runtime.version().feature()): InspectionReport {
        val abs = jarPath.toAbsolutePath().normalize()
        if (!Files.isRegularFile(abs)) {
            return InspectionReport(
                ok = false,
                jarPath = abs.toString(),
                descriptorType = "none",
                pluginName = null,
                pluginVersion = null,
                mainClass = null,
                mainClassPresent = false,
                mainClassJavaMajor = null,
                apiVersion = null,
                foliaSupported = false,
                dependencies = emptyList(),
                issues = listOf(Issue("JAR-000", severity = "error", message = "JAR file not found: $abs")),
            )
        }

        val issues = mutableListOf<Issue>()
        var descriptorType = "none"
        var rawYaml: String? = null
        var mainClassPresent = false
        var mainClassJavaMajor: Int? = null
        var parsed: Map<*, *>? = null

        ZipFile(abs.toFile()).use { zip ->
            val paperEntry = zip.getEntry("paper-plugin.yml")
            val bukkitEntry = zip.getEntry("plugin.yml")
            val chosen = paperEntry ?: bukkitEntry
            descriptorType = when {
                paperEntry != null && bukkitEntry != null -> "paper-plugin.yml+plugin.yml"
                paperEntry != null -> "paper-plugin.yml"
                bukkitEntry != null -> "plugin.yml"
                else -> "none"
            }

            if (chosen == null) {
                issues += Issue("JAR-001", severity = "error", message = "JAR contains neither paper-plugin.yml nor plugin.yml.")
            } else {
                rawYaml = zip.getInputStream(chosen).use { it.readBytes().toString(StandardCharsets.UTF_8) }
                try {
                    val loader = Yaml(SafeConstructor(LoaderOptions()))
                    val loaded = loader.load<Any?>(rawYaml)
                    if (loaded is Map<*, *>) {
                        parsed = loaded
                    } else {
                        issues += Issue("JAR-001", severity = "error", message = "$descriptorType root must be a YAML mapping.")
                    }
                } catch (e: Exception) {
                    issues += Issue("JAR-001", severity = "error", message = "Malformed YAML in $descriptorType: ${e.message}")
                }
            }

            val mainClass = parsed?.get("main")?.toString()?.trim()?.ifEmpty { null }
            if (mainClass != null) {
                val classEntryName = mainClass.replace('.', '/') + ".class"
                val clsEntry = zip.getEntry(classEntryName)
                if (clsEntry == null) {
                    issues += Issue("JAR-002", severity = "error", message = "Declared main class '$mainClass' ($classEntryName) is missing from the JAR.")
                } else {
                    mainClassPresent = true
                    val header = ByteArray(8)
                    val read = zip.getInputStream(clsEntry).use { it.read(header) }
                    if (read == 8 && header[0] == 0xCA.toByte() && header[1] == 0xFE.toByte() && header[2] == 0xBA.toByte() && header[3] == 0xBE.toByte()) {
                        val majorBytecode = ((header[6].toInt() and 0xFF) shl 8) or (header[7].toInt() and 0xFF)
                        val javaFeature = (majorBytecode - 44).coerceAtLeast(1)
                        mainClassJavaMajor = javaFeature
                        if (javaFeature > hostJavaMajor) {
                            issues += Issue(
                                "JAR-004",
                                severity = "error",
                                message = "Main class bytecode targets Java $javaFeature (classfile $majorBytecode), exceeding host JVM Java $hostJavaMajor.",
                            )
                        }
                    }
                }
            } else if (chosen != null) {
                issues += Issue("JAR-002", severity = "error", message = "Descriptor is missing required 'main' class field.")
            }
        }

        val pluginName = parsed?.get("name")?.toString()?.trim()
        val pluginVersion = parsed?.get("version")?.toString()?.trim()
        val mainClass = parsed?.get("main")?.toString()?.trim()
        val apiVersion = parsed?.get("api-version")?.toString()?.trim()
        val foliaSupported = parsed?.get("folia-supported")?.toString()?.equals("true", ignoreCase = true) == true
        val deps = extractDeps(parsed)

        if (parsed != null && apiVersion.isNullOrBlank()) {
            issues += Issue("JAR-005", severity = "warning", message = "Missing 'api-version' in $descriptorType; Paper may treat the plugin as legacy 1.13 material-compat mode.")
        }
        if (targetFolia && parsed != null && !foliaSupported) {
            issues += Issue("JAR-003", severity = "error", message = "Target platform includes Folia, but descriptor does not declare 'folia-supported: true'.")
        }

        val ok = issues.none { it.severity == "error" }
        return InspectionReport(
            ok = ok,
            jarPath = abs.toString(),
            descriptorType = descriptorType,
            pluginName = pluginName,
            pluginVersion = pluginVersion,
            mainClass = mainClass,
            mainClassPresent = mainClassPresent,
            mainClassJavaMajor = mainClassJavaMajor,
            apiVersion = apiVersion,
            foliaSupported = foliaSupported,
            dependencies = deps,
            issues = issues,
        )
    }

    private fun extractDeps(parsed: Map<*, *>?): List<String> {
        if (parsed == null) return emptyList()
        val out = LinkedHashSet<String>()
        for (key in listOf("depend", "softdepend")) {
            val v = parsed[key]
            if (v is Iterable<*>) {
                for (item in v) item?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out.add(it) }
            }
        }
        val paperDeps = parsed["dependencies"] as? Map<*, *>
        val serverDeps = paperDeps?.get("server") as? Map<*, *>
        if (serverDeps != null) {
            for (k in serverDeps.keys) k?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out.add(it) }
        }
        return out.toList()
    }

    fun toJson(rep: InspectionReport): JsonObject = buildJsonObject {
        put("ok", rep.ok)
        put("jar_path", rep.jarPath)
        put("descriptor_type", rep.descriptorType)
        rep.pluginName?.let { put("plugin_name", it) }
        rep.pluginVersion?.let { put("plugin_version", it) }
        rep.mainClass?.let { put("main_class", it) }
        put("main_class_present", rep.mainClassPresent)
        rep.mainClassJavaMajor?.let { put("main_class_java_major", it) }
        rep.apiVersion?.let { put("api_version", it) }
        put("folia_supported", rep.foliaSupported)
        putJsonArray("dependencies") { rep.dependencies.forEach { add(JsonPrimitive(it)) } }
        put("issue_count", rep.issues.size)
        putJsonArray("issues") {
            for (iss in rep.issues) {
                add(buildJsonObject {
                    put("code", iss.code)
                    put("ruleId", iss.ruleId)
                    put("severity", iss.severity)
                    put("message", iss.message)
                })
            }
        }
    }

    private fun htmlEsc(raw: String): String = raw
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    /** Render a self-contained dark-mode HTML Plugin JAR Inspection Report (`inspect-jar --html`). */
    fun renderHtml(rep: InspectionReport): String {
        val statusCol = if (rep.ok) "#22c55e" else "#ef4444"
        val issuesHtml = if (rep.issues.isEmpty()) {
            """<tr><td colspan="4" style="color:#22c55e"><b>PASS &mdash; Zero MCS023 packaging or bytecode issues detected.</b></td></tr>"""
        } else {
            rep.issues.joinToString("\n") { iss ->
                val c = if (iss.severity.equals("error", ignoreCase = true)) "#ef4444" else "#f59e0b"
                """<tr><td><code>${htmlEsc(iss.code)}</code></td><td><code>${htmlEsc(iss.ruleId)}</code></td><td style="color:$c"><b>${htmlEsc(iss.severity.uppercase())}</b></td><td>${htmlEsc(iss.message)}</td></tr>"""
            }
        }
        return """<!doctype html>
<html lang="en"><head><meta charset="utf-8"><title>mcstage Plugin JAR Inspection (${htmlEsc(rep.pluginName ?: "plugin")})</title>
<style>
body{margin:0;padding:28px;background:#0b0f17;color:#e6edf7;font-family:system-ui,sans-serif}
.wrap{max-width:960px;margin:0 auto}.card{background:#121826;border:1px solid #26324d;border-radius:10px;padding:18px;margin-bottom:16px}
table{width:100%;border-collapse:collapse;font-size:13px}th,td{padding:8px 10px;border-bottom:1px solid #1e293b;text-align:left}
th{color:#93a4c3}code{font-family:ui-monospace,monospace;color:#38bdf8}
</style></head><body><div class="wrap">
<div class="card"><h1>mcstage Pre-Boot Plugin JAR Audit (MCS023)</h1>
<p>Status: <b style="color:$statusCol">${if (rep.ok) "PASS" else "FAIL"}</b> &middot; Plugin: <b>${htmlEsc(rep.pluginName ?: "unknown")} ${htmlEsc(rep.pluginVersion ?: "")}</b> &middot; Descriptor: <code>${htmlEsc(rep.descriptorType)}</code> &middot; Java Bytecode: <b>${rep.mainClassJavaMajor ?: "?"}</b> &middot; Folia Supported: <b>${rep.foliaSupported}</b></p>
<p>JAR: <code>${htmlEsc(rep.jarPath)}</code> &middot; Main Class: <code>${htmlEsc(rep.mainClass ?: "(missing)")}</code> (present=${rep.mainClassPresent})</p></div>
<div class="card"><table><thead><tr><th>Code</th><th>Rule</th><th>Severity</th><th>Message</th></tr></thead>
<tbody>$issuesHtml</tbody></table></div></div></body></html>"""
    }
}
