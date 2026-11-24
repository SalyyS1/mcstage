package com.salyvn.mcstage.run

import com.salyvn.mcstage.core.SetupError
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Models PaperMC Folia's ThreadedRegionizer section geometry and builds deterministic multi-region
 * synthetic workload and region merge/split barrier schedules.
 *
 * Folia partitions worlds into 16x16-chunk sections (`sectionChunkShift = 4`, 256x256 blocks). Around
 * every active ticketed section, `emptySectionCreateRadius = 1` allocates padding sections and
 * `regionSectionMergeRadius = 1` merges regions within Chebyshev distance `1 + 1 = 2` sections. Two
 * active region anchors are therefore guaranteed to tick on independent region threads iff their
 * Chebyshev section distance is at least `3` sections (`48` chunks = `768` blocks).
 */
object FoliaRegionTopology {

    const val SECTION_CHUNK_SHIFT: Int = 4
    const val CHUNKS_PER_SECTION: Int = 1 shl SECTION_CHUNK_SHIFT
    const val BLOCKS_PER_CHUNK: Int = 16
    const val SECTION_BLOCK_SIZE: Int = CHUNKS_PER_SECTION * BLOCKS_PER_CHUNK
    const val CREATE_RADIUS_SECTIONS: Int = 1
    const val MERGE_RADIUS_SECTIONS: Int = 1
    const val MIN_DISJOINT_SECTION_DISTANCE: Int = CREATE_RADIUS_SECTIONS + MERGE_RADIUS_SECTIONS + 1
    const val MIN_DISJOINT_SPACING_BLOCKS: Int = MIN_DISJOINT_SECTION_DISTANCE * SECTION_BLOCK_SIZE
    const val DEFAULT_SPACING_BLOCKS: Int = 1024
    const val MAX_REGIONS: Int = 64

    enum class Mode(val id: String) {
        DISJOINT("disjoint"),
        MERGE_SPLIT("merge-split");

        companion object {
            fun of(raw: String, where: String): Mode {
                val normalized = raw.trim().lowercase()
                return entries.firstOrNull { it.id == normalized }
                    ?: throw SetupError(
                        "$where: unknown inject-lag mode '$raw'. Expected 'disjoint' or 'merge-split'.",
                    )
            }
        }
    }

    data class RegionNode(
        val index: Int,
        val row: Int,
        val col: Int,
        val blockX: Int,
        val blockY: Int,
        val blockZ: Int,
    ) {
        val chunkX: Int get() = Math.floorDiv(blockX, BLOCKS_PER_CHUNK)
        val chunkZ: Int get() = Math.floorDiv(blockZ, BLOCKS_PER_CHUNK)
        val sectionX: Int get() = Math.floorDiv(chunkX, CHUNKS_PER_SECTION)
        val sectionZ: Int get() = Math.floorDiv(chunkZ, CHUNKS_PER_SECTION)
    }

    fun chebyshevSectionDistance(a: RegionNode, b: RegionNode): Int =
        max(abs(a.sectionX - b.sectionX), abs(a.sectionZ - b.sectionZ))

    fun areGuaranteedDisjoint(a: RegionNode, b: RegionNode): Boolean =
        chebyshevSectionDistance(a, b) >= MIN_DISJOINT_SECTION_DISTANCE

    /**
     * Places `regions` anchor coordinates on a 2D square lattice spaced by `spacingBlocks` along X and Z.
     */
    fun planNodes(
        regions: Int,
        spacingBlocks: Int = DEFAULT_SPACING_BLOCKS,
        originX: Int = 0,
        originY: Int = 64,
        originZ: Int = 0,
    ): List<RegionNode> {
        require(regions in 1..MAX_REGIONS) { "regions must be between 1 and $MAX_REGIONS, got $regions" }
        require(spacingBlocks >= SECTION_BLOCK_SIZE) {
            "spacingBlocks must be at least $SECTION_BLOCK_SIZE, got $spacingBlocks"
        }
        val cols = ceil(sqrt(regions.toDouble())).toInt().coerceAtLeast(1)
        return (0 until regions).map { idx ->
            val row = idx / cols
            val col = idx % cols
            RegionNode(
                index = idx,
                row = row,
                col = col,
                blockX = originX + col * spacingBlocks,
                blockY = originY,
                blockZ = originZ + row * spacingBlocks,
            )
        }
    }

