package com.salyvn.mcstage.api

import com.salyvn.mcstage.core.ArtifactRef
import com.salyvn.mcstage.core.BuildIndex
import com.salyvn.mcstage.core.Checksum
import com.salyvn.mcstage.core.DownloadFailed
import com.salyvn.mcstage.core.Http
import com.salyvn.mcstage.core.Platform
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Purpur's own API, verified against the live service on 2026-08-25.
 *
 * Two things differ from fill and both are visible to the user rather than smoothed over. Purpur lists
 * its versions oldest first, so they are reversed here to match every other listing in the tool. And a
 * Purpur build publishes an `md5` and no stronger digest, so that is what gets checked; MD5 confirms
 * the download arrived intact, which is what it is used for, and it is a weaker guarantee than the
 * sha256 fill publishes. The tool says which algorithm it verified rather than implying they are equal.
 */
class PurpurClient(
    private val http: Http,
    private val baseUrl: String = "https://api.purpurmc.org/v2",
) : BuildIndex {

    private val json = Json { ignoreUnknownKeys = true }

    override fun versions(platform: Platform): List<String> {
        require(platform == Platform.PURPUR) { "PurpurClient was asked about ${platform.id}." }
        val body = http.getString("$baseUrl/purpur")
            ?: throw DownloadFailed("The Purpur API did not answer $baseUrl/purpur.")
        return json.decodeFromString(ProjectDto.serializer(), body).versions.reversed()
    }

    override fun resolve(platform: Platform, version: String, build: String?): ArtifactRef {
        require(platform == Platform.PURPUR) { "PurpurClient was asked about ${platform.id}." }
        val chosen = build ?: LATEST
        val body = http.getString("$baseUrl/purpur/$version/$chosen")
            ?: throw DownloadFailed(
                if (build == null) "Purpur $version has no builds."
                else "Purpur $version has no build $build.",
            )
        val dto = json.decodeFromString(BuildDto.serializer(), body)

        // A build that did not finish has no jar behind it, and its md5 belongs to nothing.
        if (dto.result != null && !dto.result.equals("SUCCESS", ignoreCase = true)) {
            throw DownloadFailed("Purpur $version build ${dto.build} ended as ${dto.result}.")
        }
        val md5 = dto.md5
            ?: throw DownloadFailed(
                "The Purpur API published no md5 for $version build ${dto.build}, so it will not be " +
                    "installed.",
            )

        return ArtifactRef(
            platform = platform,
            version = version,
            build = dto.build,
            // Purpur serves the jar from a path, not from a named file, so the name is composed the
            // way Purpur's own downloads page composes it.
            fileName = "purpur-$version-${dto.build}.jar",
            url = "$baseUrl/purpur/$version/${dto.build}/download",
            checksum = Checksum.md5(md5),
            sizeBytes = null,
        )
    }

    @Serializable
    private data class ProjectDto(
        val project: String = "",
        val versions: List<String> = emptyList(),
    )

    @Serializable
    private data class BuildDto(
        val version: String = "",
        val build: String = "",
        val result: String? = null,
        val md5: String? = null,
    )

    private companion object {
        const val LATEST = "latest"
    }
}
