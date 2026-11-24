package com.salyvn.mcstage.mcp

import com.github.ajalt.clikt.core.parse
import com.salyvn.mcstage.protocol.MinecraftSlpProbe
import com.salyvn.mcstage.run.ScenarioFile
import com.salyvn.mcstage.run.Step
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.concurrent.thread

class McpServerAndBotProbeTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `mcp server responds to initialize, tools list, validate, analyze log, and rules`() {
        val server = McstageMcpServer()

        val initResp = json.parseToJsonElement(
            server.handleMessage("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}""")!!,
        ).jsonObject
        assertEquals("mcstage", initResp["result"]!!.jsonObject["serverInfo"]!!.jsonObject["name"]!!.jsonPrimitive.content)

        val toolsResp = json.parseToJsonElement(
            server.handleMessage("""{"jsonrpc":"2.0","id":2,"method":"tools/list"}""")!!,
        ).jsonObject
        val toolNames = toolsResp["result"]!!.jsonObject["tools"]!!.jsonArray.map {
            it.jsonObject["name"]!!.jsonPrimitive.content
        }
        assertEquals(
            listOf(
                "mcstage_validate_scenario",
                "mcstage_analyze_log",
                "mcstage_plan_folia_topology",
                "mcstage_generate_scenario",
                "mcstage_compare_reports",
                "mcstage_check_world_integrity",
                "mcstage_doctor",
                "mcstage_probe_server",
                "mcstage_aggregate_junit",
                "mcstage_audit_server_config",
                "mcstage_inspect_plugin_jar",
                "mcstage_render_comparison_html",
                "mcstage_estimate_matrix_budget",
                "mcstage_generate_github_action",
                "mcstage_list_rules",
            ),
            toolNames,
        )

        val rulesResp = json.parseToJsonElement(
            server.handleMessage("""{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"mcstage_list_rules","arguments":{}}}""")!!,
        ).jsonObject
        val rulesPayload = json.parseToJsonElement(
            rulesResp["result"]!!.jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content,
        ).jsonObject
        assertEquals(23, rulesPayload["rule_count"]!!.jsonPrimitive.int)

