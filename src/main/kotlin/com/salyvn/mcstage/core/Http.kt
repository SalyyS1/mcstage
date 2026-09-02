package com.salyvn.mcstage.core

import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration

/** A network call that failed in a way the user needs to read, not a stack trace. */
class DownloadFailed(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * The only part of this tool that touches the network, and it is used for exactly two things: asking a
 * platform's own API what it publishes, and fetching a jar it named.
 *
 * Both APIs ask callers to identify themselves, so every request carries a User-Agent naming the tool
 * and its repository. Nothing about a scenario, an instance or a plugin is ever sent anywhere.
 */
open class Http(
    private val userAgent: String = BuildInfo.userAgent,
    private val timeout: Duration = Duration.ofSeconds(20),
    /**
     * When set, no request is made at all. It lives here rather than only in the cache so that
     * `--offline` covers asking an API what it publishes as well as fetching what it named: a flag that
     * means "no network" has to mean it everywhere, and a version that has to be resolved is a network
     * call like any other.
     */
    private val offline: Boolean = false,
) {

    private val client: HttpClient by lazy {
        HttpClient.newBuilder()
            .connectTimeout(timeout)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()
    }

    /** @return the body, or null when the server answered 404. Any other failure throws. */
    open fun getString(url: String): String? {
        refuseIfOffline(url)
        val request = base(url).header("Accept", "application/json").GET().build()
        val response = try {
            client.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (e: Exception) {
            throw DownloadFailed("Could not reach $url: ${e.message ?: e::class.simpleName}", e)
        }
        return when (val status = response.statusCode()) {
            200 -> response.body()
            404 -> null
            else -> throw DownloadFailed("$url answered HTTP $status.")
        }
    }

    /**
     * Downloads to a sibling temporary file and moves it into place only after the digest matches, so
     * an interrupted download can never be mistaken for a complete one.
     */
    open fun download(url: String, target: Path, expected: Checksum?, onProgress: (Long) -> Unit = {}) {
        refuseIfOffline(url)
        Files.createDirectories(target.parent)
        val partial = target.resolveSibling(target.fileName.toString() + ".part")
        val request = base(url).GET().build()

        try {
            val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
            if (response.statusCode() != 200) {
                throw DownloadFailed("$url answered HTTP ${response.statusCode()}.")
            }
            response.body().use { input ->
                Files.newOutputStream(partial).use { output ->
                    val buffer = ByteArray(1 shl 16)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        total += read
                        onProgress(total)
                    }
                }
            }

            if (expected != null && !Digest.matches(partial, expected)) {
                val actual = Digest.of(partial, expected.algorithm)
                throw DownloadFailed(
                    "The file downloaded from $url does not match the ${expected.algorithm} its own " +
                        "API published. Expected ${expected.hex}, got $actual. The file was not kept.",
                )
            }
            Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: DownloadFailed) {
            Files.deleteIfExists(partial)
            throw e
        } catch (e: Exception) {
            Files.deleteIfExists(partial)
            throw DownloadFailed("Could not download $url: ${e.message ?: e::class.simpleName}", e)
        }
    }

    private fun refuseIfOffline(url: String) {
        if (offline) {
            throw DownloadFailed(
                "--offline was given, so $url was not asked. Name an exact version rather than latest, " +
                    "and make sure the build is already in the cache.",
            )
        }
    }

    private fun base(url: String) = HttpRequest.newBuilder()
        .uri(URI.create(url))
        .timeout(timeout.multipliedBy(15)) // a server jar is tens of megabytes
        .header("User-Agent", userAgent)
}
