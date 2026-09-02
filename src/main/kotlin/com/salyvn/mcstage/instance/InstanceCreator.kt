package com.salyvn.mcstage.instance

import com.salyvn.mcstage.api.BuildIndexes
import com.salyvn.mcstage.cache.JarCache
import com.salyvn.mcstage.core.BuildIndex
import com.salyvn.mcstage.core.BuildInfo
import com.salyvn.mcstage.core.Platform
import com.salyvn.mcstage.core.SetupError
import java.nio.file.Files
import java.time.Instant
import java.nio.file.Path

/** Everything the user chose about an instance, in one object so the CLI and a scenario share a path. */
data class CreateRequest(
    val platform: Platform,
    val version: String = BuildIndex.LATEST,
    val build: String? = null,
    val port: Int = 25565,
    val bind: String = "127.0.0.1",
    val memory: String = "2G",
    val acceptEula: Boolean = false,
    val plugins: List<String> = emptyList(),
    val properties: Map<String, String> = emptyMap(),
    val allowPrerelease: Boolean = false,
    val force: Boolean = false,
)

data class CreateResult(
    val instance: Instance,
    val descriptor: InstanceDescriptor,
    val plugins: List<String>,
    val notes: List<String>,
    /** False when the instance was already there and nothing was fetched, which `--offline` relies on. */
    val resolved: Boolean = true,
)

/**
 * Turns a request into a directory a server can be started in.
 *
 * Two defaults here are deliberate and both are printed rather than assumed. The instance binds to
 * 127.0.0.1, so a test server is not reachable from the network it happens to be on; and it runs with
 * `online-mode=false`, because a throwaway instance has no business authenticating anybody. Those two
 * belong together: offline authentication on an interface the whole network can reach is an open server,
 * and that is not something a harness should leave behind on somebody's machine.
 *
 * The EULA is never accepted on the user's behalf. Passing `--accept-eula` writes the file; not passing
 * it leaves the instance created and unbootable, and the run command says exactly why.
 */
class InstanceCreator(
    private val cache: JarCache,
    private val indexes: BuildIndexes,
) {

    fun create(dir: Path, request: CreateRequest, onEvent: (String) -> Unit = {}): CreateResult {
        val notes = mutableListOf<String>()
        val instance = Instance(dir)

        if (instance.isCreated() && !request.force) {
            val existing = instance.descriptor()
            if (existing.platform != request.platform.id) {
                throw SetupError(
                    "$dir already holds a ${existing.platform} ${existing.version} instance. Use --force " +
                        "to replace it, or pick another directory.",
                )
            }
            // Resolving a version and reading a build are both network calls, so with the network turned
            // off an instance that is already here is used as it is rather than re-derived. That is what
            // makes `--offline` mean something for a scenario as well as for a download.
            if (cache.offline) {
                onEvent("offline: using the ${existing.platform} ${existing.version} instance already in $dir")
                return finish(instance, request, existing.version, existing.build, existing.jarFileName,
                    existing.checksum, notes, resolved = false)
            }
        }

        val index = indexes.of(request.platform)
        val version = index.resolveVersion(request.platform, request.version, request.allowPrerelease)
        if (version != request.version) onEvent("${request.version} resolves to $version")

        val ref = index.resolve(request.platform, version, request.build)
        val cached = cache.ensure(ref, onEvent)
        JarCache.link(cached, instance.jar)

        return finish(
            instance = instance,
            request = request,
            version = version,
            build = ref.build,
            jarFileName = ref.fileName,
            checksum = ref.checksum.toString(),
            notes = notes,
            resolved = true,
        )
    }

    private fun finish(
        instance: Instance,
        request: CreateRequest,
        version: String,
        build: String,
        jarFileName: String,
        checksum: String,
        notes: MutableList<String>,
        resolved: Boolean,
    ): CreateResult {
        Files.createDirectories(instance.pluginsDir)
        val plugins = instance.installPlugins(request.plugins)

        writeProperties(instance, request, version, notes)

        if (request.acceptEula) {
            instance.acceptEula()
        } else if (!instance.eulaAccepted()) {
            notes += "The Minecraft EULA has not been accepted, so this instance will not boot. Pass " +
                "--accept-eula to write eula.txt."
        }

        val descriptor = InstanceDescriptor(
            toolVersion = BuildInfo.version,
            platform = request.platform.id,
            version = version,
            build = build,
            jarFileName = jarFileName,
            checksum = checksum,
            created = Instant.now().toString(),
            port = request.port,
            bind = request.bind,
            memory = request.memory,
            eulaAccepted = instance.eulaAccepted(),
        )
        instance.writeDescriptor(descriptor)

        return CreateResult(instance, descriptor, plugins, notes, resolved)
    }

    /**
     * Keys the user already set are left alone, so re-creating an instance to move it to a newer build
     * does not silently undo an edit made to test something.
     */
    private fun writeProperties(
        instance: Instance,
        request: CreateRequest,
        version: String,
        notes: MutableList<String>,
    ) {
        val properties = ServerProperties.read(instance.propertiesFile)

        properties["server-port"] = request.port.toString()
        properties["server-ip"] = request.bind
        properties.putIfAbsent("online-mode", "false")
        properties.putIfAbsent("motd", "${BuildInfo.NAME} ${request.platform.id} $version")
        // A named seed makes two runs of the same scenario the same world, which is the difference
        // between a reproducible test and a coin toss.
        properties.putIfAbsent("level-seed", BuildInfo.NAME)
        properties.putIfAbsent("level-name", "world")
        properties.putIfAbsent("max-players", "10")
        properties.putIfAbsent("view-distance", "5")
        properties.putIfAbsent("simulation-distance", "5")
        properties.putIfAbsent("spawn-protection", "0")
        // Commands go in over the process's own stdin, so neither remote console nor query is needed;
        // both are turned off rather than left to a default.
        properties.putIfAbsent("enable-rcon", "false")
        properties.putIfAbsent("enable-query", "false")

        for ((key, value) in request.properties) properties[key] = value

        if (properties["online-mode"] == "false" && properties["server-ip"] !in LOOPBACK) {
            notes += "online-mode is false and the server binds to ${properties["server-ip"]}, which is " +
                "not loopback: anyone who can reach that address can join as any name."
        }

        properties.write(
            instance.propertiesFile,
            header = "Written by ${BuildInfo.NAME} ${BuildInfo.version}. Edits survive re-creation; " +
                "server-port and server-ip do not.",
        )
    }

    private companion object {
        val LOOPBACK = setOf("127.0.0.1", "::1", "localhost")
    }
}
