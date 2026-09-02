package com.salyvn.mcstage.core

/** Where a platform's builds are published, and therefore which client asks for them. */
enum class ArtifactSource { FILL, PURPUR }

/**
 * The server software mcstage knows how to fetch and boot.
 *
 * Three, deliberately. PaperMC's fill API also serves Velocity, Waterfall and Travertine, and they are
 * absent rather than listed and untested: a proxy has no world, no EULA and no server.properties, so
 * supporting one means a second creation path and a second set of assumptions about a generated config
 * that binds to every interface by default. This tool claims to boot what it has been run against.
 */
enum class Platform(
    val id: String,
    val displayName: String,
    val source: ArtifactSource,
) {
    PAPER("paper", "Paper", ArtifactSource.FILL),
    FOLIA("folia", "Folia", ArtifactSource.FILL),
    PURPUR("purpur", "Purpur", ArtifactSource.PURPUR),
    ;

    companion object {
        fun of(id: String): Platform = entries.firstOrNull { it.id.equals(id, ignoreCase = true) }
            ?: throw IllegalArgumentException(
                "Unknown platform '$id'. Known: ${entries.joinToString(", ") { it.id }}.",
            )

        /**
         * The line each of these prints once it is accepting connections, matched on the timing part
         * alone so that a wording change downstream of it does not break the wait. The scenario key
         * `ready:` exists for the day the line itself changes.
         */
        const val DEFAULT_READY = """re:Done \([0-9.,]+s\)!"""

        /** What all three understand as "shut down cleanly". A scenario can override it. */
        const val DEFAULT_STOP = "stop"
    }
}
