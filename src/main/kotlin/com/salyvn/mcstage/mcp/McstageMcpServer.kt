package com.salyvn.mcstage.mcp

import com.salyvn.mcstage.core.BuildInfo
import com.salyvn.mcstage.report.ReportComparator
import com.salyvn.mcstage.report.SarifRunReporter
import com.salyvn.mcstage.run.BarrierStallAnalyzer
import com.salyvn.mcstage.run.CaseResult
import com.salyvn.mcstage.run.CrashAnalyzer
import com.salyvn.mcstage.run.FoliaCrashRecoveryAnalyzer
import com.salyvn.mcstage.run.FoliaRegionTopology
import com.salyvn.mcstage.run.HeapUsageAnalyzer
import com.salyvn.mcstage.run.LogLine
import com.salyvn.mcstage.run.Outcome
import com.salyvn.mcstage.run.RunReport
import com.salyvn.mcstage.run.Scenario
import com.salyvn.mcstage.run.ScenarioFile
import com.salyvn.mcstage.core.Platform
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.time.Duration
import java.time.Instant

/**
 * Zero-extra-dependency JSON-RPC 2.0 stdio MCP server for `mcstage`.
 *
 * Supports both newline-delimited JSON-RPC and `Content-Length` framed messages so AI coding
 * agents (Claude Code, Cursor, Windsurf, Codex, Antigravity) can validate scenarios, analyze
 * Paper/Folia/Purpur logs with `MCS001`-`MCS019` + SARIF v2.1.0, plan Folia region topologies,
 * and scaffold integration scenarios directly from chat.
 */
class McstageMcpServer {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    private val prettyJson = Json { prettyPrint = true }

    fun serve(input: InputStream, output: OutputStream) {
        val buffered = BufferedInputStream(input)
        while (true) {
            val frame = readMessage(buffered) ?: break
            val response = handleMessage(frame.first)
            if (response != null) {
                writeMessage(output, response, frame.second)
            }
        }
    }

    fun handleMessage(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val obj = try {
            json.parseToJsonElement(trimmed).jsonObject
        } catch (e: Exception) {
            return errorResponse(JsonNull, -32700, "Malformed JSON-RPC payload: ${e.message}")
        }
        val id = obj["id"]
        val method = obj["method"]?.jsonPrimitive?.contentOrNull
            ?: return errorResponse(id ?: JsonNull, -32600, "Missing JSON-RPC method.")
        val params = obj["params"] as? JsonObject ?: buildJsonObject {}

        if (id == null) {
            // Notification (e.g. notifications/initialized) -> no response
            return null
        }

        return when (method) {
            "initialize" -> resultResponse(
                id,
                buildJsonObject {
                    put("protocolVersion", "2025-06-18")
                    putJsonObject("serverInfo") {
                        put("name", BuildInfo.NAME)
                        put("version", BuildInfo.version)
                    }
                    putJsonObject("capabilities") {
                        putJsonObject("tools") { put("listChanged", false) }
                    }
                },
            )

            "ping" -> resultResponse(id, buildJsonObject {})

            "tools/list" -> resultResponse(id, buildJsonObject { put("tools", toolDefinitions()) })

            "tools/call" -> handleToolCall(id, params)

            else -> errorResponse(id, -32601, "Method not found: $method")
        }
    }

    private fun handleToolCall(id: JsonElement, params: JsonObject): String {
        val name = params["name"]?.jsonPrimitive?.contentOrNull
            ?: return errorResponse(id, -32602, "Missing string 'name' in tools/call.")
        val args = params["arguments"] as? JsonObject ?: buildJsonObject {}

        return try {
            val payload = when (name) {
                "mcstage_validate_scenario" -> toolValidateScenario(args)
                "mcstage_analyze_log" -> toolAnalyzeLog(args)
                "mcstage_plan_folia_topology" -> toolPlanFoliaTopology(args)
                "mcstage_generate_scenario" -> toolGenerateScenario(args)
                "mcstage_compare_reports" -> toolCompareReports(args)
                "mcstage_check_world_integrity" -> toolCheckWorldIntegrity(args)
                "mcstage_doctor" -> toolDoctor(args)
                "mcstage_probe_server" -> toolProbeServer(args)
                "mcstage_aggregate_junit" -> toolAggregateJunit(args)
                "mcstage_audit_server_config" -> toolAuditServerConfig(args)
                "mcstage_inspect_plugin_jar" -> toolInspectPluginJar(args)
                "mcstage_list_rules" -> toolListRules()
                else -> return errorResponse(id, -32602, "Unknown tool: $name")
            }
            resultResponse(
                id,
                buildJsonObject {
                    putJsonArray("content") {
                        add(buildJsonObject {
                            put("type", "text")
                            put("text", prettyJson.encodeToString(JsonObject.serializer(), payload))
                        })
                    }
                    put("isError", false)
                },
            )
        } catch (e: Exception) {
            resultResponse(
                id,
                buildJsonObject {
                    putJsonArray("content") {
                        add(buildJsonObject {
                            put("type", "text")
                            put("text", "${e::class.simpleName}: ${e.message}")
                        })
                    }
                    put("isError", true)
                },
            )
        }
    }

