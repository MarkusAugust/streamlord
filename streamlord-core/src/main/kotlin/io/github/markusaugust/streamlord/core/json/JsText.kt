package io.github.markusaugust.streamlord.core.json

import java.math.BigInteger

/** Text read the way JavaScript reads it, where Kotlin's own functions differ. */
internal object JsText {
    /**
     * JavaScript's white space and line terminators, what `trim()` removes and `Number()` skips:
     * Kotlin's `isWhitespace` also counts U+001C to U+001F and leaves out U+FEFF.
     */
    fun isSpace(c: Char): Boolean =
        c == '\t' || c == '\n' || c == '\u000B' || c == '\u000C' || c == '\r' || c == '\uFEFF' ||
            c == '\u2028' || c == '\u2029' || Character.getType(c) == Character.SPACE_SEPARATOR.toInt()

    /** `String.prototype.trim()`. */
    fun trim(s: String): String = s.trim(::isSpace)

    private val DECIMAL = Regex("[+-]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][+-]?\\d+)?")
    private val RADIX = Regex("0([xXoObB])([0-9a-fA-F]+)")

    /** `Number(s)` for a string: `NaN` for what JavaScript cannot read as a number, `0` for blank. */
    fun toNumber(s: String): Double {
        val t = trim(s)
        if (t.isEmpty()) return 0.0
        when (t) {
            "Infinity", "+Infinity" -> return Double.POSITIVE_INFINITY
            "-Infinity" -> return Double.NEGATIVE_INFINITY
        }
        if (DECIMAL.matches(t)) return t.toDouble()
        val radix = RADIX.matchEntire(t) ?: return Double.NaN
        val base =
            when (radix.groupValues[1].lowercase()) {
                "x" -> 16
                "o" -> 8
                else -> 2
            }
        return runCatching { BigInteger(radix.groupValues[2], base).toDouble() }.getOrDefault(Double.NaN)
    }
}
