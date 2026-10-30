package io.github.markusaugust.streamlord.core.json

import io.github.markusaugust.streamlord.core.InternalStreamlordApi
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
 * The output is safe to embed in a JavaScript context: U+2028, U+2029, `</` and `<!` are
 * escaped, which matters because a script runs it as JavaScript, and a string inside a
 * `<script>` that holds `<!--` and then `<script` keeps the element open. For JSON that
 * Datastar's client reads as an expression, such as `data-signals`, use [writeExpression].
 */
public object JsonWriter {
    public const val DEFAULT_MAX_DEPTH: Int = 64

    /**
     * How a value is spelled, for a writer that walks values as this one does, with its guards,
     * and writes them another way: `JsLiteral` in streamlord-html writes JavaScript literals.
     */
    @InternalStreamlordApi
    public interface Style {
        /** What the output is called in an error, `JSON`. */
        public val name: String

        /** What to do instead, in the error for a value it cannot hold. */
        public val instead: String

        /** Between two members or elements, `,`. */
        public val comma: String

        /** Between a key and its value, `:`. */
        public val colon: String

        /** A string value. */
        public fun appendString(
            sb: StringBuilder,
            s: String,
        )

        /** The key of a member. */
        public fun appendKey(
            sb: StringBuilder,
            key: String,
        )
    }

    private object Json : Style {
        override val name = "JSON"
        override val instead = "Use a Map, or configure a SignalsCodec adapter (streamlord-json-kotlinx or streamlord-json-jackson)."
        override val comma = ","
        override val colon = ":"

        override fun appendString(
            sb: StringBuilder,
            s: String,
        ) = JsonWriter.appendString(sb, s)

        override fun appendKey(
            sb: StringBuilder,
            key: String,
        ) = JsonWriter.appendString(sb, key)
    }

    /** Encode [value] as JSON text. */
    public fun write(
        value: Any?,
        maxDepth: Int = DEFAULT_MAX_DEPTH,
    ): String = write(value, Json, maxDepth)

    /** [value] spelled in [style], walked and refused as [write] walks and refuses it. */
    @InternalStreamlordApi
    public fun write(
        value: Any?,
        style: Style,
        maxDepth: Int = DEFAULT_MAX_DEPTH,
    ): String = StringBuilder().also { Walk(it, style, maxDepth).value(value, 0) }.toString()

    /**
     * [write], for JSON that Datastar's client reads as an expression, such as the value of
     * `data-signals`: `\` is written as `\u005c`. The client cuts an expression into statements
     * at each `;` outside a string and reads a quote after a `\` as escaped, so `{"a": "C:\\", "b":
     * ";"}` would be cut inside the second value and lose every signal in it.
     */
    public fun writeExpression(
        value: Any?,
        maxDepth: Int = DEFAULT_MAX_DEPTH,
    ): String =
        // Every `\` in the output begins an escape, and the pairs are read from the left, so this
        // replaces exactly the escaped backslashes.
        write(value, maxDepth).replace("\\\\", "\\u005c")

    /** Encode a JSON string literal, quotes included. Also a valid JavaScript string literal. */
    public fun writeString(value: String): String = StringBuilder(value.length + 2).also { appendString(it, value) }.toString()