        val logResp = json.parseToJsonElement(
            server.handleMessage(
                """{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"mcstage_analyze_log","arguments":{"log":"[Server thread/ERROR]: Error occurred while enabling BadPlugin v1.0\n[ThreadedRegionizer] Region merge barrier stalled waiting on chunk (16, 0)","sarif":true}}}""",
            )!!,
        ).jsonObject
        val logPayload = json.parseToJsonElement(
            logResp["result"]!!.jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content,
        ).jsonObject
        assertTrue(logPayload["crash_guard_triggered"]!!.jsonPrimitive.boolean)
        assertNotNull(logPayload["sarif"])
    }

    @Test
    fun `slp bot probe parses real loopback Minecraft Server List Ping packet`() {
        val statusJson = """{"version":{"name":"Paper 26.3","protocol":775},"players":{"max":50,"online":2},"description":{"text":"MCStage Loopback Server"}}"""
        val jsonBytes = statusJson.toByteArray(StandardCharsets.UTF_8)
        val inner = ByteArrayOutputStream().apply {
            MinecraftSlpProbe.writeVarInt(this, 0x00)
            MinecraftSlpProbe.writeVarInt(this, jsonBytes.size)
            write(jsonBytes)
        }.toByteArray()
        val packet = MinecraftSlpProbe.framePacket(inner)

        ServerSocket(0).use { srv ->
            val port = srv.localPort
            val t = thread(isDaemon = true) {
                srv.accept().use { client ->
                    client.getInputStream().read(ByteArray(256))
                    client.getOutputStream().write(packet)
                    client.getOutputStream().flush()
                }
            }
            val result = MinecraftSlpProbe.probe(host = "127.0.0.1", port = port, timeout = Duration.ofSeconds(2))
            t.join(2000)
            assertTrue(result.reachable)
            assertEquals("Paper 26.3", result.versionName)
            assertEquals(775, result.protocolVersion)
            assertEquals(2, result.onlinePlayers)
            assertEquals("MCStage Loopback Server", result.motd)
        }
    }

    @Test
    fun `scenario file parses bot-probe and assert-bridge steps cleanly`(@TempDir dir: Path) {
        val file = dir.resolve("bot-qa.yml")
        Files.writeString(
            file,
            """
            name: bot probe and companion bridge check
            platforms: [paper, folia]
            versions: ["26.3"]
            steps:
              - enabled: MCKitBridge
              - bot-probe:
                  max-latency: 400ms
                  expect-version: "Paper"
              - assert-bridge:
                  url: "http://127.0.0.1:25590"
                  min-tps: 19.0
                  max-mspt: 35.0
                  max-errors: 0
                  player: "MCKitTester"
                  expect-gui-title: "RPG Shop"
            """.trimIndent(),
        )
        val scenario = ScenarioFile.read(file)
        assertEquals(3, scenario.steps.size)
        val probe = scenario.steps[1] as Step.BotProbe
        assertEquals(Duration.ofMillis(400), probe.maxLatency)
        val bridge = scenario.steps[2] as Step.AssertBridge
        assertEquals(19.0, bridge.minTps)
        assertEquals(0, bridge.maxErrors)
        assertEquals("MCKitTester", bridge.player)
        assertNotNull(bridge.expectGuiTitle)
    }

    @Test
    fun `mcp server validates generated scenario round-trip`() {
        val server = McstageMcpServer()
        val genResp = json.parseToJsonElement(
            server.handleMessage("""{"jsonrpc":"2.0","id":10,"method":"tools/call","params":{"name":"mcstage_generate_scenario","arguments":{"plugin_name":"OZbanghoi","include_bot_probe":true}}}""")!!,
        ).jsonObject
        val genPayload = json.parseToJsonElement(
            genResp["result"]!!.jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content,
        ).jsonObject
        val yaml = genPayload["yaml"]!!.jsonPrimitive.content
        assertTrue(yaml.contains("bot-probe:"))

        val req = kotlinx.serialization.json.buildJsonObject {
            put("jsonrpc", kotlinx.serialization.json.JsonPrimitive("2.0"))
            put("id", kotlinx.serialization.json.JsonPrimitive(11))
            put("method", kotlinx.serialization.json.JsonPrimitive("tools/call"))
            put("params", kotlinx.serialization.json.buildJsonObject {
                put("name", kotlinx.serialization.json.JsonPrimitive("mcstage_validate_scenario"))
                put("arguments", kotlinx.serialization.json.buildJsonObject {
                    put("yaml", kotlinx.serialization.json.JsonPrimitive(yaml))
                })
            })
        }
        val valResp = json.parseToJsonElement(server.handleMessage(req.toString())!!).jsonObject
        val valPayload = json.parseToJsonElement(
            valResp["result"]!!.jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content,
        ).jsonObject
        assertTrue(valPayload["valid"]!!.jsonPrimitive.boolean)
        assertEquals(3, valPayload["case_count"]!!.jsonPrimitive.int)
        assertFalse(valResp["result"]!!.jsonObject["isError"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `report comparator flags slowdown, outcome regression, and new diagnostics with MCS020`() {
        val baseline = """
            {
              "scenario": "plugin-qa",
              "cases": [
                {"platform": "paper", "version": "26.3", "outcome": "passed", "elapsedMillis": 4000, "diagnostics": []},
                {"platform": "folia", "version": "26.3", "outcome": "passed", "elapsedMillis": 5000, "diagnostics": []}
              ]
            }
        """.trimIndent()
        val current = """
            {
              "scenario": "plugin-qa",
              "cases": [
                {"platform": "paper", "version": "26.3", "outcome": "passed", "elapsedMillis": 6200, "diagnostics": []},
                {"platform": "folia", "version": "26.3", "outcome": "failed", "elapsedMillis": 5100, "diagnostics": [{"ruleId": "MCS015"}]}
              ]
            }
        """.trimIndent()
        val result = com.salyvn.mcstage.report.ReportComparator.compare(baseline, current, maxSlowdownPercent = 25.0)
        assertFalse(result.ok)
        assertTrue(result.cases[0].durationRegressed)
        assertTrue(result.cases[1].outcomeRegressed)
        assertEquals(listOf("MCS015"), result.cases[1].newRules)
        val sarif = com.salyvn.mcstage.report.ReportComparator.renderSarif(result)
        assertTrue(sarif.contains("\"MCS020\""))

        val server = McstageMcpServer()
        val req = kotlinx.serialization.json.buildJsonObject {
            put("jsonrpc", kotlinx.serialization.json.JsonPrimitive("2.0"))
            put("id", kotlinx.serialization.json.JsonPrimitive(20))
            put("method", kotlinx.serialization.json.JsonPrimitive("tools/call"))
            put("params", kotlinx.serialization.json.buildJsonObject {
                put("name", kotlinx.serialization.json.JsonPrimitive("mcstage_compare_reports"))
                put("arguments", kotlinx.serialization.json.buildJsonObject {
                    put("baseline_json", kotlinx.serialization.json.JsonPrimitive(baseline))
                    put("current_json", kotlinx.serialization.json.JsonPrimitive(current))
                    put("max_slowdown_pct", kotlinx.serialization.json.JsonPrimitive(25.0))
                })
            })
        }
        val cmpResp = json.parseToJsonElement(server.handleMessage(req.toString())!!).jsonObject
        val cmpPayload = json.parseToJsonElement(
            cmpResp["result"]!!.jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content,
        ).jsonObject
        assertFalse(cmpPayload["ok"]!!.jsonPrimitive.boolean)
        assertTrue(cmpPayload["markdown"]!!.jsonPrimitive.content.contains("REGRESSION DETECTED"))
        val htmlDiff = com.salyvn.mcstage.report.ReportComparator.renderHtml(result)
        assertTrue(htmlDiff.contains("<!doctype html>"))
        assertTrue(htmlDiff.contains("REGRESSION DETECTED"))
    }

    @Test
    fun `world integrity checker validates valid Anvil region and flags truncated corruption`(@TempDir dir: Path) {
        val worldDir = dir.resolve("world")
        val regionDir = worldDir.resolve("region")
        Files.createDirectories(regionDir)
        // Valid GZIP header (0x1F 0x8B ...)
        val gzipDummy = ByteArray(24).apply { this[0] = 0x1F; this[1] = 0x8B.toByte() }
        Files.write(worldDir.resolve("level.dat"), gzipDummy)

        // Valid 3-sector (12288 B) Anvil .mca with 1 chunk at sector 2, count 1
        val validMca = ByteArray(4096 * 3)
        validMca[0] = 0; validMca[1] = 0; validMca[2] = 2; validMca[3] = 1
        Files.write(regionDir.resolve("r.0.0.mca"), validMca)

        val okRep = com.salyvn.mcstage.run.WorldIntegrityChecker.inspect(worldDir, requireSessionLockCleared = true)
        assertTrue(okRep.ok, okRep.issues.toString())
        assertEquals(1, okRep.regionFilesScanned)
        assertEquals(1, okRep.validChunkHeaders)

        // Now add a truncated .mca file and check via MCP tool
        Files.write(regionDir.resolve("r.0.1.mca"), ByteArray(512))
        val server = McstageMcpServer()
        val req = kotlinx.serialization.json.buildJsonObject {
            put("jsonrpc", kotlinx.serialization.json.JsonPrimitive("2.0"))
            put("id", kotlinx.serialization.json.JsonPrimitive(25))
            put("method", kotlinx.serialization.json.JsonPrimitive("tools/call"))
            put("params", kotlinx.serialization.json.buildJsonObject {
                put("name", kotlinx.serialization.json.JsonPrimitive("mcstage_check_world_integrity"))
                put("arguments", kotlinx.serialization.json.buildJsonObject {
                    put("world_dir", kotlinx.serialization.json.JsonPrimitive(worldDir.toString()))
                })
            })
        }
        val resp = json.parseToJsonElement(server.handleMessage(req.toString())!!).jsonObject
        val payload = json.parseToJsonElement(
            resp["result"]!!.jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content,
        ).jsonObject
        assertFalse(payload["ok"]!!.jsonPrimitive.boolean)
        assertEquals(1, payload["issue_count"]!!.jsonPrimitive.int)
    }

    @Test
    fun `doctor check verifies Java 21 runtime and mcstage_doctor MCP tool`(@TempDir dir: Path) {
        val rep = com.salyvn.mcstage.core.DoctorCheck.run(port = 25599, cacheRoot = dir)
        assertTrue(rep.ok)
        assertTrue(rep.javaOk)
        assertEquals(0, rep.cachedJarCount)
        assertTrue(com.salyvn.mcstage.core.DoctorCheck.renderText(rep).contains("READY"))

        val server = McstageMcpServer()
        val resp = json.parseToJsonElement(
            server.handleMessage("""{"jsonrpc":"2.0","id":26,"method":"tools/call","params":{"name":"mcstage_doctor","arguments":{"port":25599}}}""")!!,
        ).jsonObject
        val payload = json.parseToJsonElement(
            resp["result"]!!.jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content,
        ).jsonObject
        assertTrue(payload["ok"]!!.jsonPrimitive.boolean)
        assertTrue(payload["java"]!!.jsonObject["ok"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `html run reporter escapes special characters and renders pass fail matrix`() {
        val rep = com.salyvn.mcstage.run.RunReport(
            tool = "mcstage",
            toolVersion = "0.13.0",
            scenario = "RPG <script>alert(1)</script> QA",
            startedAt = java.time.Instant.EPOCH,
            cases = listOf(
                com.salyvn.mcstage.run.CaseResult(
                    case = com.salyvn.mcstage.run.Scenario.Case(com.salyvn.mcstage.core.Platform.PAPER, "26.3"),
                    outcome = com.salyvn.mcstage.run.Outcome.PASSED,
                    elapsed = Duration.ofMillis(1200),
                    steps = listOf(com.salyvn.mcstage.run.StepResult("enabled: MyRPG", ok = true, elapsed = Duration.ofMillis(300))),
                ),
            ),
        )
        val html = com.salyvn.mcstage.report.HtmlRunReporter().render(rep)
        assertTrue(html.contains("<!doctype html>"))
        assertFalse(html.contains("<script>alert(1)</script>"))
        assertTrue(html.contains("&lt;script&gt;alert(1)&lt;/script&gt;"))
        assertTrue(html.contains("PASS (1/1)"))
    }

    @Test
    fun `config preflight audits server properties and jvm flags via MCS022 and MCP tool`() {
        val props = """
            online-mode=false
            server-ip=0.0.0.0
            enable-rcon=true
            rcon.password=123456
            sync-chunk-writes=true
            view-distance=16
            network-compression-threshold=1024
        """.trimIndent()
        val rep = com.salyvn.mcstage.instance.ConfigPreflight.audit(props, listOf("-Xms256M", "-Xmx512M"))
        assertFalse(rep.ok)
        assertTrue(rep.issues.any { it.code == "CFG-001" })
        assertTrue(rep.issues.any { it.code == "CFG-002" })
        assertTrue(rep.issues.any { it.code == "CFG-006" })

        val server = McstageMcpServer()
        val req = kotlinx.serialization.json.buildJsonObject {
            put("jsonrpc", kotlinx.serialization.json.JsonPrimitive("2.0"))
            put("id", kotlinx.serialization.json.JsonPrimitive(30))
            put("method", kotlinx.serialization.json.JsonPrimitive("tools/call"))
            put("params", kotlinx.serialization.json.buildJsonObject {
                put("name", kotlinx.serialization.json.JsonPrimitive("mcstage_audit_server_config"))
                put("arguments", kotlinx.serialization.json.buildJsonObject {
                    put("server_properties", kotlinx.serialization.json.JsonPrimitive("online-mode=true\nserver-ip=127.0.0.1\nview-distance=8\nsimulation-distance=6\nsync-chunk-writes=false\nnetwork-compression-threshold=256\n"))
                    put("jvm_flags", kotlinx.serialization.json.buildJsonArray {
                        add(kotlinx.serialization.json.JsonPrimitive("-Xms2G"))
                        add(kotlinx.serialization.json.JsonPrimitive("-Xmx2G"))
                        add(kotlinx.serialization.json.JsonPrimitive("-XX:+UseG1GC"))
                    })
                })
            })
        }
        val resp = json.parseToJsonElement(server.handleMessage(req.toString())!!).jsonObject
        val payload = json.parseToJsonElement(
            resp["result"]!!.jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content,
        ).jsonObject
        assertTrue(payload["ok"]!!.jsonPrimitive.boolean)
        assertEquals("A", payload["grade"]!!.jsonPrimitive.content)
    }

    @Test
    fun `plugin jar inspector verifies manifest, main class bytecode version, and folia support`(@TempDir dir: Path) {
        val jar = dir.resolve("sample-plugin.jar")
        java.util.zip.ZipOutputStream(Files.newOutputStream(jar)).use { zos ->
            zos.putNextEntry(java.util.zip.ZipEntry("paper-plugin.yml"))
            zos.write(
                """
                name: SampleRPG
                version: 1.0.0
                main: com.salyvn.sample.SamplePlugin
                api-version: '1.21.4'
                folia-supported: true
                dependencies:
                  server:
                    Vault: {}
                """.trimIndent().toByteArray(StandardCharsets.UTF_8),
            )
            zos.closeEntry()
            // Synthetic classfile header: CAFEBABE minor=0 major=65 (Java 21)
            zos.putNextEntry(java.util.zip.ZipEntry("com/salyvn/sample/SamplePlugin.class"))
            zos.write(byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte(), 0x00, 0x00, 0x00, 65))
            zos.closeEntry()
        }

        val server = McstageMcpServer()
        val req = kotlinx.serialization.json.buildJsonObject {
            put("jsonrpc", kotlinx.serialization.json.JsonPrimitive("2.0"))
            put("id", kotlinx.serialization.json.JsonPrimitive(31))
            put("method", kotlinx.serialization.json.JsonPrimitive("tools/call"))
            put("params", kotlinx.serialization.json.buildJsonObject {
                put("name", kotlinx.serialization.json.JsonPrimitive("mcstage_inspect_plugin_jar"))
                put("arguments", kotlinx.serialization.json.buildJsonObject {
                    put("jar_path", kotlinx.serialization.json.JsonPrimitive(jar.toString()))
                    put("target_folia", kotlinx.serialization.json.JsonPrimitive(true))
                })
            })
        }
        val resp = json.parseToJsonElement(server.handleMessage(req.toString())!!).jsonObject
        val payload = json.parseToJsonElement(
            resp["result"]!!.jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content,
        ).jsonObject
        assertTrue(payload["ok"]!!.jsonPrimitive.boolean)
        assertEquals("SampleRPG", payload["plugin_name"]!!.jsonPrimitive.content)
        assertEquals(21, payload["main_class_java_major"]!!.jsonPrimitive.int)
        assertTrue(payload["folia_supported"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `matrix estimator budgets RAM, cache, and CI shards for mcstage yaml`() {
        val yaml = """
            name: Multi-Platform CI Matrix
            platforms: [paper, folia, purpur]
            versions: ["1.21.4", "latest"]
            memory: 2G
            steps:
              - enabled: MyPlugin
              - bot-probe:
                  max-latency: 400ms
        """.trimIndent()
        val server = McstageMcpServer()
        val req = kotlinx.serialization.json.buildJsonObject {
            put("jsonrpc", kotlinx.serialization.json.JsonPrimitive("2.0"))
            put("id", kotlinx.serialization.json.JsonPrimitive(32))
            put("method", kotlinx.serialization.json.JsonPrimitive("tools/call"))
            put("params", kotlinx.serialization.json.buildJsonObject {
                put("name", kotlinx.serialization.json.JsonPrimitive("mcstage_estimate_matrix_budget"))
                put("arguments", kotlinx.serialization.json.buildJsonObject {
                    put("yaml", kotlinx.serialization.json.JsonPrimitive(yaml))
                    put("host_ram_gb", kotlinx.serialization.json.JsonPrimitive(7.0))
                    put("max_shards", kotlinx.serialization.json.JsonPrimitive(3))
                })
            })
        }
        val resp = json.parseToJsonElement(server.handleMessage(req.toString())!!).jsonObject
        val payload = json.parseToJsonElement(
            resp["result"]!!.jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content,
        ).jsonObject
        assertEquals(6, payload["case_count"]!!.jsonPrimitive.int)
        assertEquals(3, payload["recommended_shards"]!!.jsonPrimitive.int)
        assertTrue(payload["estimated_parallel_seconds"]!!.jsonPrimitive.int < payload["estimated_serial_seconds"]!!.jsonPrimitive.int)
    }

    @Test
    fun `cli inspect-jar and preflight subcommands parse and execute cleanly`(@TempDir dir: Path) {
        val props = dir.resolve("server.properties")
        Files.writeString(
            props,
            "online-mode=true\nserver-ip=127.0.0.1\nview-distance=8\nsimulation-distance=6\nsync-chunk-writes=false\n",
            StandardCharsets.UTF_8,
        )
        com.salyvn.mcstage.cli.PreflightCommand().parse(
            listOf(dir.toString(), "--jvm-arg", "-Xms2G", "--jvm-arg", "-Xmx2G", "--jvm-arg", "-XX:+UseG1GC", "--json"),
        )

        val jar = dir.resolve("cli-plugin.jar")
        java.util.zip.ZipOutputStream(Files.newOutputStream(jar)).use { zos ->
            zos.putNextEntry(java.util.zip.ZipEntry("paper-plugin.yml"))
            zos.write(
                "name: CliTest\nversion: 1.0.0\nmain: com.salyvn.cli.Main\napi-version: '1.21.4'\nfolia-supported: true\n"
                    .toByteArray(StandardCharsets.UTF_8),
            )
            zos.closeEntry()
            zos.putNextEntry(java.util.zip.ZipEntry("com/salyvn/cli/Main.class"))
            zos.write(byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte(), 0x00, 0x00, 0x00, 65))
            zos.closeEntry()
        }
        com.salyvn.mcstage.cli.InspectJarCommand().parse(listOf(jar.toString(), "--folia", "--json"))

        val world = dir.resolve("test-world")
        Files.createDirectories(world.resolve("region"))
        // Minimal valid GZIP header (18+ bytes)
        val gzipHeader = ByteArray(20)
        gzipHeader[0] = 0x1F
        gzipHeader[1] = 0x8B.toByte()
        Files.write(world.resolve("level.dat"), gzipHeader)
        Files.write(world.resolve("region/r.0.0.mca"), ByteArray(8192))
        com.salyvn.mcstage.cli.CheckWorldCommand().parse(listOf(world.toString(), "--require-unlocked", "--json"))

        for (preset in listOf("basic", "folia-stress", "rpg-bot-bridge", "ci-matrix")) {
            val out = dir.resolve("preset-$preset.yml")
            com.salyvn.mcstage.cli.InitCommand().parse(listOf(out.toString(), "--preset", preset))
            assertTrue(Files.isRegularFile(out))
            val parsed = ScenarioFile.read(out)
            assertTrue(parsed.steps.isNotEmpty(), "preset $preset should contain steps")
            if (preset == "ci-matrix") {
                com.salyvn.mcstage.cli.BudgetCommand().parse(listOf(out.toString(), "--host-ram-gb", "7.0", "--max-shards", "3", "--json"))
            }
        }

        val workflowOut = dir.resolve("mcstage-ci.yml")
        com.salyvn.mcstage.cli.GithubActionCommand().parse(listOf("mcstage.yml", "--build-tool", "gradle", "-o", workflowOut.toString()))
        val wfText = Files.readString(workflowOut, StandardCharsets.UTF_8)
        assertTrue(wfText.contains("upload-sarif@v3"))
        assertTrue(wfText.contains("~/.mcstage"))
        com.salyvn.mcstage.cli.ListRulesCommand().parse(listOf("--json"))

        // Verify ProbeCommand against a real local loopback SLP socket
        ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { ss ->
            val port = ss.localPort
            val worker = thread(isDaemon = true) {
                ss.accept().use { sock ->
                    val input = sock.getInputStream()
                    // Drain handshake + status request headers quickly
                    val buf = ByteArray(128)
                    input.read(buf)
                    val jsonBytes = """{"version":{"name":"Paper 1.21.4","protocol":769},"players":{"max":20,"online":1},"description":{"text":"MCStage Probe"}}"""
                        .toByteArray(StandardCharsets.UTF_8)
                    val payload = ByteArrayOutputStream().apply {
                        write(0x00) // status response id
                        // varint json length
                        var v = jsonBytes.size
                        while (true) {
                            if ((v and 0x7F.inv()) == 0) { write(v); break }
                            write((v and 0x7F) or 0x80)
                            v = v ushr 7
                        }
                        write(jsonBytes)
                    }.toByteArray()
                    val frame = ByteArrayOutputStream().apply {
                        var v = payload.size
                        while (true) {
                            if ((v and 0x7F.inv()) == 0) { write(v); break }
                            write((v and 0x7F) or 0x80)
                            v = v ushr 7
                        }
                        write(payload)
                    }.toByteArray()
                    sock.getOutputStream().write(frame)
                    sock.getOutputStream().flush()
                }
            }
            com.salyvn.mcstage.cli.ProbeCommand().parse(listOf("127.0.0.1", "--port", port.toString(), "--json"))
            worker.join(3000)
        }
    }
}