    /**
     * Computes minimal spanning-tree bridge coordinates spaced 1 section (256 blocks) apart between
     * adjacent lattice nodes so ticketing them forces Folia's ThreadedRegionizer to merge independent
     * regions across a tick barrier, and unticketing them lets regions split again.
     */
    fun planBridgeNodes(nodes: List<RegionNode>): List<RegionNode> {
        if (nodes.size < 2) return emptyList()
        val byGrid = nodes.associateBy { it.row to it.col }
        val seenSections = nodes.map { it.sectionX to it.sectionZ }.toMutableSet()
        val bridges = mutableListOf<RegionNode>()

        for (node in nodes) {
            val neighbor = when {
                node.col > 0 -> byGrid[node.row to (node.col - 1)]
                node.row > 0 -> byGrid[(node.row - 1) to 0]
                else -> null
            } ?: continue

            val dx = node.blockX - neighbor.blockX
            val dz = node.blockZ - neighbor.blockZ
            val distance = max(abs(dx), abs(dz))
            val steps = distance / SECTION_BLOCK_SIZE
            val stepX = dx.coerceIn(-1, 1) * SECTION_BLOCK_SIZE
            val stepZ = dz.coerceIn(-1, 1) * SECTION_BLOCK_SIZE

            for (s in 1 until steps) {
                val bx = neighbor.blockX + stepX * s
                val bz = neighbor.blockZ + stepZ * s
                val candidate = RegionNode(
                    index = bridges.size,
                    row = node.row,
                    col = node.col,
                    blockX = bx,
                    blockY = node.blockY,
                    blockZ = bz,
                )
                if (seenSections.add(candidate.sectionX to candidate.sectionZ)) {
                    bridges += candidate
                }
            }
        }
        return bridges
    }

    fun renderCommand(template: String, node: RegionNode, world: String, pulse: Int): String =
        template
            .replace("{region}", node.index.toString())
            .replace("{index}", node.index.toString())
            .replace("{x}", node.blockX.toString())
            .replace("{y}", node.blockY.toString())
            .replace("{z}", node.blockZ.toString())
            .replace("{chunkX}", node.chunkX.toString())
            .replace("{chunkZ}", node.chunkZ.toString())
            .replace("{sectionX}", node.sectionX.toString())
            .replace("{sectionZ}", node.sectionZ.toString())
            .replace("{world}", world)
            .replace("{pulse}", pulse.toString())

    fun defaultAnchorSetupCommands(nodes: List<RegionNode>, world: String): List<String> =
        nodes.map { "execute in $world run forceload add ${it.blockX} ${it.blockZ}" }

    fun defaultAnchorCleanupCommands(nodes: List<RegionNode>, bridges: List<RegionNode>, world: String): List<String> =
        (bridges + nodes).map { "execute in $world run forceload remove ${it.blockX} ${it.blockZ}" }

    fun defaultPulseCommands(
        mode: Mode,
        nodes: List<RegionNode>,
        bridges: List<RegionNode>,
        world: String,
        pulse: Int,
    ): List<String> = buildList {
        for (node in nodes) {
            add("execute in $world run forceload query ${node.blockX} ${node.blockZ}")
        }
        if (mode == Mode.MERGE_SPLIT && bridges.isNotEmpty()) {
            val action = if (pulse % 2 == 0) "add" else "remove"
            for (bridge in bridges) {
                add("execute in $world run forceload $action ${bridge.blockX} ${bridge.blockZ}")
            }
        }
    }