    private class Walk(
        val sb: StringBuilder,
        val style: Style,
        val maxDepth: Int,
    ) {
        fun value(
            value: Any?,
            depth: Int,
        ) {
            if (depth >= maxDepth && isContainer(value)) {
                throw SignalsCodecException("${style.name} nests deeper than $maxDepth levels (cyclic structure?)")
            }
            when (value) {
                null, JsonNull -> sb.append("null")

                is JsonValue -> jsonValue(value, depth)

                is Boolean -> sb.append(value)

                is String -> style.appendString(sb, value)

                is Char -> style.appendString(sb, value.toString())

                is Number -> appendNumber(sb, value)

                is Enum<*> -> style.appendString(sb, value.name)

                is Map<*, *> -> map(value, depth)

                is Iterable<*> -> all(value, depth)

                is Sequence<*> -> all(value.asIterable(), depth)

                is Array<*> -> all(value.asIterable(), depth)

                is IntArray -> all(value.asIterable(), depth)

                is LongArray -> all(value.asIterable(), depth)

                is DoubleArray -> all(value.asIterable(), depth)

                is BooleanArray -> all(value.asIterable(), depth)

                else -> throw SignalsCodecException(
                    "${style.name} cannot hold ${value::class.qualifiedName}. ${style.instead}",
                )
            }
        }

        private fun jsonValue(
            value: JsonValue,
            depth: Int,
        ) {
            when (value) {
                is JsonObject -> map(value, depth)

                is JsonArray -> all(value, depth)

                is JsonString -> style.appendString(sb, value.value)

                // As written, since a Long or Double would round 12345678901234567890 or 0.1000000000000000055.
                is JsonNumber -> sb.append(value.text)

                is JsonBoolean -> sb.append(value.value)

                JsonNull -> sb.append("null")
            }
        }

        private fun map(
            map: Map<*, *>,
            depth: Int,
        ) {
            sb.append('{')
            var first = true
            // Only a map with keys that are not strings can repeat one once they are written as text.
            val written = if (map.keys.all { it is String }) null else HashSet<String>()
            for ((key, v) in map) {
                if (!first) sb.append(style.comma)
                first = false
                val name = key.toString()
                if (written != null && !written.add(name)) throw SignalsCodecException("Two keys of the map are written as \"$name\"")
                style.appendKey(sb, name)
                sb.append(style.colon)
                value(v, depth + 1)
            }
            sb.append('}')
        }

        private fun all(
            items: Iterable<*>,
            depth: Int,
        ) {
            sb.append('[')
            var first = true
            for (item in items) {
                if (!first) sb.append(style.comma)
                first = false
                value(item, depth + 1)
            }
            sb.append(']')
        }
    }

    private const val PLAIN_SCALE = 100

    private fun isContainer(value: Any?): Boolean =
        value is Map<*, *> || value is Iterable<*> || value is Sequence<*> || value is Array<*> ||
            value is IntArray || value is LongArray || value is DoubleArray || value is BooleanArray

    private fun appendNumber(
        sb: StringBuilder,
        n: Number,
    ) {
        when (n) {
            is Int, is Long, is Short, is Byte, is BigInteger -> sb.append(n.toString())

            is Double -> sb.append(finite(n))

            // Its own shortest form: widened to a Double, 0.1f is written as 0.10000000149011612.
            is Float -> finite(n.toDouble()).let { sb.append(n.toString()) }

            // Plain notation reads better, and for a scale like 2000000000 it is two billion zeros.
            is BigDecimal -> sb.append(if (n.scale() in -PLAIN_SCALE..PLAIN_SCALE) n.toPlainString() else n.toString())

            else -> sb.append(finite(n.toDouble()))
        }
    }

    private fun finite(d: Double): String {
        if (d.isNaN() || d.isInfinite()) throw SignalsCodecException("JSON cannot represent $d")
        return d.toString()
    }

    private fun appendString(
        sb: StringBuilder,
        s: String,
    ) {
        sb.append('"')
        var previous = ' '
        for ((i, c) in s.withIndex()) {
            when (c) {
                '"' -> {
                    sb.append("\\\"")
                }

                '\\' -> {
                    sb.append("\\\\")
                }

                '\n' -> {
                    sb.append("\\n")
                }

                '\r' -> {
                    sb.append("\\r")
                }

                '\t' -> {
                    sb.append("\\t")
                }

                '\b' -> {
                    sb.append("\\b")
                }

                '\u000C' -> {
                    sb.append("\\f")
                }

                '\u2028' -> {
                    sb.append("\\u2028")
                }

                '\u2029' -> {
                    sb.append("\\u2029")
                }

                '/' -> {
                    if (previous == '<') sb.append("\\/") else sb.append('/')
                }

                '<' -> {
                    if (s.getOrNull(i + 1) == '!') sb.append("\\u003c") else sb.append('<')
                }

                // A surrogate without its other half is no character UTF-8 can carry: escaped, as
                // JSON.stringify does, it arrives as itself rather than as a replacement character.
                in '\uD800'..'\uDBFF' -> {
                    if (s.getOrNull(i + 1)?.isLowSurrogate() ==
                        true
                    ) {
                        sb.append(c)
                    } else {
                        sb.append("\\u%04x".format(c.code))
                    }
                }

                in '\uDC00'..'\uDFFF' -> {
                    if (s.getOrNull(i - 1)?.isHighSurrogate() ==
                        true
                    ) {
                        sb.append(c)
                    } else {
                        sb.append("\\u%04x".format(c.code))
                    }
                }

                else -> {
                    if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
                }
            }
            previous = c
        }
        sb.append('"')
    }
}
