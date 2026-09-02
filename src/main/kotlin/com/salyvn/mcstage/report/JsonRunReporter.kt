package com.salyvn.mcstage.report

import com.salyvn.mcstage.run.RunReport
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Machine-readable output, for the case where the exit code is not enough and something wants to know
 * which version failed and where.
 *
 * The shape is built here rather than annotated onto the result classes, so that renaming a field in the
 * runner cannot silently change a format other people's scripts parse.
 */
class JsonRunReporter {

    fun render(report: RunReport): String = json.encodeToString(JsonObject.serializer(), asJson(report))

    private fun asJson(report: RunReport) = buildJsonObject {
        put("tool", report.tool)
        put("toolVersion", report.toolVersion)
        put("scenario", report.scenario)
        put("startedAt", report.startedAt.toString())
        put("ok", report.ok)
        putJsonObject("summary") {
            put("passed", report.passed)
            put("failed", report.failed)
            put("errored", report.errored)
            put("elapsedMillis", report.elapsed.toMillis())
        }
        put("cases", cases(report))
    }

    private fun cases(report: RunReport): JsonArray = buildJsonArray {
        for (case in report.cases) {
            add(
                buildJsonObject {
                    put("platform", case.case.platform.id)
                    put("version", case.case.version)
                    put("outcome", case.outcome.name.lowercase())
                    put("elapsedMillis", case.elapsed.toMillis())
                    case.failure?.let { put("failure", it) }
                    case.stop?.let { put("stopped", it.name.lowercase()) }
                    putJsonObject("facts") {
                        for ((key, value) in case.facts) put(key, value)
                    }
                    putJsonArray("steps") {
                        for (step in case.steps) {
                            add(
                                buildJsonObject {
                                    put("step", step.describe)
                                    put("ok", step.ok)
                                    put("elapsedMillis", step.elapsed.toMillis())
                                    step.detail?.let { put("detail", it) }
                                    step.matched?.let { put("matched", it) }
                                },
                            )
                        }
                    }
                    if (case.tail.isNotEmpty()) {
                        putJsonArray("tail") { for (line in case.tail) add(line) }
                    }
                },
            )
        }
    }

    private companion object {
        val json = Json { prettyPrint = true }
    }
}
