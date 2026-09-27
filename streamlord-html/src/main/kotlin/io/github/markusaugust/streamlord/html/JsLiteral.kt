package io.github.markusaugust.streamlord.html

import io.github.markusaugust.streamlord.core.SignalsCodecException
import io.github.markusaugust.streamlord.core.json.JsonValue
import io.github.markusaugust.streamlord.core.json.JsonWriter

/**
 * Writes Kotlin values as JavaScript literals for Datastar expressions: strings in single
 * quotes, object keys bare where JavaScript allows it.
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
    private val IDENTIFIER = Regex("[A-Za-z_$][A-Za-z0-9_$]*")

    /** A single-quoted JavaScript string literal. `</` is broken up so the text is also safe inside a `<script>` element. */
    public fun string(value: String): String =
        buildString(value.length + 2) {
            append('\'')
            var previous = ' '
            for (c in value) {
                when (c) {
                    '\'' -> append("\\'")
                    '\\' -> append("\\\\")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    '\b' -> append("\\b")
                    '' -> append("\\f")
                    ' ' -> append("\\u2028")
                    ' ' -> append("\\u2029")
                    '/' -> if (previous == '<') append("\\/") else append('/')
                    else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
                }
                previous = c
            }
            append('\'')
        }

    /** [value] as a JavaScript literal: `{id: 7, tags: ['a', 'b'], 'x-y': null}`. */
    public fun write(value: Any?): String = buildString { appendValue(this, value) }

    private fun appendValue(
        sb: StringBuilder,
        value: Any?,
    ) {
        when (value) {
            is String -> sb.append(string(value))

            is Char -> sb.append(string(value.toString()))

            is Enum<*> -> sb.append(string(value.name))

            is JsonValue -> appendValue(sb, value.toKotlin())

            is Map<*, *> -> appendMap(sb, value)

            is Iterable<*> -> appendAll(sb, value)

            is Sequence<*> -> appendAll(sb, value.asIterable())

            is Array<*> -> appendAll(sb, value.asIterable())

            is IntArray -> appendAll(sb, value.asIterable())

            is LongArray -> appendAll(sb, value.asIterable())

            is DoubleArray -> appendAll(sb, value.asIterable())

            is BooleanArray -> appendAll(sb, value.asIterable())

            null, is Boolean, is Number -> sb.append(JsonWriter.write(value))

            else -> throw SignalsCodecException(
                "JsLiteral cannot write ${value::class.qualifiedName}. Use a Map, a List or a primitive.",
            )
        }
    }

    private fun appendMap(
        sb: StringBuilder,
        map: Map<*, *>,
    ) {
        sb.append('{')
        var first = true
        for ((key, v) in map) {
            if (!first) sb.append(", ")
            first = false
            val name = key.toString()
            sb.append(if (IDENTIFIER.matches(name)) name else string(name)).append(": ")
            appendValue(sb, v)
        }
        sb.append('}')
    }

    private fun appendAll(
        sb: StringBuilder,
        items: Iterable<*>,
    ) {
        sb.append('[')
        var first = true
        for (item in items) {
            if (!first) sb.append(", ")
            first = false
            appendValue(sb, item)
        }
        sb.append(']')
    }
}
