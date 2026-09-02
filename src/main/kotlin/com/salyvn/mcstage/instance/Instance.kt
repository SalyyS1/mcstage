package com.salyvn.mcstage.instance

import com.salyvn.mcstage.core.BuildInfo
import com.salyvn.mcstage.core.SetupError
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/** What mcstage wrote into an instance, so a later command does not have to guess or ask again. */
@Serializable
data class InstanceDescriptor(
    val tool: String = BuildInfo.NAME,
    val toolVersion: String = "",
    val platform: String = "",
    val version: String = "",
    val build: String = "",
    val jarFileName: String = "",
    val checksum: String = "",
    val created: String = "",
    val port: Int = 25565,
    val bind: String = "127.0.0.1",
    val memory: String = "2G",
    val eulaAccepted: Boolean = false,
)

/**
 * A directory mcstage created and can recognise again.
 *
 * The jar is always called `server.jar` no matter which build it came from, because the command line
 * that starts it should not change when the build does. What it actually is lives in `mcstage.json`
 * next to it, digest included.
 */
class Instance(val dir: Path) {

    val jar: Path get() = dir.resolve("server.jar")
    val pluginsDir: Path get() = dir.resolve("plugins")
    val eulaFile: Path get() = dir.resolve("eula.txt")
    val propertiesFile: Path get() = dir.resolve("server.properties")
    val descriptorFile: Path get() = dir.resolve("mcstage.json")

    fun isCreated(): Boolean = descriptorFile.exists() && jar.exists()

    fun descriptor(): InstanceDescriptor {
        if (!descriptorFile.exists()) {
            throw SetupError("$dir is not an mcstage instance: there is no mcstage.json in it.")
        }
        return try {
            JSON.decodeFromString(InstanceDescriptor.serializer(), Files.readString(descriptorFile))
        } catch (e: Exception) {
            throw SetupError("${descriptorFile.name} in $dir could not be read: ${e.message}", e)
        }
    }

    fun writeDescriptor(descriptor: InstanceDescriptor) {
        Files.createDirectories(dir)
        Files.writeString(descriptorFile, JSON.encodeToString(InstanceDescriptor.serializer(), descriptor))
    }

    /** Reads the file rather than trusting the descriptor: the user may have edited either one. */
    fun eulaAccepted(): Boolean {
        if (!eulaFile.exists()) return false
        return Files.readAllLines(eulaFile).any {
            it.trim().replace(" ", "").equals("eula=true", ignoreCase = true)
        }
    }

    fun acceptEula() {
        Files.createDirectories(dir)
        Files.writeString(
            eulaFile,
            """
            # Written by ${BuildInfo.NAME} because --accept-eula was passed on the command line.
            # It records agreement to the Minecraft EULA: https://aka.ms/MinecraftEULA
            eula=true
            """.trimIndent() + "\n",
        )
    }

    fun installedPlugins(): List<String> =
        if (!pluginsDir.isDirectory()) emptyList()
        else pluginsDir.listDirectoryEntries()
            .filter { it.isRegularFile() && it.name.endsWith(".jar", ignoreCase = true) }
            .map { it.name }
            .sorted()

    /**
     * Copies plugin jars in, accepting a file, a directory of jars, or a glob such as `plugin-*.jar`,
     * because the thing being tested is usually whatever the last build produced and its name carries a
     * version that changes.
     *
     * @return the file names now in the plugins directory.
     */
    fun installPlugins(sources: List<String>): List<String> {
        if (sources.isEmpty()) return installedPlugins()
        Files.createDirectories(pluginsDir)
        for (source in sources) {
            val matches = expand(source)
            if (matches.isEmpty()) throw SetupError("No plugin jar matched '$source'.")
            for (jar in matches) {
                Files.copy(
                    jar,
                    pluginsDir.resolve(jar.name),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                )
            }
        }
        return installedPlugins()
    }

    private fun expand(source: String): List<Path> {
        if (!source.contains('*') && !source.contains('?')) {
            val path = Path.of(source)
            return when {
                path.isRegularFile() -> listOf(path)
                path.isDirectory() -> path.listDirectoryEntries()
                    .filter { it.isRegularFile() && it.name.endsWith(".jar", ignoreCase = true) }
                    .sorted()

                else -> emptyList()
            }
        }

        // A glob cannot be turned into a Path first: Windows rejects an asterisk in a path outright, so
        // `Path.of("build/libs/x-*.jar")` throws before there is anything to match against. Split the
        // text instead, and only the directory part becomes a path.
        val boundary = source.lastIndexOfAny(charArrayOf('/', '\\'))
        val parent = Path.of(if (boundary < 0) "." else source.substring(0, boundary).ifEmpty { "/" })
        if (!parent.isDirectory()) return emptyList()

        val matcher = parent.fileSystem.getPathMatcher("glob:${source.substring(boundary + 1)}")
        return parent.listDirectoryEntries()
            .filter { it.isRegularFile() && matcher.matches(it.fileName) }
            .sorted()
    }

    private companion object {
        val JSON = Json { prettyPrint = true; ignoreUnknownKeys = true }
    }
}
