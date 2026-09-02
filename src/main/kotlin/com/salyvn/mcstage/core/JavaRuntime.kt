package com.salyvn.mcstage.core

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.exists
import kotlin.io.path.isExecutable

/**
 * The Java that will run the server, found and then asked what it is.
 *
 * mcstage does not decide whether a Java is new enough for a given Minecraft version. That mapping
 * changes with every release and would be a claim this tool cannot check, so instead the detected
 * version is reported with every run and the server is allowed to refuse for itself — its own refusal
 * is accurate by definition, and it lands in the transcript next to the version that caused it.
 */
data class JavaRuntime(val binary: Path, val version: String, val vmName: String) {

    /** 21 from "21.0.5", 8 from "1.8.0_452", null when the string is not shaped like either. */
    val major: Int? by lazy {
        val cleaned = version.trim()
        val head = cleaned.substringBefore('-').split('.', '_').filter { it.isNotEmpty() }
        when {
            head.isEmpty() -> null
            head[0] == "1" -> head.getOrNull(1)?.toIntOrNull()
            else -> head[0].toIntOrNull()
        }
    }

    val describe: String get() = "$version ($vmName)"

    companion object {

        fun detect(explicit: String? = null): JavaRuntime {
            val candidate = locate(explicit)
            val settings = ask(candidate)
                ?: throw SetupError(
                    "Could not run '$candidate'. Point mcstage at a JDK with --java, JAVA_HOME or " +
                        "MCSTAGE_JAVA.",
                )
            return JavaRuntime(
                binary = candidate,
                version = settings["java.version"] ?: "unknown",
                vmName = settings["java.vm.name"] ?: "unknown",
            )
        }

        private fun locate(explicit: String?): Path {
            explicit?.let { return asBinary(Paths.get(it)) }
            System.getenv("MCSTAGE_JAVA")?.takeIf { it.isNotBlank() }?.let { return asBinary(Paths.get(it)) }
            System.getenv("JAVA_HOME")?.takeIf { it.isNotBlank() }?.let {
                val home = asBinary(Paths.get(it))
                if (home.exists()) return home
            }
            // Nothing said where Java is, so the one on PATH is the one the user would have typed.
            return Paths.get(executableName())
        }

        /** Accepts either a JDK home or the binary itself, because both are what people have to hand. */
        private fun asBinary(candidate: Path): Path {
            if (Files.isDirectory(candidate)) return candidate.resolve("bin").resolve(executableName())
            return candidate
        }

        private fun executableName() =
            if (System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)) {
                "java.exe"
            } else {
                "java"
            }

        /**
         * `-XshowSettings:properties -version` prints the runtime's own view of itself, which beats
         * parsing the version banner: the banner's wording differs between vendors and the properties
         * do not.
         */
        private fun ask(binary: Path): Map<String, String>? {
            if (binary.isAbsolute && !binary.exists()) return null
            val process = try {
                ProcessBuilder(binary.toString(), "-XshowSettings:properties", "-version")
                    .redirectErrorStream(true)
                    .start()
            } catch (_: Exception) {
                return null
            }
            val text = process.inputStream.bufferedReader().use { it.readText() }
            if (!process.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return null
            }
            if (process.exitValue() != 0) return null

            val settings = mutableMapOf<String, String>()
            for (line in text.lineSequence()) {
                val trimmed = line.trim()
                val separator = trimmed.indexOf(" = ")
                if (separator <= 0) continue
                settings[trimmed.substring(0, separator)] = trimmed.substring(separator + 3).trim()
            }
            return settings
        }
    }
}
