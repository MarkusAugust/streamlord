package io.github.markusaugust.streamlord.html

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
                    '\'' -> {
                        append("\\x27")
                    }

                    '"' -> {
                        append("\\x22")
                    }

                    '&' -> {
                        append("\\x26")
                    }

                    '\\' -> {
                        append("\\x5c")
                    }

                    '\n' -> {
                        append("\\n")
                    }

                    '\r' -> {
                        append("\\r")
                    }

                    '\t' -> {
                        append("\\t")
                    }

                    '\b' -> {
                        append("\\b")
                    }

                    '\u000C' -> {
                        append("\\f")
                    }

                    '\u2028' -> {
                        append("\\u2028")
                    }

                    '\u2029' -> {
                        append("\\u2029")
                    }

                    '/' -> {
                        if (previous == '<') append("\\/") else append('/')
                    }

                    '<' -> {
                        if (value.getOrNull(i + 1) == '!') append("\\x3c") else append('<')
                    }

                    // A surrogate without its other half is no character UTF-8 can carry: escaped, it
                    // arrives as itself rather than as a replacement character, as JsonWriter writes it.
                    in '\uD800'..'\uDBFF' -> {
                        if (value.getOrNull(i + 1)?.isLowSurrogate() ==
                            true
                        ) {
                            append(c)
                        } else {
                            append("\\u%04x".format(c.code))
                        }
                    }

                    in '\uDC00'..'\uDFFF' -> {
                        if (value.getOrNull(i - 1)?.isHighSurrogate() ==
                            true
                        ) {
                            append(c)
                        } else {
                            append("\\u%04x".format(c.code))
                        }
                    }

                    else -> {
                        if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
                    }
                }
                previous = c
            }
            append('\'')
        }

    /** [value] as a JavaScript literal: `{id: 7, tags: ['a', 'b'], 'x-y': null}`. */
    public fun write(value: Any?): String = JsonWriter.write(value, Style)

    // JsonWriter's walk, and so its guards: a value either writes, the other writes too.
    private object Style : JsonWriter.Style {
        override val name = "A JavaScript literal"
        override val instead = "Use a Map, a List or a primitive."
        override val comma = ", "
        override val colon = ": "

        override fun appendString(
            sb: StringBuilder,
            s: String,
        ) {
            sb.append(string(s))
        }

        // __proto__ sets the object literal's prototype, quoted or not; only a computed key is a key.
        override fun appendKey(
            sb: StringBuilder,
            key: String,
        ) {
            sb.append(
                when {
                    key == "__proto__" -> "['__proto__']"
                    IDENTIFIER.matches(key) -> key
                    else -> string(key)
                },
            )
        }
    }
}