    private fun toolValidateScenario(args: JsonObject): JsonObject {
        val yamlText = args["yaml"]?.jsonPrimitive?.contentOrNull
            ?: throw IllegalArgumentException("Missing required 'yaml' string.")
        val temp = Files.createTempFile("mcstage-mcp-", ".yml")
        return try {
            Files.writeString(temp, yamlText, StandardCharsets.UTF_8)
            val scenario = ScenarioFile.read(temp)
            buildJsonObject {
                put("valid", true)
                put("name", scenario.name)
                put("case_count", scenario.cases.size)
                put("step_count", scenario.steps.size)
                put("crash_guard", scenario.crashGuard)
                putJsonArray("matrix") {
                    for (c in scenario.cases) {
                        add(buildJsonObject {
                            put("platform", c.platform.id)
                            put("version", c.version)
                        })
                    }
                }
                putJsonArray("steps") {
                    for (s in scenario.steps) add(JsonPrimitive(s.describe))
                }
            }
        } catch (e: Exception) {
            buildJsonObject {
                put("valid", false)
                put("error", e.message ?: e::class.simpleName ?: "Validation failed")
            }
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    private fun toolAnalyzeLog(args: JsonObject): JsonObject {
        val logText = args["log"]?.jsonPrimitive?.contentOrNull
            ?: throw IllegalArgumentException("Missing required 'log' string.")
        val includeSarif = args["sarif"]?.jsonPrimitive?.booleanOrNull ?: false
        val maxStallMs = args["max_stall_ms"]?.jsonPrimitive?.intOrNull?.toLong() ?: 400L

        val lines = logText.lines().mapIndexed { idx, line ->
            LogLine(number = idx + 1, elapsedMillis = idx * 50L, text = line)
        }
        val crashFindings = CrashAnalyzer.analyze(lines)
        val barrierViolation = BarrierStallAnalyzer.detectViolation(lines, Duration.ofMillis(maxStallMs))
        val recoveryAssessment = FoliaCrashRecoveryAnalyzer.evaluate(
            transcript = lines,
            maxFaultsPerRegion = 1,
            loopWindow = Duration.ofSeconds(5),
            maxRecoveryTime = Duration.ofSeconds(2),
            requireRecoveryOnFault = true,
        )
        val peakHeapPct = HeapUsageAnalyzer.peakUsagePercent(lines.map { it.text })

        return buildJsonObject {
            put("line_count", lines.size)
            put("finding_count", crashFindings.size)
            put("crash_guard_triggered", CrashAnalyzer.firstCrashGuardFailure(crashFindings) != null)
            put("barrier_ok", barrierViolation == null)
            put("region_recovery_ok", recoveryAssessment.violation == null)
            peakHeapPct?.let { pct ->
                put("peak_heap_percent", pct)
            }
            putJsonArray("findings") {
                for (f in crashFindings) {
                    add(buildJsonObject {
                        put("ruleId", f.ruleId)
                        put("severity", f.severity)
                        put("message", f.message)
                        f.lineNumber?.let { ln -> put("lineNumber", ln) }
                    })
                }
            }
            if (includeSarif) {
                val report = RunReport(
                    tool = BuildInfo.NAME,
                    toolVersion = BuildInfo.version,
                    scenario = "mcp-log-analysis",
                    startedAt = Instant.now(),
                    cases = listOf(
                        CaseResult(
                            case = Scenario.Case(Platform.PAPER, "inline-log"),
                            outcome = if (crashFindings.isEmpty()) Outcome.PASSED else Outcome.FAILED,
                            elapsed = Duration.ZERO,
                            diagnostics = crashFindings,
                        ),
                    ),
                )
                put("sarif", json.parseToJsonElement(SarifRunReporter().render(report)))
            }
        }
    }

    private fun toolPlanFoliaTopology(args: JsonObject): JsonObject {
        val regions = (args["regions"]?.jsonPrimitive?.intOrNull ?: 3).coerceIn(1, 32)
        val modeRaw = args["mode"]?.jsonPrimitive?.contentOrNull ?: "merge-split"
        val spacing = (args["spacing"]?.jsonPrimitive?.intOrNull ?: FoliaRegionTopology.DEFAULT_SPACING_BLOCKS).coerceAtLeast(768)
        val mode = if (modeRaw.equals("disjoint", ignoreCase = true)) {
            FoliaRegionTopology.Mode.DISJOINT
        } else {
            FoliaRegionTopology.Mode.MERGE_SPLIT
        }
        val anchors = FoliaRegionTopology.planNodes(regions = regions, spacingBlocks = spacing)
        return buildJsonObject {
            put("regions", regions)
            put("mode", mode.id)
            put("spacing_blocks", spacing)
            putJsonArray("anchors") {
                for (a in anchors) {
                    add(buildJsonObject {
                        put("index", a.index)
                        put("blockX", a.blockX)
                        put("blockZ", a.blockZ)
                        put("chunkX", a.chunkX)
                        put("chunkZ", a.chunkZ)
                        put("sectionX", a.sectionX)
                        put("sectionZ", a.sectionZ)
                    })
                }
            }
        }
    }

    private fun toolGenerateScenario(args: JsonObject): JsonObject {
        val pluginName = args["plugin_name"]?.jsonPrimitive?.contentOrNull ?: "MyPlugin"
        val pluginGlob = args["plugin_glob"]?.jsonPrimitive?.contentOrNull ?: "build/libs/*.jar"
        val includeFolia = args["include_folia"]?.jsonPrimitive?.booleanOrNull ?: true
        val includeBotProbe = args["include_bot_probe"]?.jsonPrimitive?.booleanOrNull ?: true
        val platforms = if (includeFolia) "[paper, folia, purpur]" else "[paper, purpur]"
        val sb = StringBuilder()
        sb.appendLine("name: $pluginName real-server integration suite")
        sb.appendLine("platforms: $platforms")
        sb.appendLine("versions: [\"latest\"]")
        sb.appendLine("memory: 2G")
        sb.appendLine("crash-guard: true")
        sb.appendLine("plugins:")
        sb.appendLine("  - $pluginGlob")
        sb.appendLine("steps:")
        sb.appendLine("  - enabled: $pluginName")
        if (includeBotProbe) {
            sb.appendLine("  - bot-probe:")
            sb.appendLine("      max-latency: 500ms")
        }
        if (includeFolia) {
            sb.appendLine("  - inject-lag:")
            sb.appendLine("      regions: 3")
            sb.appendLine("      mode: merge-split")
            sb.appendLine("      duration: 2s")
            sb.appendLine("  - no-barrier-stall:")
            sb.appendLine("      max-stall: 400ms")
            sb.appendLine("      probe-command: \"list\"")
        }
        sb.appendLine("  - assert-heap:")
        sb.appendLine("      max-percent: 85")
        sb.appendLine("      window: 2s")
        return buildJsonObject {
            put("filename", "mcstage.yml")
            put("yaml", sb.toString())
        }
    }

    private fun toolCompareReports(args: JsonObject): JsonObject {
        val baseline = args["baseline_json"]?.jsonPrimitive?.contentOrNull
            ?: throw IllegalArgumentException("Missing required 'baseline_json' string.")
        val current = args["current_json"]?.jsonPrimitive?.contentOrNull
            ?: throw IllegalArgumentException("Missing required 'current_json' string.")
        val maxSlowdown = args["max_slowdown_pct"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 25.0
        val comparison = ReportComparator.compare(baseline, current, maxSlowdownPercent = maxSlowdown)
        val base = ReportComparator.toJson(comparison)
        return buildJsonObject {
            for ((k, v) in base) put(k, v)
            put("markdown", ReportComparator.renderMarkdown(comparison))
        }
    }

    private fun toolCheckWorldIntegrity(args: JsonObject): JsonObject {
        val dirRaw = args["world_dir"]?.jsonPrimitive?.contentOrNull
            ?: throw IllegalArgumentException("Missing required 'world_dir' string.")
        val requireLockCleared = args["require_session_lock_cleared"]?.jsonPrimitive?.booleanOrNull ?: false
        val rep = com.salyvn.mcstage.run.WorldIntegrityChecker.inspect(
            java.nio.file.Path.of(dirRaw),
            requireSessionLockCleared = requireLockCleared,
        )
        return buildJsonObject {
            put("ok", rep.ok)
            put("world_dir", rep.worldDir)
            put("level_dat_valid", rep.levelDatValid)
            put("session_lock_present", rep.sessionLockPresent)
            put("region_files_scanned", rep.regionFilesScanned)
            put("valid_chunk_headers", rep.validChunkHeaders)
            put("issue_count", rep.issues.size)
            putJsonArray("issues") {
                for (iss in rep.issues) {
                    add(buildJsonObject {
                        put("ruleId", "MCS021")
                        put("code", iss.code)
                        put("path", iss.path)
                        put("message", iss.message)
                    })
                }
            }
        }
    }

    private fun toolDoctor(args: JsonObject): JsonObject {
        val port = (args["port"]?.jsonPrimitive?.intOrNull ?: 25565).coerceIn(1, 65535)
        val bridgeUrl = args["bridge_url"]?.jsonPrimitive?.contentOrNull
        val report = com.salyvn.mcstage.core.DoctorCheck.run(
            port = port,
            bridgeUrl = bridgeUrl,
        )
        return com.salyvn.mcstage.core.DoctorCheck.toJson(report)
    }

    private fun toolProbeServer(args: JsonObject): JsonObject {
        val host = args["host"]?.jsonPrimitive?.contentOrNull?.trim()?.ifEmpty { "127.0.0.1" } ?: "127.0.0.1"
        val port = (args["port"]?.jsonPrimitive?.intOrNull ?: 25565).coerceIn(1, 65535)
        val timeoutMs = (args["timeout_ms"]?.jsonPrimitive?.intOrNull ?: 3000).coerceIn(200, 10000)
        val res = com.salyvn.mcstage.protocol.MinecraftSlpProbe.probe(
            host = host,
            port = port,
            timeout = Duration.ofMillis(timeoutMs.toLong()),
        )
        return buildJsonObject {
            put("reachable", res.reachable)
            put("host", host)
            put("port", port)
            put("latency_ms", res.latencyMillis)
            res.versionName?.let { put("version_name", it) }
            res.protocolVersion?.let { put("protocol_version", it) }
            res.onlinePlayers?.let { put("online_players", it) }
            res.maxPlayers?.let { put("max_players", it) }
            res.motd?.let { put("motd", it) }
            res.error?.let { put("error", it) }
        }
    }

    private fun toolAggregateJunit(args: JsonObject): JsonObject {
        val rawInputs = args["inputs"]?.let { el ->
            (el as? kotlinx.serialization.json.JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }
        } ?: throw IllegalArgumentException("Missing required 'inputs' array of JUnit XML paths/dirs/globs.")
        val suiteName = args["name"]?.jsonPrimitive?.contentOrNull?.ifBlank { "mcstage-aggregated" } ?: "mcstage-aggregated"
        val agg = com.salyvn.mcstage.report.JunitXmlAggregator()
        val files = agg.discoverXmlFiles(rawInputs)
        val rep = agg.aggregateFiles(files, name = suiteName)
        val parsed = json.parseToJsonElement(agg.renderJson(rep)).jsonObject
        return buildJsonObject {
            for ((k, v) in parsed) put(k, v)
            put("markdown", agg.renderMarkdown(rep))
        }
    }

    private fun toolAuditServerConfig(args: JsonObject): JsonObject {
        val propsText = args["server_properties"]?.jsonPrimitive?.contentOrNull
        val serverDir = args["server_dir"]?.jsonPrimitive?.contentOrNull
        val jvmFlags = (args["jvm_flags"] as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }
            ?: emptyList()
        val report = when {
            propsText != null -> com.salyvn.mcstage.instance.ConfigPreflight.audit(propsText, jvmFlags)
            !serverDir.isNullOrBlank() -> com.salyvn.mcstage.instance.ConfigPreflight.auditDirectory(java.nio.file.Path.of(serverDir), jvmFlags)
            else -> throw IllegalArgumentException("Provide either 'server_properties' text or 'server_dir' path.")
        }
        return com.salyvn.mcstage.instance.ConfigPreflight.toJson(report)
    }

    private fun toolInspectPluginJar(args: JsonObject): JsonObject {
        val jarPath = args["jar_path"]?.jsonPrimitive?.contentOrNull?.trim()?.ifEmpty { null }
            ?: throw IllegalArgumentException("Missing required 'jar_path' string.")
        val targetFolia = args["target_folia"]?.jsonPrimitive?.booleanOrNull ?: false
        val rep = com.salyvn.mcstage.instance.PluginJarInspector.inspect(
            java.nio.file.Path.of(jarPath),
            targetFolia = targetFolia,
        )
        return com.salyvn.mcstage.instance.PluginJarInspector.toJson(rep)
    }

    private fun toolListRules(): JsonObject = buildJsonObject {
        put("rule_count", CrashAnalyzer.RULES.size)
        putJsonArray("rules") {
            for (r in CrashAnalyzer.RULES) {
                add(buildJsonObject {
                    put("id", r.id)
                    put("name", r.name)
                    put("severity", r.defaultSeverity)
                    put("fatalForCrashGuard", r.fatalForCrashGuard)
                    put("shortDescription", r.shortDescription)
                    put("help", r.help)
                })
            }
        }
    }

    private fun toolDefinitions() = kotlinx.serialization.json.buildJsonArray {
        add(toolDef("mcstage_validate_scenario", "Validate an mcstage YAML scenario offline and expand its (platform, version) test matrix.", listOf("yaml")))
        add(toolDef("mcstage_analyze_log", "Analyze Paper/Folia/Purpur console logs for MCS001-MCS020 crash, barrier stall, region recovery, and heap diagnostics with optional SARIF v2.1.0.", listOf("log")))
        add(toolDef("mcstage_plan_folia_topology", "Compute disjoint or merge-split Folia ThreadedRegionizer 16-chunk section anchor coordinates.", emptyList()))
        add(toolDef("mcstage_generate_scenario", "Generate a production-ready mcstage.yml with crash-guard, bot-probe, Folia stress, and heap checks.", emptyList()))
        add(toolDef("mcstage_compare_reports", "Compare baseline vs current mcstage JSON reports to flag outcome, boot/scenario latency, or diagnostic regressions (MCS020).", listOf("baseline_json", "current_json")))
        add(toolDef("mcstage_check_world_integrity", "Audit a Minecraft world directory (level.dat GZIP header, session.lock, and Anvil .mca 4096-byte sector alignment & chunk pointers, MCS021).", listOf("world_dir")))
        add(toolDef("mcstage_doctor", "Inspect local Java 21+ runtime, JAR cache health, loopback port availability, and optional companion mckit-bridge-plugin readiness.", emptyList()))
        add(toolDef("mcstage_probe_server", "Run a pure-JVM Minecraft Java Edition Server List Ping (SLP) handshake probe against a live server (MCS018).", listOf("host")))
        add(toolDef("mcstage_aggregate_junit", "Aggregate sharded JUnit XML matrix reports (files, directories, or globs) into a unified JSON + Markdown summary with XXE hardening.", listOf("inputs")))
        add(toolDef("mcstage_audit_server_config", "Run static preflight security & performance audit on server.properties and JVM flags (MCS022, A-F grade).", emptyList()))
        add(toolDef("mcstage_inspect_plugin_jar", "Inspect a compiled Minecraft plugin JAR (paper-plugin.yml/plugin.yml, main .class presence, JVM bytecode major version, Folia flag, MCS023).", listOf("jar_path")))
        add(toolDef("mcstage_list_rules", "List all MCStage diagnostic rules (MCS001-MCS023) and crash-guard policies.", emptyList()))
    }

    private fun toolDef(name: String, description: String, required: List<String>): JsonObject = buildJsonObject {
        put("name", name)
        put("description", description)
        putJsonObject("inputSchema") {
            put("type", "object")
            putJsonObject("properties") {
                when (name) {
                    "mcstage_validate_scenario" -> putJsonObject("yaml") { put("type", "string") }
                    "mcstage_analyze_log" -> {
                        putJsonObject("log") { put("type", "string") }
                        putJsonObject("max_stall_ms") { put("type", "integer") }
                        putJsonObject("sarif") { put("type", "boolean") }
                    }
                    "mcstage_plan_folia_topology" -> {
                        putJsonObject("regions") { put("type", "integer") }
                        putJsonObject("mode") { put("type", "string") }
                        putJsonObject("spacing") { put("type", "integer") }
                    }
                    "mcstage_generate_scenario" -> {
                        putJsonObject("plugin_name") { put("type", "string") }
                        putJsonObject("plugin_glob") { put("type", "string") }
                        putJsonObject("include_folia") { put("type", "boolean") }
                        putJsonObject("include_bot_probe") { put("type", "boolean") }
                    }
                    "mcstage_compare_reports" -> {
                        putJsonObject("baseline_json") { put("type", "string") }
                        putJsonObject("current_json") { put("type", "string") }
                        putJsonObject("max_slowdown_pct") { put("type", "number") }
                    }
                    "mcstage_check_world_integrity" -> {
                        putJsonObject("world_dir") { put("type", "string") }
                        putJsonObject("require_session_lock_cleared") { put("type", "boolean") }
                    }
                    "mcstage_doctor" -> {
                        putJsonObject("port") { put("type", "integer") }
                        putJsonObject("bridge_url") { put("type", "string") }
                    }
                    "mcstage_probe_server" -> {
                        putJsonObject("host") { put("type", "string") }
                        putJsonObject("port") { put("type", "integer") }
                        putJsonObject("timeout_ms") { put("type", "integer") }
                    }
                    "mcstage_aggregate_junit" -> {
                        putJsonObject("inputs") { put("type", "array") }
                        putJsonObject("name") { put("type", "string") }
                    }
                    "mcstage_audit_server_config" -> {
                        putJsonObject("server_properties") { put("type", "string") }
                        putJsonObject("server_dir") { put("type", "string") }
                        putJsonObject("jvm_flags") { put("type", "array") }
                    }
                    "mcstage_inspect_plugin_jar" -> {
                        putJsonObject("jar_path") { put("type", "string") }
                        putJsonObject("target_folia") { put("type", "boolean") }
                    }
                }
            }
            if (required.isNotEmpty()) {
                putJsonArray("required") { required.forEach { add(JsonPrimitive(it)) } }
            }
        }
    }

    private fun resultResponse(id: JsonElement, result: JsonObject): String =
        json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                put("result", result)
            },
        )

    private fun errorResponse(id: JsonElement, code: Int, message: String): String =
        json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                putJsonObject("error") {
                    put("code", code)
                    put("message", message)
                }
            },
        )

    private fun readMessage(input: BufferedInputStream): Pair<String, Boolean>? {
        while (true) {
            val line = readLineBytes(input) ?: return null
            if (line.isEmpty()) continue
            val text = line.toString(StandardCharsets.UTF_8)
            if (text.lowercase().startsWith("content-length:")) {
                val len = text.substringAfter(':').trim().toIntOrNull() ?: return null
                // Consume remaining headers until empty line
                while (true) {
                    val hdr = readLineBytes(input) ?: return null
                    if (hdr.isEmpty()) break
                }
                val body = ByteArray(len)
                var off = 0
                while (off < len) {
                    val n = input.read(body, off, len - off)
                    if (n == -1) return null
                    off += n
                }
                return body.toString(StandardCharsets.UTF_8) to true
            }
            return text to false
        }
    }

    private fun readLineBytes(input: BufferedInputStream): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        var sawAny = false
        while (true) {
            val b = input.read()
            if (b == -1) return if (sawAny) out.toByteArray() else null
            sawAny = true
            if (b == '\n'.code) break
            if (b != '\r'.code) out.write(b)
        }
        return out.toByteArray()
    }

    private fun writeMessage(output: OutputStream, payload: String, framed: Boolean) {
        val bytes = payload.toByteArray(StandardCharsets.UTF_8)
        if (framed) {
            output.write("Content-Length: ${bytes.size}\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
            output.write(bytes)
        } else {
            output.write(bytes)
            output.write('\n'.code)
        }
        output.flush()
    }
}
