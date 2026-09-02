package com.salyvn.mcstage.core

/** One published build of one platform: what it is called, where it is, and what it hashes to. */
data class ArtifactRef(
    val platform: Platform,
    val version: String,
    val build: String,
    val fileName: String,
    val url: String,
    val checksum: Checksum,
    val sizeBytes: Long?,
) {
    val label: String get() = "${platform.id} $version build $build"
}

/**
 * A platform's build index, narrowed to the three questions this tool asks: what versions exist, what
 * the newest build of one is, and where a named build lives.
 *
 * There is no method for "download it" here. Fetching is the cache's job, so that a build resolved
 * from the network and a build already on disk take exactly the same path afterwards.
 */
interface BuildIndex {

    /** Every published version, newest first. */
    fun versions(platform: Platform): List<String>

    /** @param build a build number, or null for the newest. */
    fun resolve(platform: Platform, version: String, build: String? = null): ArtifactRef

    /**
     * Turns what a person typed into a version this index publishes.
     *
     * `latest` means the newest stable version. A family like `1.21` means the newest stable version
     * in it, which is what somebody testing "on 1.21" almost always means. An exact name is always
     * taken literally, including a release candidate, because asking for one by name is deliberate.
     */
    fun resolveVersion(platform: Platform, requested: String, allowPrerelease: Boolean = false): String {
        val available = versions(platform)
        if (available.isEmpty()) throw NoSuchElementException("${platform.id} publishes no versions.")
        if (requested != LATEST && available.contains(requested)) return requested

        val stable = if (allowPrerelease) available else available.filterNot { isPrerelease(it) }
        val pool = stable.ifEmpty { available }

        if (requested == LATEST) return pool.first()

        // `1.21` covers `1.21.11`, and — when prereleases are in the pool at all — `1.21-rc-1` as well,
        // because a version's own candidate belongs to the series somebody named.
        val family = pool.firstOrNull {
            it == requested || it.startsWith("$requested.") || it.startsWith("$requested-")
        }
        if (family != null) return family

        val near = available.take(6).joinToString(", ")
        throw NoSuchElementException(
            "${platform.id} does not publish version '$requested'. Newest available: $near.",
        )
    }

    companion object {
        const val LATEST = "latest"

        /** Names publishers use for builds that are not the recommended download. */
        private val PRERELEASE = listOf("-rc", "-pre", "-snapshot", "-experimental")

        fun isPrerelease(version: String): Boolean {
            val lower = version.lowercase()
            return PRERELEASE.any { lower.contains(it) }
        }
    }
}
