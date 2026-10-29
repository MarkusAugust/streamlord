package io.github.markusaugust.streamlord.html

import io.github.markusaugust.streamlord.core.SignalsCodecException
import io.github.markusaugust.streamlord.core.json.JsonNumber
import io.github.markusaugust.streamlord.core.json.JsonValue
import io.github.markusaugust.streamlord.core.json.JsonWriter

/**
 * Writes Kotlin values as JavaScript literals for Datastar expressions: strings in single
 * quotes, object keys bare where JavaScript allows it and Datastar's client leaves them alone.
 *
 * JSON is valid JavaScript, but its double quotes are also the quotes of an HTML attribute.
 * `@get("/x")` survives the kotlinx.html DSL, which escapes them, and breaks the moment it is
 * pasted into `data-on:click="..."` in a string or a template. Datastar's own documentation
 * writes `@get('/x')`, and so does everything this object produces, so an expression built by
 * the helpers reads the same in all three ways of writing markup.
 *
 * Accepts what [JsonWriter] accepts: `null`, booleans, numbers, strings, chars, enums (by name),
 * maps, iterables, arrays and [JsonValue]s.
 */
public object JsLiteral {
    // No `$`: the client rewrites `$a` as a signal anywhere outside a string, so such a key is quoted.
    private val IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]*")

    /**
     * A single-quoted JavaScript string literal. `</` and `<!` are broken up so the text is also
     * safe inside a `<script>` element, where `<!--` followed by `<script` would keep it open.
     *
     * Single and double quotes, `&` and `\` are written as `\xHH`. No quote can then end an
     * attribute pasted into a string or a template, no entity is decoded on the way, and no `\`
     * stands before a quote, where Datastar's client, splitting an expression into statements,
     * would read the quote after it as escaped and run on past the end of the string.
     */
    public fun string(value: String): String =
        buildString(value.length + 2) {
            append('\'')
            var previous = ' '
            for ((i, c) in value.withIndex()) {
                when (c) {
                    '\'' -> append("\\x27")
                    '"' -> append("\\x22")
                    '&' -> append("\\x26")
                    '\\' -> append("\\x5c")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    '\b' -> append("\\b")
                    '\u000C' -> append("\\f")
                    '\u2028' -> append("\\u2028")
                    '\u2029' -> append("\\u2029")
                    '/' -> if (previous == '<') append("\\/") else append('/')
                    '<' -> if (value.getOrNull(i + 1) == '!') append("\\x3c") else append('<')
                    else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
                }
                previous = c
            }
            append('\'')
        }

    /** [value] as a JavaScript literal: `{id: 7, tags: ['a', 'b'], 'x-y': null}`. */
    public fun write(value: Any?): String = buildString { appendValue(this, value, 0) }

    // JsonWriter's guards, so that a value either writes refuses the other refuses as well.
    private fun appendValue(
        sb: StringBuilder,
        value: Any?,
        depth: Int,
    ) {
        val container = value is Map<*, *> || value is Iterable<*> || value is Sequence<*> || value is Array<*> || value is JsonValue
        if (depth >= JsonWriter.DEFAULT_MAX_DEPTH && container) {
            throw SignalsCodecException("JavaScript literal nesting deeper than ${JsonWriter.DEFAULT_MAX_DEPTH} levels (cyclic structure?)")
        }
        when (value) {
            is String -> sb.append(string(value))

            is Char -> sb.append(string(value.toString()))

            is Enum<*> -> sb.append(string(value.name))

            // As written, since a Long or Double would round 12345678901234567890 or 0.1000000000000000055.
            is JsonNumber -> sb.append(value.text)

            // A JsonObject or JsonArray is a Map or a List, so the numbers in it come here one by one.
            is Map<*, *> -> appendMap(sb, value, depth)

            is Iterable<*> -> appendAll(sb, value, depth)

            is Sequence<*> -> appendAll(sb, value.asIterable(), depth)

            is Array<*> -> appendAll(sb, value.asIterable(), depth)

            is IntArray -> appendAll(sb, value.asIterable(), depth)

            is LongArray -> appendAll(sb, value.asIterable(), depth)

            is DoubleArray -> appendAll(sb, value.asIterable(), depth)

            is BooleanArray -> appendAll(sb, value.asIterable(), depth)

            is JsonValue -> appendValue(sb, value.toKotlin(), depth)

            null, is Boolean, is Number -> sb.append(JsonWriter.write(value))

            else -> throw SignalsCodecException(
                "JsLiteral cannot write ${value::class.qualifiedName}. Use a Map, a List or a primitive.",
            )
        }
    }

    private fun appendMap(
        sb: StringBuilder,
        map: Map<*, *>,
        depth: Int,
    ) {
        sb.append('{')
        var first = true
        // Only a map with keys that are not strings can repeat one once they are written as text.
        val written = if (map.keys.all { it is String }) null else HashSet<String>()
        for ((key, v) in map) {
            if (!first) sb.append(", ")
            first = false
            val name = key.toString()
            if (written != null && !written.add(name)) throw SignalsCodecException("Two keys of the map are written as \"$name\"")
            // __proto__ sets the object literal's prototype, quoted or not; only a computed key is a key.
            sb
                .append(
                    when {
                        name == "__proto__" -> "['__proto__']"
                        IDENTIFIER.matches(name) -> name
                        else -> string(name)
                    },
                ).append(": ")
            appendValue(sb, v, depth + 1)
        }
        sb.append('}')
    }

    private fun appendAll(
        sb: StringBuilder,
        items: Iterable<*>,
        depth: Int,
    ) {
        sb.append('[')
        var first = true
        for (item in items) {
            if (!first) sb.append(", ")
            first = false
            appendValue(sb, item, depth + 1)
        }
        sb.append(']')
    }
}
