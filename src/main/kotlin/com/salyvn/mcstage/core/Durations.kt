package com.salyvn.mcstage.core

import java.time.Duration

/**
 * Durations as people write them in a scenario file: `30s`, `2m`, `500ms`, or a bare number of seconds.
 *
 * A bare number means seconds because every timeout in this tool is seconds-sized, and reading `60` as a
 * minute of waiting is what somebody writing it means.
 */
object Durations {

    fun parse(text: String, what: String = "duration"): Duration {
        val cleaned = text.trim().lowercase().replace(" ", "")
        if (cleaned.isEmpty()) throw SetupError("Empty $what.")

        val (number, unit) = split(cleaned)
        val amount = number.toDoubleOrNull()
            ?: throw SetupError("'$text' is not a $what. Write something like 30s, 2m or 500ms.")
        if (amount < 0) throw SetupError("A $what cannot be negative: '$text'.")

        return when (unit) {
            "", "s", "sec", "secs", "second", "seconds" ->
                Duration.ofMillis((amount * 1000).toLong())

            "ms", "milli", "millis", "millisecond", "milliseconds" ->
                Duration.ofMillis(amount.toLong())

            "m", "min", "mins", "minute", "minutes" ->
                Duration.ofMillis((amount * 60_000).toLong())

            "h", "hour", "hours" ->
                Duration.ofMillis((amount * 3_600_000).toLong())

            else -> throw SetupError("Unknown unit '$unit' in $what '$text'. Use ms, s, m or h.")
        }
    }

    fun format(duration: Duration): String {
        val millis = duration.toMillis()
        return when {
            millis < 1000 -> "${millis}ms"
            millis % 60_000 == 0L && millis >= 60_000 -> "${millis / 60_000}m"
            else -> "%.1fs".format(java.util.Locale.ROOT, millis / 1000.0)
        }
    }

    private fun split(cleaned: String): Pair<String, String> {
        val boundary = cleaned.indexOfFirst { !(it.isDigit() || it == '.') }
        return if (boundary < 0) cleaned to "" else cleaned.substring(0, boundary) to cleaned.substring(boundary)
    }
}
