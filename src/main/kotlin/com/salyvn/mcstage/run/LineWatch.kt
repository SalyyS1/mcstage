package com.salyvn.mcstage.run

/**
 * Watches every line the server prints, for two different purposes that are deliberately kept apart.
 *
 * Forbidden patterns are what the scenario said must not happen, and one of them appearing fails the run.
 * The counts are just counts: how many lines carried a warning level, how many carried an error level, how
 * many looked like a thrown exception. They are reported because they are useful to see and never turned
 * into a verdict, because "this server logged three warnings" is not a defect and this tool does not get
 * to decide otherwise.
 */
class LineWatch(private val forbidden: List<LineMatcher>) {

    private val violations = mutableListOf<Pair<LineMatcher, LogLine>>()
    private var warnings = 0
    private var errors = 0
    private var exceptions = 0

    fun inspect(line: LogLine) {
        if (LEVEL_WARN.containsMatchIn(line.text)) warnings++
        if (LEVEL_ERROR.containsMatchIn(line.text)) errors++
        if (THROWN.containsMatchIn(line.text)) exceptions++

        for (matcher in forbidden) {
            if (matcher.matches(line.text)) violations += matcher to line
        }
    }

    fun firstViolation(): String? {
        val (matcher, line) = violations.firstOrNull() ?: return null
        val more = if (violations.size > 1) " (${violations.size} lines matched a forbidden pattern)" else ""
        return "line ${line.number} ${matcher.describe}, which the scenario forbids$more: ${line.text.trim()}"
    }

    fun firstViolationLine(): LogLine? = violations.firstOrNull()?.second

    fun contribute(facts: MutableMap<String, String>) {
        if (warnings > 0) facts["warning lines"] = warnings.toString()
        if (errors > 0) facts["error lines"] = errors.toString()
        if (exceptions > 0) facts["exception lines"] = exceptions.toString()
    }

    private companion object {
        /**
         * The level as it appears in console output, which puts it in brackets after the time. Matching
         * the token rather than a whole layout keeps this working for the file layout too, where the
         * level follows a thread name.
         */
        val LEVEL_WARN = Regex("""\bWARN(ING)?]""")
        val LEVEL_ERROR = Regex("""\b(ERROR|FATAL)]""")

        /** A class name shaped like something that gets thrown, which is what a stack trace starts with. */
        val THROWN = Regex("""\b[A-Z]\w*(Exception|Error)\b""")
    }
}
