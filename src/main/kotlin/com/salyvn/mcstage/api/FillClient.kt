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
 * PaperMC's fill API, which publishes Paper, Folia and Velocity.
 *
 * Shapes were verified against the live service on 2026-08-25: `/v3/projects` answers
 * `{"projects":[{"project":{"id","name"},"versions":{family:[version,...]}}]}` with the newest version
 * first inside each family, and a build carries `downloads["server:default"]` with a `checksums.sha256`
 * and a URL under fill-data that is addressed by that same digest.
 *
 * That content-addressed URL is the reason the cache is keyed by digest rather than by version: the
 * name of a file says what somebody called it, and the digest says what it is.
 */
class FillClient(
    private val http: Http,
    private val baseUrl: String = "https://fill.papermc.io/v3",
) : BuildIndex {

    private val json = Json { ignoreUnknownKeys = true }
    private var projects: List<ProjectDto>? = null

    override fun versions(platform: Platform): List<String> {
        val entry = projects().firstOrNull { it.project.id.equals(platform.id, ignoreCase = true) }
            ?: throw DownloadFailed("The fill API does not publish a project called '${platform.id}'.")
        // Families arrive newest first and so do the versions inside one, which is the order a person
        // reading `mcstage versions` expects, so it is kept rather than re-sorted.
        return entry.versions.values.flatten()
    }

    override fun resolve(platform: Platform, version: String, build: String?): ArtifactRef {
        val path = "/projects/${platform.id}/versions/$version/builds/" + (build ?: "latest")
        val body = http.getString(baseUrl + path)
            ?: throw DownloadFailed(
                if (build == null) "${platform.id} $version has no builds on the fill API."
                else "${platform.id} $version has no build $build on the fill API.",
            )
        val dto = json.decodeFromString(BuildDto.serializer(), body)
        val download = dto.downloads[SERVER_DOWNLOAD]
            ?: throw DownloadFailed(
                "Build ${dto.id} of ${platform.id} $version publishes no '$SERVER_DOWNLOAD' download; " +
                    "it offers ${dto.downloads.keys.joinToString(", ").ifEmpty { "nothing" }}.",
            )
        val sha256 = download.checksums["sha256"]
            ?: throw DownloadFailed(
                "The fill API published no sha256 for ${download.name}, so it will not be installed.",
            )

        return ArtifactRef(
            platform = platform,
            version = version,
            build = dto.id.toString(),
            fileName = download.name,
            url = download.url,
            checksum = Checksum.sha256(sha256),
            sizeBytes = download.size,
        )
    }

    private fun projects(): List<ProjectDto> = projects ?: run {
        val body = http.getString("$baseUrl/projects")
            ?: throw DownloadFailed("The fill API did not answer $baseUrl/projects.")
        json.decodeFromString(ProjectsDto.serializer(), body).projects.also { projects = it }
    }

    @Serializable
    private data class ProjectsDto(val projects: List<ProjectDto> = emptyList())

    @Serializable
    private data class ProjectDto(
        val project: IdDto = IdDto(),
        val versions: Map<String, List<String>> = emptyMap(),
    )

    @Serializable
    private data class IdDto(val id: String = "", val name: String = "")

    @Serializable
    private data class BuildDto(
        val id: Int = 0,
        val time: String? = null,
        val channel: String? = null,
        val downloads: Map<String, DownloadDto> = emptyMap(),
    )

    @Serializable
    private data class DownloadDto(
        val name: String = "",
        val url: String = "",
        val size: Long? = null,
        val checksums: Map<String, String> = emptyMap(),
    )

    private companion object {
        /**
         * The key under which every one of these projects publishes the jar you run, the proxy
         * included: fill names the artifact by its role, not by the project.
         */
        const val SERVER_DOWNLOAD = "server:default"
    }
}