    /** Render a self-contained dark-mode HTML report with an SVG Folia ThreadedRegionizer 16-chunk section grid. */
    fun renderTopologyHtml(
        nodes: List<RegionNode>,
        mode: Mode,
        spacingBlocks: Int,
    ): String {
        val maxCol = nodes.maxOfOrNull { it.col } ?: 0
        val maxRow = nodes.maxOfOrNull { it.row } ?: 0
        val cell = 92
        val pad = 48
        val svgW = (maxCol + 1) * cell + pad * 2
        val svgH = (maxRow + 1) * cell + pad * 2
        val svgElements = StringBuilder()
        if (mode == Mode.MERGE_SPLIT) {
            for (i in 0 until nodes.size) {
                for (j in i + 1 until nodes.size) {
                    val a = nodes[i]
                    val b = nodes[j]
                    if (abs(a.row - b.row) + abs(a.col - b.col) == 1) {
                        val x1 = pad + a.col * cell + cell / 2
                        val y1 = pad + a.row * cell + cell / 2
                        val x2 = pad + b.col * cell + cell / 2
                        val y2 = pad + b.row * cell + cell / 2
                        svgElements.append("""<line x1="$x1" y1="$y1" x2="$x2" y2="$y2" stroke="#f59e0b" stroke-width="2" stroke-dasharray="5,4"/>""")
                    }
                }
            }
        }
        val rowsHtml = StringBuilder()
        for (n in nodes) {
            val cx = pad + n.col * cell + cell / 2
            val cy = pad + n.row * cell + cell / 2
            svgElements.append("""<rect x="${cx - 32}" y="${cy - 32}" width="64" height="64" rx="8" fill="rgba(56,189,248,0.12)" stroke="#38bdf8" stroke-dasharray="3,3"/>""")
            svgElements.append("""<circle cx="$cx" cy="$cy" r="14" fill="#22c55e" stroke="#0b0f17" stroke-width="2"/>""")
            svgElements.append("""<text x="$cx" y="${cy + 4}" text-anchor="middle" fill="#0b0f17" font-size="11" font-weight="bold">R${n.index}</text>""")
            rowsHtml.append(
                "<tr><td><b>R${n.index}</b></td><td>(${n.blockX}, ${n.blockZ})</td><td>(${n.chunkX}, ${n.chunkZ})</td><td>(${n.sectionX}, ${n.sectionZ})</td></tr>\n",
            )
        }
        val minSectionStep = spacingBlocks / SECTION_BLOCK_SIZE
        val disjointOk = minSectionStep >= MIN_DISJOINT_SECTION_DISTANCE
        return """<!doctype html>
<html lang="en"><head><meta charset="utf-8"><title>mcstage Folia Region Topology (${nodes.size} regions)</title>
<style>
body{margin:0;padding:28px;background:#0b0f17;color:#e6edf7;font-family:system-ui,sans-serif}
.wrap{max-width:960px;margin:0 auto}.card{background:#121826;border:1px solid #26324d;border-radius:10px;padding:18px;margin-bottom:16px}
table{width:100%;border-collapse:collapse;font-size:13px}th,td{padding:8px 10px;border-bottom:1px solid #1e293b;text-align:left}
th{color:#93a4c3}code{font-family:ui-monospace,monospace;color:#38bdf8}
</style></head><body><div class="wrap">
<div class="card"><h1>Folia ThreadedRegionizer 16-Chunk Section Topology</h1>
<p>Regions: <b>${nodes.size}</b> &middot; Mode: <code>${mode.id}</code> &middot; Spacing: <b>${spacingBlocks} blocks</b> (${minSectionStep} sections) &middot; Chebyshev &ge; 3 Disjoint Guarantee: <b style="color:${if (disjointOk) "#22c55e" else "#ef4444"}">${if (disjointOk) "VERIFIED" else "OVERLAPPING"}</b></p>
<svg width="$svgW" height="$svgH" viewBox="0 0 $svgW $svgH" style="background:#080c14;border:1px solid #26324d;border-radius:8px;max-width:100%">$svgElements</svg></div>
<div class="card"><table><thead><tr><th>Region</th><th>Block (X, Z)</th><th>Chunk (X, Z)</th><th>Section (X, Z)</th></tr></thead><tbody>$rowsHtml</tbody></table></div></div></body></html>"""
    }
}
