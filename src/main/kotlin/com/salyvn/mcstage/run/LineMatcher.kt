package com.salyvn.mcstage.run

/** One line of server output, numbered and stamped with how long after start-up it arrived. */
data class LogLine(val number: Int, val elapsedMillis: Long, val text: String) {
    override fun toString() = "%6.2fs  %s".format(java.util.Locale.ROOT, elapsedMillis / 1000.0, text)
}

/**
 * What a scenario means by a line of interest.
 *
 * A bare string is a substring, which is what people write when they mean "this appeared". A `re:`
 * prefix makes it a regular expression, for the times a substring is not enough. Nothing in between:
 * guessing which one was meant produces a matcher that works until the day a plugin is named `C++`.
 */
sealed interface LineMatcher {

    fun matches(text: String): Boolean

    val describe: String

    data class Substring(val needle: String) : LineMatcher {
        override fun matches(text: String) = text.contains(needle)
        override val describe get() = "contains \"$needle\""
    }

    data class Pattern(val source: String) : LineMatcher {
        private val regex = Regex(source)
        override fun matches(text: String) = regex.containsMatchIn(text)
        override val describe get() = "matches /$source/"
    }

    companion object {
        const val REGEX_PREFIX = "re:"

        fun parse(spec: String): LineMatcher =
            if (spec.startsWith(REGEX_PREFIX)) Pattern(spec.removePrefix(REGEX_PREFIX))
            else Substring(spec)
    }
}
