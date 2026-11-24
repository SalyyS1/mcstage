package com.salyvn.mcstage.mcp

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
        assertEquals(20, rulesPayload["rule_count"]!!.jsonPrimitive.int)

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
    }
}
