package io.github.markusaugust.streamlord.core.json

import io.github.markusaugust.streamlord.core.SignalsCodecException
import java.math.BigDecimal
import java.math.BigInteger

/**
 * Writes plain Kotlin values as compact JSON, with no dependencies.
 *
 * Understands `null`, `Boolean`, `Number`, `String`, `Char`, `Enum` (by name), `Map` (keys via
 * `toString`), `Iterable`, `Sequence`, arrays and [JsonValue]. It does **not** reflect over data
 * classes; that is what the codec adapters (`streamlord-json-kotlinx`, `streamlord-json-jackson`)
 * are for. Attempting it raises [SignalsCodecException] with a clear message rather than a
 * silent `toString()`.
 *
 * The output is safe to embed in a JavaScript context: U+2028, U+2029 and `</` are escaped,
 * which matters because Datastar evaluates `data-signals` as an expression.
 */
public object JsonWriter {
    public const val DEFAULT_MAX_DEPTH: Int = 64

    /** Encode [value] as JSON text. */
    public fun write(value: Any?, maxDepth: Int = DEFAULT_MAX_DEPTH): String =
        StringBuilder().also { appendValue(it, value, 0, maxDepth) }.toString()

    /** Encode a JSON string literal, quotes included. Also a valid JavaScript string literal. */
    public fun writeString(value: String): String = StringBuilder(value.length + 2).also { appendString(it, value) }.toString()

    private fun appendValue(sb: StringBuilder, value: Any?, depth: Int, maxDepth: Int) {
        if (depth > maxDepth) throw SignalsCodecException("JSON nesting deeper than $maxDepth levels (cyclic structure?)")
        when (value) {
            null, JsonNull -> sb.append("null")
            is JsonValue -> appendJsonValue(sb, value, depth, maxDepth)
            is Boolean -> sb.append(value)
            is String -> appendString(sb, value)
            is Char -> appendString(sb, value.toString())
            is Number -> appendNumber(sb, value)
            is Enum<*> -> appendString(sb, value.name)
            is Map<*, *> -> appendMap(sb, value, depth, maxDepth)
            is Iterable<*> -> appendIterable(sb, value, depth, maxDepth)
            is Sequence<*> -> appendIterable(sb, value.asIterable(), depth, maxDepth)
            is Array<*> -> appendIterable(sb, value.asIterable(), depth, maxDepth)
            is IntArray -> appendIterable(sb, value.asIterable(), depth, maxDepth)
            is LongArray -> appendIterable(sb, value.asIterable(), depth, maxDepth)
            is DoubleArray -> appendIterable(sb, value.asIterable(), depth, maxDepth)
            is BooleanArray -> appendIterable(sb, value.asIterable(), depth, maxDepth)
            else -> throw SignalsCodecException(
                "JsonWriter cannot encode ${value::class.qualifiedName}. " +
                    "Use a Map, or configure a SignalsCodec adapter (streamlord-json-kotlinx or streamlord-json-jackson).",
            )
        }
    }

    private fun appendJsonValue(sb: StringBuilder, value: JsonValue, depth: Int, maxDepth: Int) {
        when (value) {
            is JsonObject -> appendMap(sb, value, depth, maxDepth)
            is JsonArray -> appendIterable(sb, value, depth, maxDepth)
            is JsonString -> appendString(sb, value.value)
            is JsonNumber -> sb.append(value.text)
            is JsonBoolean -> sb.append(value.value)
            JsonNull -> sb.append("null")
        }
    }

    private fun appendMap(sb: StringBuilder, map: Map<*, *>, depth: Int, maxDepth: Int) {
        sb.append('{')
        var first = true
        for ((key, v) in map) {
            if (!first) sb.append(',')
            first = false
            appendString(sb, key.toString())
            sb.append(':')
            appendValue(sb, v, depth + 1, maxDepth)
        }
        sb.append('}')
    }

    private fun appendIterable(sb: StringBuilder, items: Iterable<*>, depth: Int, maxDepth: Int) {
        sb.append('[')
        var first = true
        for (item in items) {
            if (!first) sb.append(',')
            first = false
            appendValue(sb, item, depth + 1, maxDepth)
        }
        sb.append(']')
    }

    private fun appendNumber(sb: StringBuilder, n: Number) {
        when (n) {
            is Int, is Long, is Short, is Byte, is BigInteger -> sb.append(n.toString())
            is Double -> sb.append(finite(n))
            is Float -> sb.append(finite(n.toDouble()))
            is BigDecimal -> sb.append(n.toPlainString())
            else -> sb.append(finite(n.toDouble()))
        }
    }

    private fun finite(d: Double): String {
        if (d.isNaN() || d.isInfinite()) throw SignalsCodecException("JSON cannot represent $d")
        return d.toString()
    }

    private fun appendString(sb: StringBuilder, s: String) {
        sb.append('"')
        var previous = ' '
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                '\u2028' -> sb.append("\\u2028")
                '\u2029' -> sb.append("\\u2029")
                '/' -> if (previous == '<') sb.append("\\/") else sb.append('/')
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
            previous = c
        }
        sb.append('"')
    }
}
