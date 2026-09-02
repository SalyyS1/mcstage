package com.salyvn.mcstage.core

/** Reads the version the build stamped into resources, so `--version` cannot drift. */
object BuildInfo {
    const val NAME = "mcstage"

    val version: String by lazy {
        val stream = BuildInfo::class.java.getResourceAsStream("/mcstage-version.properties")
        stream?.bufferedReader()?.useLines { lines ->
            lines.firstOrNull { it.startsWith("version=") }?.removePrefix("version=")?.trim()
        } ?: "unknown"
    }

    val userAgent: String get() = "$NAME/$version (github.com/SalyyS1/mcstage)"
}
