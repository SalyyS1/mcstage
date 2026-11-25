package com.salyvn.mcstage.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.path
import com.salyvn.mcstage.run.FoliaRegionTopology
import java.nio.file.Files
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Plans Folia ThreadedRegionizer anchor coordinates (`merge-split` or `disjoint`)
 * for deterministic multi-region stress testing (`mcstage plan-folia`).
 */
class PlanFoliaCommand : CliktCommand(name = "plan-folia") {

    override fun help(context: Context) =
        "Plan Folia ThreadedRegionizer anchor coordinates (merge-split or disjoint) for multi-region QA."

    private val regions by option("--regions", help = "Number of region anchors (1..32, default: 4)").int().default(4)
    private val modeRaw by option("--mode", help = "Topology mode: merge-split or disjoint (default: merge-split)").default("merge-split")
    private val spacing by option("--spacing", help = "Block spacing between region anchors (>=768, default: 1024)").int().default(FoliaRegionTopology.DEFAULT_SPACING_BLOCKS)
    private val htmlOut by option("--html", help = "Write a self-contained dark-mode HTML SVG region topology diagram").path()
    private val badgeJsonOut by option("--badge-json", help = "Write a shields.io Endpoint Badge JSON summary").path()
    private val json by option("--json", help = "Emit machine-readable JSON output").flag()

    override fun run() {
        val count = regions.coerceIn(1, 32)
        val clampedSpacing = spacing.coerceAtLeast(768)
        val mode = if (modeRaw.equals("disjoint", ignoreCase = true)) {
            FoliaRegionTopology.Mode.DISJOINT
        } else {
            FoliaRegionTopology.Mode.MERGE_SPLIT
        }
        val anchors = FoliaRegionTopology.planNodes(regions = count, spacingBlocks = clampedSpacing)
        htmlOut?.let { target ->
            target.parent?.let { Files.createDirectories(it) }
            Files.writeString(target, FoliaRegionTopology.renderTopologyHtml(anchors, mode, clampedSpacing))
        }
        badgeJsonOut?.let { target ->
            target.parent?.let { Files.createDirectories(it) }
            Files.writeString(target, FoliaRegionTopology.renderBadgeJson(anchors, mode, clampedSpacing))
        }
        if (json) {
            val payload = buildJsonObject {
                put("regions", count)
                put("mode", mode.id)
                put("spacing_blocks", clampedSpacing)
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
            echo(PRETTY_JSON.encodeToString(payload))
        } else {
            echo("MCStage Folia Region Topology ($count regions, mode=${mode.id}, spacing=${clampedSpacing} blocks)")
            for (a in anchors) {
                echo("  region #${a.index}: block=(${a.blockX}, ${a.blockZ}) chunk=(${a.chunkX}, ${a.chunkZ}) section=(${a.sectionX}, ${a.sectionZ})")
            }
        }
    }

    private companion object {
        val PRETTY_JSON = Json { prettyPrint = true }
    }
}
