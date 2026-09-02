package com.salyvn.mcstage.instance

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists

/**
 * A `.properties` file, read and written without losing the keys mcstage does not know about.
 *
 * The server rewrites this file itself on every boot, so nothing here tries to be clever about
 * formatting. What it does guarantee is that editing one key leaves the rest of somebody's file alone,
 * and that the output is byte-for-byte the same given the same input, which matters when the file ends
 * up in a diff.
 */
class ServerProperties private constructor(private val entries: LinkedHashMap<String, String>) {

    operator fun get(key: String): String? = entries[key]

    operator fun set(key: String, value: String) {
        entries[key] = value
    }

    fun putIfAbsent(key: String, value: String) {
        entries.putIfAbsent(key, value)
    }

    fun asMap(): Map<String, String> = LinkedHashMap(entries)

    fun write(path: Path, header: String? = null) {
        val text = buildString {
            header?.lineSequence()?.forEach { appendLine("# $it") }
            for ((key, value) in entries) appendLine("$key=${escape(value)}")
        }
        Files.createDirectories(path.parent)
        Files.writeString(path, text)
    }

    companion object {

        fun empty() = ServerProperties(LinkedHashMap())

        fun read(path: Path): ServerProperties {
            val entries = LinkedHashMap<String, String>()
            if (!path.exists()) return ServerProperties(entries)
            for (raw in Files.readAllLines(path)) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) continue
                val separator = line.indexOfFirst { it == '=' || it == ':' }
                if (separator <= 0) continue
                entries[line.substring(0, separator).trim()] = unescape(line.substring(separator + 1).trim())
            }
            return ServerProperties(entries)
        }

        /** Only what a properties file cannot carry literally: a backslash, and anything non-ASCII. */
        private fun escape(value: String): String = buildString {
            for (character in value) {
                when {
                    character == '\\' -> append("\\\\")
                    character == '\n' -> append("\\n")
                    character.code in 0x20..0x7E -> append(character)
                    else -> append("\\u%04x".format(character.code))
                }
            }
        }

        private fun unescape(value: String): String {
            if (!value.contains('\\')) return value
            val out = StringBuilder(value.length)
            var index = 0
            while (index < value.length) {
                val character = value[index]
                if (character != '\\' || index == value.lastIndex) {
                    out.append(character)
                    index++
                    continue
                }
                when (val next = value[index + 1]) {
                    'n' -> { out.append('\n'); index += 2 }
                    't' -> { out.append('\t'); index += 2 }
                    'u' -> {
                        val hex = value.substring(index + 2, (index + 6).coerceAtMost(value.length))
                        val code = hex.toIntOrNull(16)
                        if (code != null && hex.length == 4) {
                            out.append(code.toChar())
                            index += 6
                        } else {
                            out.append(next)
                            index += 2
                        }
                    }
                    else -> { out.append(next); index += 2 }
                }
            }
            return out.toString()
        }
    }
}
