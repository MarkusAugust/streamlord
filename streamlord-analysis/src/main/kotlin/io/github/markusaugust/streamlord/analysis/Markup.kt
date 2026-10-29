package io.github.markusaugust.streamlord.analysis

import io.github.markusaugust.streamlord.core.domain.AttributeKeys
import io.github.markusaugust.streamlord.core.json.JsonParser

/*
 * Markup checks for the HTML Datastar patches: complete elements, ids where the protocol needs
 * them, and well-formed `data-*` attributes. A deliberately small tokenizer, not a browser.
 */

/** An attribute name as a browser parses it, without template syntax or a spread in it. */
private val PLAIN_ATTRIBUTE_NAME = Regex("""[A-Za-z_:][A-Za-z0-9_:.@-]*""")

/** A modifier name with arguments run into it by underscores, and nothing else. */
private val MODIFIER_WORD = Regex("""[a-z][a-z0-9]*(?:_[A-Za-z0-9]+)+""")

private val VOID = setOf("area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param", "source", "track", "wbr")

/** Elements whose content is text, whatever it looks like: a `<div>` in a `<textarea>` or a `<title>` is not a tag. The list of MarkupScan in streamlord-core. */
private val ASCII_INTEGER = Regex("[+-]?[0-9]+")

private val RAW_TEXT = setOf("script", "style", "textarea", "title", "xmp", "iframe", "noembed", "noframes", "noscript", "plaintext")

/**
 * The elements whose end tag HTML lets the author leave out, each with the start tags that close
 * it. `<p>` is closed by another `<p>` only: the block elements that also close it in a browser
 * are left alone, so that `<p><div></div></p>` reads the way its author meant it.
 */
private val OPTIONAL_END: Map<String, Set<String>> =
    mapOf(
        "li" to setOf("li"),
        "p" to setOf("p"),
        "dt" to setOf("dt", "dd"),
        "dd" to setOf("dt", "dd"),
        "option" to setOf("option", "optgroup"),
        "optgroup" to setOf("optgroup"),
        "td" to setOf("td", "th"),
        "th" to setOf("td", "th"),
        "tr" to setOf("tr"),
        "thead" to setOf("tbody", "tfoot"),
        "tbody" to setOf("tbody", "tfoot"),
        "tfoot" to setOf("tbody"),
    )

/**
 * Where a template engine or a Kotlin template will substitute text, the expression cannot be
 * judged before rendering: Pebble, Twig, Jinja, Handlebars and Mustache (`{{ }}`, `{% %}`),
 * ERB, EJS and JTE comments (`<% %>`), Kotlin, Thymeleaf, FreeMarker, JTE, kte and Velocity
 * (`${ }`, and JTE's `!{ }` for unescaped output), Thymeleaf's `*{ }`, `#{ }` and `@{ }`,
 * FreeMarker's square-bracket syntax (`[# ]`, `[= ]`), JTE's control flow (`@if`, `@for`,
 * ...), Velocity's (`#if`, `#foreach`, ...), and the placeholder for a Kotlin interpolation.
 */
public val TEMPLATE_SYNTAX: Regex =
    Regex(
        """\{\{|\{%|<%|[$!*#@]\{|\[#|\[=|(?:^|[\s>])@(?:if|elseif|else|endif|for|endfor|template|import|param|raw|endraw)\b|""" +
            """(?:^|[\s>])#(?:if|elseif|else|end|foreach|set|macro|parse|include)\b|__kt__""",
    )

/**
 * Tags that belong to a template engine, not to the document: FreeMarker directives and macro
 * calls (`<#if>`, `</#if>`, `<@row/>`), and its `<#-- -->` comments. Skipped like `<!DOCTYPE>`.
 */
private val TEMPLATE_TAG = Regex("""^</?[#@]""")
private val TAG_OPEN = Regex("""^<(/?)([A-Za-z][A-Za-z0-9:-]*)""")

/** A name also ends where a template construct starts: `data-x{{/if}}`, `data-x</#if>`. */
private val ATTR_NAME = Regex("""^[^\s"'<>/={]+""")
private val UNQUOTED_VALUE = Regex("""^[^\s>]*""")

/** Datastar reads `500ms`, `1s`, and a bare number as milliseconds. */
private val DURATION = Regex("""^\d+(ms|s)?$""")
private val WHOLE_NUMBER = Regex("""^\d+$""")

/** A value that looks like a name, so that its problems as a name are told rather than called an expression. */
private val NAME_LIKE = Regex("""[\p{L}\p{Nd}_$.-]+""")

/** A `$name` in a filter, which the client evaluates without rewriting signals. */
private val FILTER_SIGNAL = Regex("""\$[A-Za-z_][\w$]*""")
private val WORD_CHAR_CLASS = Regex("""\w""")
private val IDENT = Regex("""^[A-Za-z_][A-Za-z0-9_-]*$""")
private val SIGNAL_NAME = Regex("""^[A-Za-z_$][A-Za-z0-9_.$-]*$""")
private val HAS_CAPITAL = Regex("""[A-Z]""")
private val LEADING_CAPITAL = Regex("""^[A-Z]""")
private val HAS_LOWERCASE = Regex("""[a-z]""")

/** A JavaScript escape in a key as written: `\\` is a backslash, `\'` a quote. */
private val KEY_ESCAPE = Regex("""\\(.)""", RegexOption.DOT_MATCHES_ALL)

/**
 * The path of a key of the object form, part by part as the keys are written and read as the
 * client gets them, for the bracket form: `{'a.b': {'c d': 1}}` is `$['a.b']['c d']`, `{'a\\b': 1}`
 * is the key a\b.
 */
private fun keyParts(k: ObjectKey): List<String> = k.parts.map { p -> KEY_ESCAPE.replace(p) { it.groupValues[1] } }

/** A number literal, whole. */
private val NUMBER = Regex("""(?:0[xX][\da-fA-F_]+|0[bB][01_]+|0[oO][0-7_]+|(?:\d[\d_]*(?:\.[\d_]*)?|\.\d[\d_]*)(?:[eE][+-]?\d+)?)n?""")
private val NO_FUNCTION_WORDS = setOf("true", "false", "null", "undefined", "NaN", "Infinity")
private val UNARY_WORDS = setOf("typeof", "void", "delete")
private val BINARY_WORDS = setOf("in", "instanceof")
private const val OPERATOR_CHARS = "+-*/%<>&|^!=~"

/**
 * Whether the JavaScript expression [value] is sure to give no function: a literal (a number, a
 * string, a template, a regular expression, an array with such an item), a unary operation, or
 * operands joined by an arithmetic, bitwise or comparison operator. A name, a call, `new`, or
 * anything with `?`, `||`, `&&`, `??`, `=`, `=>` or `,` at its top level may give one, and is not
 * judged. Shared with the VS Code extension (`certainlyNoFunction` in markup.ts), which this
 * follows step by step.
 */
internal fun certainlyNoFunction(value: String): Boolean {
    val v = value.trim()
    if (v.isEmpty()) return false
    if (NUMBER.matches(v) || v in NO_FUNCTION_WORDS) return true
    val regions = jsRegions(v)
    var depth = 0
    var binary = false
    var unary = false
    // Whether what came last at the top level ends an operand, and whether anything came yet.
    var operand = false
    var started = false
    // The literals and brackets that open an operand at the top level, and whether a name, an
    // operator or a member access stands there as well.
    var groups = 0
    var code = false
    var lastRegion = Region.CODE
    var i = 0
    while (i < v.length) {
        val c = v[i]
        val r = regions[i]
        val step = i
        i++
        when {
            r == Region.TEMPLATE && c == '$' && v.getOrNull(step + 1) == '{' && v.getOrNull(step - 1) != '\\' -> {
                depth++
                i++
            }

            r == Region.TEMPLATE && c == '}' && depth > 0 && regions[step - 1] == Region.CODE -> {
                depth--
            }

            r == Region.CODE && (c == '(' || c == '[' || c == '{') -> {
                if (depth == 0) {
                    if (operand) code = true else groups++
                    started = true
                    lastRegion = Region.CODE
                }
                depth++
            }

            r == Region.CODE && (c == ')' || c == ']' || c == '}') -> {
                depth = maxOf(depth - 1, 0)
                if (depth == 0) operand = true
            }

            depth > 0 || r == Region.COMMENT -> {}

            r != Region.CODE -> {
                if (r != lastRegion) {
                    if (operand) code = true else groups++
                }
                lastRegion = r
                operand = true
                started = true
            }

            else -> {
                lastRegion = Region.CODE
                val next = v.getOrNull(step + 1)
                when {
                    c.isJsWhitespace() -> {}

                    c == '?' || c == ',' || (c == '=' && next == '>') || ((c == '|' || c == '&') && next == c) -> {
                        return false
                    }

                    c == '=' && next != '=' && v.getOrNull(step - 1)?.let { it in "=!<>" } != true -> {
                        return false
                    }

                    c.isJsNamePart() -> {
                        var j = step + 1
                        while (j < v.length && v[j].isJsNamePart()) j++
                        val word = v.substring(step, j)
                        i = j
                        if (operand && word in BINARY_WORDS) {
                            binary = true
                            operand = false
                        } else if (!started && word in UNARY_WORDS) {
                            unary = true
                        } else {
                            operand = true
                            code = true
                        }
                        started = true
                    }

                    c == '.' -> {
                        code = true
                    }

                    c !in OPERATOR_CHARS -> {
                        return false
                    }

                    else -> {
                        if (operand) {
                            binary = true
                        } else if (!started) {
                            unary = true
                        }
                        operand = false
                        code = true
                        started = true
                    }
                }
            }
        }
    }
    if (depth != 0) return false
    if (binary || unary) return true
    if (groups != 1 || code) return false
    // The client takes the functions out of an array as it does out of an object (`updateLeaves`
    // in paths.ts), so an array gives no function only where one of its items is sure to give none.
    if (v[0] == '[') return arrayItems(v, regions).any { certainlyNoFunction(it) }
    return regions[0] != Region.CODE
}

/** The items of the array literal [v], split at the commas of its top level. */
private fun arrayItems(
    v: String,
    regions: ByteArray,
): List<String> {
    val items = ArrayList<String>()
    var depth = 0
    var from = 1
    for (i in v.indices) {
        if (regions[i] != Region.CODE) continue
        when (v[i]) {
            '(', '[', '{' -> {
                depth++
            }

            ')', ']', '}' -> {
                if (--depth == 0) items += v.substring(from, i)
            }

            ',' -> {
                if (depth == 1) {
                    items += v.substring(from, i)
                    from = i + 1
                }
            }
        }
    }
    return items
}

/**
 * Whether the JavaScript expression [value] may give an object literal it holds: one that opens the
 * value or stands where the value of a group, a conditional or a `||`, `&&`, `??` or `,` comes
 * from, as in `({x: 1})`, `$a ? {x: 1} : 0` and `null || {x: 1}`. An object passed to a call, put
 * in an array or returned by a function does not count, and a value with `=>` in it is a function.
 * Shared with the VS Code extension (`mayGiveObject` in markup.ts).
 */
internal fun mayGiveObject(value: String): Boolean {
    val regions = jsRegions(value)
    // One entry per open bracket: whether it is a group, through which the value comes.
    val open = ArrayList<Boolean>()
    // The last character of code outside a string, a template or a comment, `x` after one of those.
    var last: Char? = null
    var beforeLast: Char? = null
    for (i in value.indices) {
        val r = regions[i]
        if (r == Region.COMMENT) continue
        val c = value[i]
        if (r != Region.CODE) {
            beforeLast = last
            last = 'x'
            continue
        }
        if (c.isJsWhitespace()) continue
        val through = open.all { it }
        if (through && c == '=' && value.getOrNull(i + 1) == '>') return false
        // At the start, after `(`, `?` (and `??`), `:`, `,`, `||` or `&&`.
        val opensOperand = last == null || last in "(?:," || ((last == '|' || last == '&') && beforeLast == last)
        if (c == '{' && through && opensOperand) return true
        if (c == '(' || c == '[' || c == '{') {
            open += c == '(' && opensOperand
        } else if ((c == ')' || c == ']' || c == '}') && open.isNotEmpty()) {
            open.removeAt(open.size - 1)
        }
        beforeLast = last
        last = c
    }
    return false
}

/** Where the value that starts at [from] in an object's [body] ends: at the `,` after it or the bracket that closes the object. */
private fun valueEnd(
    body: String,
    regions: ByteArray,
    from: Int,
): Int {
    var depth = 0
    for (i in from until body.length) {
        if (regions[i] != Region.CODE) continue
        when (body[i]) {
            '(', '[', '{' -> {
                depth++
            }

            ')', ']', '}' -> {
                if (depth == 0) return i
                depth--
            }

            ',' -> {
                if (depth == 0) return i
            }
        }
    }
    return body.length
}

/*
 * A named reference with its semicolon, one of the few legacy names the browser also reads
 * without it (unless `=` or a letter or digit follows, as in a URL's `&copy=1`), or a numeric
 * one, which the browser reads with or without its semicolon.
 */
private val ENTITY =
    Regex("""&(?:([A-Za-z][A-Za-z0-9]*);|(amp|AMP|lt|LT|gt|GT|quot|QUOT|nbsp)(?![=A-Za-z0-9])|#(\d+);?|#[xX]([0-9A-Fa-f]+);?)""")

/** Where `</[name]` begins after [from] as a whole name, `</titlex>` not ending a `title`; -1 when nowhere. */
private fun rawTextEnd(
    html: String,
    name: String,
    from: Int,
): Int {
    var at = from
    while (true) {
        val close = html.indexOfAsciiIgnoreCase("</$name", at)
        if (close < 0) return -1
        val next = html.getOrNull(close + 2 + name.length)
        if (next == null || next == '/' || next == '>' || next in "\t\n\u000c\r ") return close
        at = close + 1
    }
}

/** The named references that spell a character an expression may hold: the ASCII punctuation, and the whitespace. */
private val NAMED_ENTITIES =
    mapOf(
        "amp" to "&",
        "AMP" to "&",
        "lt" to "<",
        "LT" to "<",
        "gt" to ">",
        "GT" to ">",
        "quot" to "\"",
        "QUOT" to "\"",
        "apos" to "'",
        "nbsp" to "\u00A0",
        "Tab" to "\t",
        "NewLine" to "\n",
        "excl" to "!",
        "num" to "#",
        "dollar" to "$",
        "percnt" to "%",
        "lpar" to "(",
        "rpar" to ")",
        "ast" to "*",
        "midast" to "*",
        "plus" to "+",
        "comma" to ",",
        "period" to ".",
        "sol" to "/",
        "colon" to ":",
        "semi" to ";",
        "equals" to "=",
        "quest" to "?",
        "commat" to "@",
        "lsqb" to "[",
        "lbrack" to "[",
        "bsol" to "\\",
        "rsqb" to "]",
        "rbrack" to "]",
        "Hat" to "^",
        "lowbar" to "_",
        "UnderBar" to "_",
        "grave" to "`",
        "DiacriticalGrave" to "`",
        "lcub" to "{",
        "lbrace" to "{",
        "verbar" to "|",
        "vert" to "|",
        "VerticalLine" to "|",
        "rcub" to "}",
        "rbrace" to "}",
    )
private val TEMPLATE_CALL = Regex("""^[@#][A-Za-z]+\s*\(""")

/** Below this length a Datastar name has too many honest neighbours (test, kind, once) to judge a bare `data-*` at all. */
private const val LONG_NAME = 6

/**
 * Lowercases A to Z and nothing else, so the result is as long as the input: `lowercase()` turns
 * U+0130 into two characters and moves every offset after it.
 */
internal fun String.asciiLowercase(): String {
    if (none { it in 'A'..'Z' }) return this
    val out = StringBuilder(length)
    for (c in this) out.append(if (c in 'A'..'Z') c + 32 else c)
    return out.toString()
}

/** `indexOf` that ignores the case of A to Z; [needle] is given in lowercase. */
internal fun String.indexOfAsciiIgnoreCase(
    needle: String,
    from: Int,
): Int {
    for (i in maxOf(0, from)..length - needle.length) {
        var k = 0
        while (k < needle.length && (this[i + k].let { if (it in 'A'..'Z') it + 32 else it }) == needle[k]) k++
        if (k == needle.length) return i
    }
    return -1
}

/** Text with its character references decoded, and for every decoded index the offset it came from. Has `text.length + 1` entries. */
internal class DecodedValue(
    val text: String,
    val map: IntArray,
) {
    fun toSource(decoded: Int): Int = map[decoded.coerceIn(0, map.size - 1)]
}

/**
 * The attribute value as the browser hands it to Datastar: `&amp;&amp;` is `&&` by then. The
 * named references that spell ASCII punctuation or whitespace, the legacy ones the browser reads
 * without a semicolon, and the numeric ones are decoded; `&copy;` and the rest stay as written,
 * which to the expression they are.
 */
internal fun decodeEntities(value: String): DecodedValue {
    if ('&' !in value) return DecodedValue(value, IntArray(value.length + 1) { it })
    val out = StringBuilder()
    val map = ArrayList<Int>()
    var i = 0
    while (i < value.length) {
        val m = if (value[i] == '&') ENTITY.matchAt(value, i) else null
        val decoded =
            when {
                m == null -> {
                    null
                }

                m.groupValues[1].isNotEmpty() -> {
                    NAMED_ENTITIES[m.groupValues[1]]
                }

                m.groupValues[2].isNotEmpty() -> {
                    NAMED_ENTITIES[m.groupValues[2]]
                }

                // A number too large for an Int is past U+10FFFF, which the browser reads as U+FFFD.
                else -> {
                    codePoint(
                        m.groupValues[3].takeIf { it.isNotEmpty() }?.let { it.toIntOrNull() ?: Int.MAX_VALUE }
                            ?: m.groupValues[4].toIntOrNull(16)
                            ?: Int.MAX_VALUE,
                    )
                }
            }
        if (m == null || decoded == null) {
            out.append(value[i])
            map += i
            i++
            continue
        }
        for (c in decoded) {
            out.append(c)
            map += i
        }
        i += m.value.length
    }
    map += value.length
    return DecodedValue(out.toString(), map.toIntArray())
}

/** The character a numeric reference names, as the browser reads it: NUL, a surrogate and past U+10FFFF are U+FFFD. */
private fun codePoint(cp: Int): String = if (cp == 0 || cp > 0x10FFFF || cp in 0xD800..0xDFFF) "\uFFFD" else String(Character.toChars(cp))

/**
 * Does the value hold template syntax, so that it cannot be judged before rendering? A `${`
 * inside a JavaScript template literal is JavaScript's own and does not count.
 */
public fun hasTemplateSyntax(value: String): Boolean =
    TEMPLATE_SYNTAX.findAll(value).any { it.value != "\${" || quoteAt(value, it.range.first) != '`' }

/**
 * Where a template construct that starts at [at] inside a tag ends, or [at] when none starts
 * there: `<% %>`, `{{ }}`, `{% %}`, `{# #}`, a FreeMarker directive, or a JTE or Velocity
 * directive with its parenthesised condition. Its `>` does not end the tag.
 */
private fun templateConstructEnd(
    html: String,
    at: Int,
): Int {
    fun after(
        close: String,
        from: Int,
    ): Int = html.indexOf(close, from).let { if (it < 0) at else it + close.length }
    return when {
        html.startsWith("<%", at) -> {
            after("%>", at + 2)
        }

        html.startsWith("{{", at) -> {
            after("}}", at + 2)
        }

        html.startsWith("{%", at) -> {
            after("%}", at + 2)
        }

        html.startsWith("{#", at) -> {
            after("#}", at + 2)
        }

        html.startsWith("<#", at) || html.startsWith("</#", at) || html.startsWith("<@", at) -> {
            after(">", at + 2)
        }

        else -> {
            val call = TEMPLATE_CALL.find(html.substring(at, minOf(html.length, at + 32))) ?: return at
            var depth = 0
            for (i in at + call.value.length - 1 until html.length) {
                if (html[i] == '(') {
                    depth++
                } else if (html[i] == ')' && --depth == 0) {
                    return i + 1
                }
            }
            at
        }
    }
}

/** Whether a template construct starts at [at] in a tag's text, where it cuts the attribute name before it short: `${`, `{{`, `<%`. */
private fun cutByTemplate(
    tagSource: String,
    at: Int,
): Boolean = tagSource.getOrNull(at) == '{' || (at < tagSource.length && templateConstructEnd(tagSource, at) > at)

/** A Kotlin template, `$name` or `${...}`, in a name written in Kotlin source. */
private val KOTLIN_TEMPLATE = Regex("""\$[A-Za-z_{]""")

public data class Attribute(
    val name: String,
    val nameStart: Int,
    val value: String?,
    val valueStart: Int,
    val quoted: Boolean,
) {
    val nameEnd: Int get() = nameStart + name.length
    val valueEnd: Int get() = valueStart + (value?.length ?: 0)
}

public data class Tag(
    val name: String,
    val start: Int,
    val end: Int,
    val closing: Boolean,
    val selfClosing: Boolean,
    val attributes: List<Attribute>,
)

public data class TextRun(
    val start: Int,
    val end: Int,
)

public data class Tokens(
    val tags: List<Tag>,
    val topLevelText: List<TextRun>,
)

public data class MarkupOptions(
    /** Each top-level element must carry an id (no selector given, mode is outer/replace). */
    val requireIds: Boolean,
    /** Attribute prefix of the Datastar bundle. */
    val prefix: String,
    /** Validate data-* attributes and their expressions. */
    val checkAttributes: Boolean,
    /** Kebab-case signal names the source declares with `__case.kebab`, which `$my-value` then reaches as written. */
    val hyphenatedSignals: Set<String> = emptySet(),
)

public fun tokenize(html: String): Tokens {
    val tags = ArrayList<Tag>()
    val topLevelText = ArrayList<TextRun>()
    var i = 0
    var depth = 0
    var textStart = -1

    fun flushText(end: Int) {
        if (textStart >= 0 && depth == 0) {
            val t = html.substring(textStart, end)
            if (t.isNotBlank()) {
                val lead = t.length - t.trimStart().length
                topLevelText += TextRun(textStart + lead, textStart + t.trimEnd().length)
            }
        }
        textStart = -1
    }
    while (i < html.length) {
        if (html.startsWith("<!--", i) || html.startsWith("<#--", i)) {
            flushText(i)
            val close = html.indexOf("-->", i + 4)
            i = if (close < 0) html.length else close + 3
            continue
        }
        if (html.startsWith("<%--", i)) {
            flushText(i)
            val close = html.indexOf("--%>", i + 4)
            i = if (close < 0) html.length else close + 4
            continue
        }
        // Pebble and Twig comments, and Mustache and Handlebars ones. Skipped only when they close,
        // because `{#` also opens a block in other template languages.
        val commentClose =
            when {
                html.startsWith("{#", i) -> "#}"
                html.startsWith("{{!--", i) -> "--}}"
                html.startsWith("{{!", i) -> "}}"
                else -> null
            }
        val commentEnd = if (commentClose == null) -1 else html.indexOf(commentClose, i + 2)
        if (commentClose != null && commentEnd >= 0) {
            flushText(i)
            i = commentEnd + commentClose.length
            continue
        }
        if (html[i] == '<' &&
            (
                html.startsWith("<!", i) || html.startsWith("<?", i) ||
                    TEMPLATE_TAG.containsMatchIn(html.substring(i, minOf(html.length, i + 3)))
            )
        ) {
            flushText(i)
            val close = html.indexOf('>', i)
            i = if (close < 0) html.length else close + 1
            continue
        }
        val tagMatch = if (html[i] == '<') TAG_OPEN.find(html.substring(i, minOf(html.length, i + 64))) else null
        if (tagMatch != null) {
            flushText(i)
            val closing = tagMatch.groupValues[1] == "/"
            val name = tagMatch.groupValues[2].asciiLowercase()
            var j = i + tagMatch.value.length
            val attributes = ArrayList<Attribute>()
            var selfClosing = false
            while (j < html.length && html[j] != '>') {
                val c = html[j]
                if (c.isWhitespace()) {
                    j++
                    continue
                }
                if (c == '/') {
                    selfClosing = true
                    j++
                    continue
                }
                val constructEnd = templateConstructEnd(html, j)
                if (constructEnd > j) {
                    j = constructEnd
                    continue
                }
                val am = ATTR_NAME.find(html.substring(j))
                if (am == null) {
                    j++
                    continue
                }
                val attrName = am.value
                val nameStart = j
                j += attrName.length
                var k = j
                while (k < html.length && html[k].isWhitespace()) k++
                var value: String? = null
                var valueStart = -1
                var quoted = false
                if (k < html.length && html[k] == '=') {
                    k++
                    while (k < html.length && html[k].isWhitespace()) k++
                    val q = html.getOrNull(k)
                    if (q == '"' || q == '\'') {
                        val close = html.indexOf(q, k + 1)
                        valueStart = k + 1
                        value = html.substring(k + 1, if (close < 0) html.length else close)
                        quoted = true
                        j = if (close < 0) html.length else close + 1
                    } else {
                        val vm = UNQUOTED_VALUE.find(html.substring(minOf(k, html.length)))
                        valueStart = k
                        value = vm?.value ?: ""
                        j = k + value.length
                    }
                }
                attributes += Attribute(attrName, nameStart, value, valueStart, quoted)
            }
            val end = minOf(j + 1, html.length)
            tags += Tag(name, i, end, closing, selfClosing || name in VOID, attributes)
            // As the browser reads it, and MarkupScan in core: a `/>` closes no raw-text element,
            // plaintext runs to the end, and only `</name` as a whole name ends the others.
            if (!closing && name in RAW_TEXT) {
                val closeIdx = if (name == "plaintext") -1 else rawTextEnd(html, name, end)
                i = if (closeIdx < 0) html.length else closeIdx
                depth++
                continue
            }
            if (!closing && !selfClosing && name !in VOID) {
                depth++
            } else if (closing) {
                depth = maxOf(0, depth - 1)
            }
            i = end
            continue
        }
        if (textStart < 0) textStart = i
        i++
    }
    flushText(html.length)
    return Tokens(tags, topLevelText)
}

/** `fooBar` -> `foo-bar`, one hyphen per capital, which Datastar's camel conversion turns back into `fooBar`. */
public fun kebab(name: String): String = AttributeKeys.kebab(name)

/**
 * The key to write, and the `__case` to add, so that a key the author typed with capitals comes
 * back as that name: `foo-bar` for a signal (Datastar reads it as camelCase), `widget-loaded__case.camel`
 * for an event or class (kebab by default), `aria-label` where the key is used as it is. An
 * explicit `__case` is kept: the key is still kebab-cased, because the browser lowercases it
 * either way. A signal key is read as camelCase, so `fooBar-baz` comes back as `fooBarBaz`, the
 * name the DSL's `signal()` reads from it too; [clientKeyName] says what. Null for an event or
 * class name that mixes capitals with a hyphen before a lower-case letter, `fooBar-baz` or `A-b`,
 * since the client's camel and pascal turn that hyphen into a capital too (`modifyCasing` in
 * text.ts) and no key gives it back; [wireKeyReads] says what it would read. Shared by the HTML
 * warning and the Kotlin wire hint.
 */
public fun wireKey(
    key: String,
    keyCase: KeyCase?,
    explicitCase: String?,
): String? {
    val base = kebab(key)
    if (explicitCase != null) return "${base}__case.$explicitCase"
    val wanted = if (LEADING_CAPITAL.containsMatchIn(key)) "pascal" else "camel"
    if (keyCase == KeyCase.RAW) return base
    if (keyCase == KeyCase.KEBAB && CASE_FNS.getValue(wanted)(base) != key) return null
    val defaultCase = if (keyCase == KeyCase.CAMEL) "camel" else "kebab"
    return if (wanted == defaultCase) base else "${base}__case.$wanted"
}

/** What the client reads from the kebab-case key of [key] under the case its capitals ask for. */
public fun wireKeyReads(key: String): String =
    CASE_FNS.getValue(if (LEADING_CAPITAL.containsMatchIn(key)) "pascal" else "camel")(kebab(key))

/**
 * What `data-x:` with an empty key lacks besides the colon dropped: null when the attribute runs
 * without it as it stands, else the error the client raises.
 */
private fun emptyKeyNeeds(
    spec: AttributeSpec,
    hasValue: Boolean,
    cut: Boolean,
): String? =
    when {
        hasValue -> null
        spec.requires == Requires.VALUE && !cut -> "ValueRequired"
        spec.requires == Requires.EXCLUSIVE -> "KeyOrValueRequired"
        else -> null
    }

private fun emptyKeyAdvice(
    spec: AttributeSpec,
    prefix: String,
    hasValue: Boolean,
    cut: Boolean,
): String {
    if (spec.keyRequired) return "The key follows the colon, e.g. ${spec.forms.firstOrNull() ?: ""}."
    return when (emptyKeyNeeds(spec, hasValue, cut)) {
        "ValueRequired" -> {
            "Without the colon it is $prefix${spec.name}, which also needs a value; without one Datastar raises ValueRequired."
        }

        "KeyOrValueRequired" -> {
            "It needs a signal, as the key after the colon, e.g. ${spec.forms.getOrElse(0) { "" }}, or as the value without the colon, " +
                "e.g. ${spec.forms.getOrElse(1) { "" }}; without one Datastar raises KeyOrValueRequired."
        }

        else -> {
            "Without the colon it is $prefix${spec.name}."
        }
    }
}

/** The cases the 1.0.4 client has a function for (utils/text.ts); any other value leaves the key as it is. */
private val CASE_FNS: Map<String, (String) -> String> =
    mapOf(
        "camel" to ::camelCase,
        "snake" to { s -> s.replace('-', '_') },
        "pascal" to { s -> if (s.isEmpty()) s else s.substring(0, 1).uppercase() + camelCase(s.substring(1)) },
    )

private val HYPHEN_LOWER = Regex("-[a-z]")

private fun camelCase(s: String): String = HYPHEN_LOWER.replace(s) { it.value.substring(1).uppercase() }

/** The key as the client recases it: each value of the last `__case` in turn, or the attribute's default without one. */
private fun clientCase(
    key: String,
    modifiers: List<ParsedModifier>,
    defaultCase: String,
): String {
    val mod = modifiers.lastOrNull { it.name == "case" }
    var out = key
    for (c in mod?.args?.distinct() ?: listOf(defaultCase)) out = CASE_FNS[c]?.invoke(out)?.ifEmpty { null } ?: out
    return out
}

/** What a key is to Datastar: the noun the messages and the hover use, from `keyCase` in the catalog. */
public enum class KeyKind { SIGNAL, EVENT, CLASS, RAW }

public fun keyKind(spec: AttributeSpec): KeyKind =
    when (spec.keyCase) {
        KeyCase.CAMEL -> KeyKind.SIGNAL
        KeyCase.KEBAB -> if (spec.name == "on") KeyKind.EVENT else KeyKind.CLASS
        else -> KeyKind.RAW
    }

/**
 * How Datastar reads a key written as [wire]: "the signal $fooBar", "the event widgetLoaded",
 * "the class isOpen", or, for a raw key, the key itself. One wording for the HTML warning, the
 * Kotlin wire hint and the hover note.
 */
public fun keyReading(
    spec: AttributeSpec,
    name: String,
    wire: String,
): String =
    when (keyKind(spec)) {
        KeyKind.SIGNAL -> "the signal \$$name"
        KeyKind.EVENT -> "the event $name"
        KeyKind.CLASS -> "the class $name"
        KeyKind.RAW -> "the ${if (spec.name == "style") "property" else "attribute"} ${wire.substringBefore("__")}"
    }

/**
 * What a raw key with a capital can do, said once, where it applies: an attribute keeps its name in
 * the object form (attr.ts sets the key as written); a style property has no form that keeps one,
 * since the object form writes the key kebab-case (style.ts:50 in 1.0.4).
 */
public fun rawKeyNote(
    spec: AttributeSpec,
    prefix: String,
    key: String,
): String =
    when {
        keyKind(spec) != KeyKind.RAW || spec.valueKind != ValueKind.EXPRESSION -> {
            ""
        }

        // The object form writes a key kebab-case too (style.ts:50 in 1.0.4), so no form keeps a capital.
        spec.name == "style" && key.startsWith("--") -> {
            " No form keeps a capital in a custom property: name it in kebab-case, here and in the CSS, as ${kebab(key)}."
        }

        spec.name == "style" -> {
            " A CSS property is written in kebab-case: $prefix${spec.name}:${kebab(key)}."
        }

        else -> {
            " For a name that really has capitals, such as SVG's viewBox, use the object form: $prefix${spec.name}=\"{$key: ...}\"."
        }
    }

/** The markup and attribute rules, bound to one catalog. */
public class MarkupValidator(
    private val catalog: Catalog = Catalog.default,
) {
    private val expressions = ExpressionValidator(catalog)

    /** The actions the catalog lists as sending a request. */
    private val backendActions = catalog.actions.filter { it.kind == "backend" }.map { it.name }

    /** A call to one of them, written as Datastar parses it: no space before the parenthesis. */
    private val backendAction = Regex("@(?:" + backendActions.joinToString("|") { Regex.escape(it) } + ")\\(")

    public fun validateMarkup(
        html: String,
        opts: MarkupOptions,
    ): List<Issue> {
        val issues = ArrayList<Issue>()
        val (tags, topLevelText) = tokenize(html)
        val stack = ArrayList<Tag>()
        val topLevel = ArrayList<Tag>()
        for (tag in tags) {
            if (tag.closing) {
                val idx = stack.indexOfLast { it.name == tag.name }
                if (idx < 0) {
                    issues += Issue(tag.start, tag.end, "Stray closing tag </${tag.name}>.", Severity.ERROR, "stray-close")
                } else {
                    for (unclosed in stack.subList(idx + 1, stack.size)) {
                        if (unclosed.name in OPTIONAL_END) continue
                        issues += Issue(unclosed.start, unclosed.end, "<${unclosed.name}> is never closed.", Severity.ERROR, "unclosed")
                    }
                    while (stack.size > idx) stack.removeAt(stack.size - 1)
                }
                continue
            }
            closeImplied(stack, tag.name)
            if (stack.isEmpty()) topLevel += tag
            if (!tag.selfClosing) stack += tag
            if (opts.checkAttributes) {
                issues +=
                    validateAttributes(tag, opts.prefix, html.substring(tag.start, tag.end), opts.hyphenatedSignals)
            }
        }
        for (unclosed in stack) {
            if (unclosed.name in OPTIONAL_END) continue
            issues += Issue(unclosed.start, unclosed.end, "<${unclosed.name}> is never closed.", Severity.ERROR, "unclosed")
        }
        for (t in topLevelText) {
            if (TEMPLATE_SYNTAX.containsMatchIn(html.substring(t.start, t.end))) continue
            issues +=
                Issue(
                    t.start,
                    t.end,
                    "Datastar patches complete elements, not text fragments. Wrap this in an element.",
                    Severity.WARNING,
                    "top-level-text",
                )
        }
        if (opts.requireIds) {
            for (tag in topLevel) {
                if (tag.name == "html" || tag.name == "body" || tag.name == "head") continue
                if (tag.attributes.none { it.name.equals("id", ignoreCase = true) }) {
                    val afterName = tag.start + 1 + tag.name.length
                    issues +=
                        Issue(
                            start = tag.start,
                            end = tag.end,
                            message =
                                "<${tag.name}> has no id. Without a selector, Datastar matches top-level elements by id " +
                                    "and silently ignores the rest.",
                            severity = Severity.WARNING,
                            code = "missing-id",
                            link = Docs.SSE,
                            fixes = listOf(Fix("Add id=\"${tag.name}\"", afterName, afterName, " id=\"${tag.name}\"")),
                        )
                }
            }
        }
        return issues
    }

    /**
     * A start tag closes the open elements whose end tag was left out: `<li>` closes the `<li>`
     * before it, `<tbody>` closes the cell, the row and the `<thead>` above it. The search stops
     * at the first element that needs its end tag.
     */
    private fun closeImplied(
        stack: MutableList<Tag>,
        opening: String,
    ) {
        var i = stack.size - 1
        while (i >= 0) {
            val closers = OPTIONAL_END[stack[i].name] ?: return
            if (opening in closers) {
                while (stack.size > i) stack.removeAt(stack.size - 1)
            }
            i--
        }
    }

    /** Validate the Datastar attributes on one tag. */
    public fun validateAttributes(
        tag: Tag,
        prefix: String,
    ): List<Issue> = validateAttributes(tag, prefix, null)

    /**
     * Validate the Datastar attributes on one tag, whose text is [tagSource]: with it, a template
     * construct the tokenizer stepped over inside the tag is seen too. [kotlin] says the names are
     * written in Kotlin source, where `$name` and `${...}` are templates.
     */
    public fun validateAttributes(
        tag: Tag,
        prefix: String,
        tagSource: String?,
        hyphenatedSignals: Set<String> = emptySet(),
        kotlin: Boolean = false,
    ): List<Issue> {
        val issues = ArrayList<Issue>()
        for (attr in tag.attributes) {
            val lower = attr.name.asciiLowercase()
            if (prefix == "data-" && lower.startsWith("data-star-")) {
                issues +=
                    Issue(
                        attr.nameStart,
                        attr.nameEnd,
                        "This is an aliased Datastar attribute, but the prefix is set to data-. Check the Streamlord attribute prefix setting.",
                        Severity.WARNING,
                        "prefix-mismatch",
                    )
                continue
            }
            if (!lower.startsWith(prefix)) continue
            val parsed = catalog.parseAttributeName(lower, prefix) ?: continue
            val nameEnd = attr.nameEnd
            val spec = parsed.spec
            // data-on:${evt}, data-signals:{{ name }}, attributes["data-bind:$key"]: a template construct
            // cut the name short or stands in it, so the key, its modifiers and the value that went with
            // the name are not known until the page is rendered.
            val cut = tagSource != null && cutByTemplate(tagSource, nameEnd - tag.start)
            val templated = cut || hasTemplateSyntax(attr.name) || (kotlin && KOTLIN_TEMPLATE.containsMatchIn(attr.name))
            if (spec == null && templated) continue
            if (spec == null) {
                // The rule of the runtime guard. A key or a modifier makes a custom attribute implausible, so
                // any one-letter neighbour is a typo. A bare name (data-test, data-kind, data-effects) is an
                // honest word more often than not: only a swapped letter in a long name is judged.
                val qualified = parsed.key != null || parsed.modifiers.isNotEmpty()
                val near =
                    catalog.attributes.firstOrNull {
                        distance(parsed.base, it.name) == 1 &&
                            (qualified || (it.name.length >= LONG_NAME && it.name.length == parsed.base.length))
                    }
                val baseEnd = attr.nameStart + prefix.length + parsed.base.length
                if (near != null) {
                    issues +=
                        Issue(
                            start = attr.nameStart,
                            end = nameEnd,
                            message = "Unknown Datastar attribute $prefix${parsed.base}. Did you mean $prefix${near.name}?",
                            severity = Severity.WARNING,
                            code = "unknown-attribute",
                            link = Docs.attribute(near.name),
                            fixes = listOf(Fix("Change to $prefix${near.name}", attr.nameStart, baseEnd, "$prefix${near.name}")),
                        )
                    continue
                }
                // data-on-click, data-bind-value: the pre-1.0 syntax, which 1.0 reads as an attribute it does not have.
                val dash = parsed.base.indexOf('-')
                val keyedSpec =
                    if (dash > 0 &&
                        parsed.key == null
                    ) {
                        catalog.attributesByName[parsed.base.substring(0, dash)]?.takeIf { it.keyed }
                    } else {
                        null
                    }
                if (keyedSpec != null) {
                    val rest = parsed.base.substring(dash + 1)
                    val init = keyedSpec.name == "on" && rest == "load"
                    val fixed = if (init) "${prefix}init" else "$prefix${keyedSpec.name}:$rest"
                    issues +=
                        Issue(
                            start = attr.nameStart,
                            end = nameEnd,
                            message =
                                "$prefix${parsed.base} is Datastar's pre-1.0 syntax, which 1.0 ignores. " +
                                    if (init) {
                                        "An expression run when the element appears is $fixed."
                                    } else {
                                        "The key follows a colon: $fixed."
                                    },
                            severity = Severity.WARNING,
                            code = "unknown-attribute",
                            link = Docs.attribute(if (init) "init" else keyedSpec.name),
                            fixes = listOf(Fix("Change to $fixed", attr.nameStart, baseEnd, fixed)),
                        )
                }
                continue
            }
            val link = Docs.attribute(spec.name)
            if (parsed.key == "" && !templated) {
                // data-indicator: the client splits the key off at a colon with something after it, so it
                // looks up an attribute named indicator:, finds none and leaves the element alone.
                val colonEnd = attr.nameStart + prefix.length + spec.name.length + 1
                issues +=
                    Issue(
                        start = attr.nameStart,
                        end = nameEnd,
                        message =
                            "$prefix${spec.name}: has an empty key, so Datastar reads ${spec.name}: as an attribute it does not have " +
                                "and does nothing with it. " + emptyKeyAdvice(spec, prefix, !attr.value.isNullOrEmpty(), cut),
                        severity = Severity.WARNING,
                        code = "unknown-attribute",
                        link = link,
                        // Only where the attribute runs without the colon: data-text with no value raises ValueRequired.
                        fixes =
                            if (spec.keyRequired || emptyKeyNeeds(spec, !attr.value.isNullOrEmpty(), cut) != null) {
                                emptyList()
                            } else {
                                listOf(Fix("Change to $prefix${spec.name}", colonEnd - 1, colonEnd, ""))
                            },
                    )
                continue
            }
            if (spec.pro) {
                issues +=
                    Issue(
                        attr.nameStart,
                        nameEnd,
                        "$prefix${spec.name} is a Datastar Pro attribute; it needs the Pro bundle.",
                        Severity.HINT,
                        "pro-attribute",
                        link,
                    )
            }
            if (spec.keyRequired && parsed.key.isNullOrEmpty() && !templated) {
                issues +=
                    Issue(
                        attr.nameStart,
                        nameEnd,
                        "$prefix${spec.name} needs a key, e.g. ${spec.forms.firstOrNull() ?: ""}.",
                        Severity.ERROR,
                        "missing-key",
                        link,
                    )
            }
            if (!spec.keyed && parsed.key != null && parsed.key !in spec.keys && !templated) {
                issues += Issue(attr.nameStart, nameEnd, "$prefix${spec.name} does not take a key.", Severity.ERROR, "unexpected-key", link)
            }
            if (parsed.key != null && !templated) issues += validateKeyCase(attr, parsed, spec, prefix)
            val named = validateSignalNames(attr, parsed, spec, prefix, templated)
            issues += named
            if (!templated) issues += validateDottedKey(attr, parsed, spec, prefix)
            val hasKey = !parsed.key.isNullOrEmpty() || templated
            val hasValue = !attr.value.isNullOrEmpty()
            if (spec.requires == Requires.VALUE && !hasValue && !cut) {
                issues +=
                    Issue(
                        attr.nameStart,
                        nameEnd,
                        "$prefix${spec.name} needs a value; without one Datastar raises ValueRequired.",
                        Severity.ERROR,
                        "missing-value",
                        link,
                    )
            }
            if (spec.requires == Requires.EXCLUSIVE && hasKey == hasValue) {
                issues +=
                    Issue(
                        attr.nameStart,
                        nameEnd,
                        if (hasKey) {
                            "$prefix${spec.name} takes the signal as a key or as a value, not both; Datastar raises KeyAndValueProvided."
                        } else {
                            "$prefix${spec.name} needs a signal, as a key or as a value; without one Datastar raises KeyOrValueRequired."
                        },
                        Severity.ERROR,
                        if (hasKey) "key-and-value" else "missing-key-or-value",
                        link,
                    )
            }
            if (spec.onlyOn != null && tag.name !in spec.onlyOn) {
                issues +=
                    Issue(
                        attr.nameStart,
                        nameEnd,
                        "$prefix${spec.name} only works on <${spec.onlyOn.joinToString(">, <")}>.",
                        Severity.WARNING,
                        "wrong-element",
                    )
            }
            // data-on:my__event: the event is the key before the first __, so the name the author may have
            // meant whole is cut there, and recased as __case says.
            val listensFor =
                if (spec.name == "on" && !parsed.key.isNullOrEmpty()) {
                    ", so it listens for the event ${clientCase(parsed.key, parsed.modifiers, "kebab")}"
                } else {
                    ""
                }
            for (mod in if (templated) emptyList() else parsed.modifiers) {
                val mend = attr.nameStart + mod.offset + mod.text.length
                // An empty modifier has no text to underline: the `__` that opens it is marked.
                val mstart = if (mod.text.isEmpty()) mend - 2 else mend - mod.text.length
                val mspec = spec.modifiers.firstOrNull { it.name == mod.name }
                if (mspec == null) {
                    // Datastar ignores a modifier it does not know and runs the handler anyway, so
                    // these are warnings. __debounce_150ms: an argument joined with an underscore,
                    // which Datastar reads as one unknown name. Arguments follow a dot.
                    val dotted = dottedArguments(mod.name, spec)
                    if (dotted != null) {
                        issues +=
                            Issue(
                                start = mstart,
                                end = mend,
                                message =
                                    "Unknown modifier __${mod.name} on $prefix${spec.name}, which Datastar ignores$listensFor. " +
                                        "A modifier's arguments follow a dot: __$dotted.",
                                severity = Severity.WARNING,
                                code = "unknown-modifier",
                                link = link,
                                fixes = listOf(Fix("Change to __$dotted", mstart, mstart + mod.name.length, dotted)),
                            )
                        continue
                    }
                    val near = spec.modifiers.map { it.name }.firstOrNull { distance(it, mod.name) <= 2 }
                    issues +=
                        Issue(
                            start = mstart,
                            end = mend,
                            message =
                                "Unknown modifier __${mod.name} on $prefix${spec.name}, which Datastar ignores$listensFor." +
                                    (near?.let { " Did you mean __$it?" } ?: ""),
                            severity = Severity.WARNING,
                            code = "unknown-modifier",
                            link = link,
                            fixes =
                                if (near !=
                                    null
                                ) {
                                    listOf(Fix("Change to __$near", mstart, mstart + mod.name.length, near))
                                } else {
                                    emptyList()
                                },
                        )
                    continue
                }
                val present = parsed.modifiers.filter { it !== mod }.map { it.name }
                issues += validateModifierArgs(mspec, mod.args, mstart, mend, spec, present, listensFor).map { it.copy(link = link) }
            }
            val value = attr.value
            if (value != null && spec.valueKind == ValueKind.EXPRESSION && value.isNotBlank() && !hasTemplateSyntax(value)) {
                val decoded = decodeEntities(value)

                fun source(
                    start: Int,
                    end: Int,
                ): Pair<Int, Int> {
                    val from = decoded.toSource(start)
                    return attr.valueStart + from to attr.valueStart + if (end > start) maxOf(decoded.toSource(end), from + 1) else from
                }
                for (issue in expressions.validate(decoded.text, spec.returnsValue, hyphenatedSignals, spec)) {
                    val (start, end) = source(issue.start, issue.end)
                    issues +=
                        issue.copy(
                            start = start,
                            end = end,
                            fixes = issue.fixes.map { source(it.start, it.end).let { (s, e) -> it.copy(start = s, end = e) } },
                        )
                }
            }
            if (value != null && spec.valueKind == ValueKind.FILTER && value.isNotBlank() && !hasTemplateSyntax(value)) {
                issues += validateFilter(attr, spec, prefix)
            }
            if (spec.name == "computed" && parsed.key == null && value != null && value.isNotBlank() && !hasTemplateSyntax(value)) {
                issues += validateComputedObject(attr, prefix)
            }
            if (value != null && spec.valueKind == ValueKind.SIGNAL && value.isNotBlank() && !hasTemplateSyntax(value) &&
                named.none { it.start >= attr.valueStart } && !SIGNAL_NAME.matches(value.trim())
            ) {
                issues +=
                    Issue(
                        attr.valueStart,
                        attr.valueEnd,
                        "$prefix${spec.name} takes a signal name, not an expression.",
                        Severity.WARNING,
                        "signal-name-expected",
                    )
            }
            if (spec.valueKind == ValueKind.NONE && value != null && value.isNotBlank()) {
                issues += Issue(attr.valueStart, attr.valueEnd, "$prefix${spec.name} takes no value.", Severity.WARNING, "unexpected-value")
            }
        }
        issues += indicatorWithoutAction(tag, prefix, tagSource)
        return issues
    }

    /**
     * `data-indicator` tracks the requests its own element sends: the client matches the fetch
     * event's element against it. On an element whose attributes send none, it never turns on.
     * Elements with template syntax in an attribute are left alone, since what they send is not
     * known until they are rendered.
     */
    private fun indicatorWithoutAction(
        tag: Tag,
        prefix: String,
        tagSource: String?,
    ): List<Issue> {
        val indicator =
            tag.attributes.firstOrNull {
                val lower = it.name.asciiLowercase()
                // data-indicator: with an empty key is no indicator at all, and says so on its own.
                lower == "${prefix}indicator" ||
                    lower.startsWith("${prefix}indicator__") ||
                    (lower.startsWith("${prefix}indicator:") && !catalog.parseAttributeName(lower, prefix)?.key.isNullOrEmpty())
            } ?: return emptyList()
        // With both a key and a value, or neither, Datastar raises before the indicator exists,
        // and the error on the same name says so.
        val hasKey = !catalog.parseAttributeName(indicator.name.asciiLowercase(), prefix)?.key.isNullOrEmpty()
        if (hasKey == !indicator.value.isNullOrEmpty()) return emptyList()
        // Template syntax in a value, or attributes spread into the tag by name, leave what it
        // sends unknown until rendered.
        if ((tagSource != null && hasTemplateSyntax(tagSource)) ||
            tag.attributes.any { (it.value != null && hasTemplateSyntax(it.value)) || !PLAIN_ATTRIBUTE_NAME.matches(it.name) }
        ) {
            return emptyList()
        }
        val sends =
            tag.attributes.any {
                it.name.asciiLowercase().startsWith(prefix) && it.value != null &&
                    backendAction.containsMatchIn(decodeEntities(it.value).text)
            }
        if (sends) return emptyList()
        return listOf(
            Issue(
                indicator.nameStart,
                indicator.nameEnd,
                "${prefix}indicator tracks the requests this element sends, and nothing on it sends one. " +
                    "Put it on the element whose attribute calls " + backendActions.joinToString(", ") { "@$it" } + ".",
                Severity.WARNING,
                "indicator-without-action",
                Docs.attribute("indicator"),
            ),
        )
    }

    /**
     * `data-on:click.prevent`: a modifier written after a dot, which Datastar reads as part of the
     * event name, so the listener waits for an event that never comes. An error, unlike an unknown
     * modifier, which Datastar ignores. Only for events; a dot in a signal key is a path.
     */
    private fun validateDottedKey(
        attr: Attribute,
        parsed: ParsedAttribute,
        spec: AttributeSpec,
        prefix: String,
    ): List<Issue> {
        val key = parsed.key ?: return emptyList()
        if ('.' !in key || keyKind(spec) != KeyKind.EVENT) return emptyList()
        val pieces = key.split('.')
        val mods = pieces.drop(1)
        if (pieces[0].isEmpty() || mods.any { m -> spec.modifiers.none { it.name == m } }) return emptyList()
        val keyStart = attr.nameStart + attr.name.indexOf(':') + 1
        val fixed = pieces[0] + mods.joinToString("") { "__$it" }
        return listOf(
            Issue(
                start = keyStart,
                end = keyStart + key.length,
                message =
                    "Datastar reads $key as ${keyReading(spec, key, key)}, dots and all, so the expression never runs on ${pieces[0]}. " +
                        "A modifier follows two underscores: $prefix${spec.name}:$fixed.",
                severity = Severity.ERROR,
                code = "unknown-modifier",
                link = Docs.attribute(spec.name),
                fixes = listOf(Fix("Change to $fixed", keyStart, keyStart + key.length, fixed)),
            ),
        )
    }

    /**
     * The signal an attribute declares, when the client cannot hold it or no `$` reference reaches
     * it: the key as the client reads it ([clientKeyName]), the name in the value of `data-bind`,
     * `data-ref` and `data-indicator`, and the keys of the object form. The DSL holds a name to the
     * same rules (`SignalNames` in streamlord-core, `Casing.signalName` and `Casing.heldKey` in
     * streamlord-html).
     */
    private fun validateSignalNames(
        attr: Attribute,
        parsed: ParsedAttribute,
        spec: AttributeSpec,
        prefix: String,
        cut: Boolean,
    ): List<Issue> {
        if (keyKind(spec) != KeyKind.SIGNAL) return emptyList()
        val out = ArrayList<Issue>()
        val name = prefix + spec.name
        val link = Docs.attribute(spec.name)
        val colon = attr.name.indexOf(':')
        // data-signals:__proto__ has no key to the client: the `__` starts the modifiers, and no plugin is named `signals:`.
        if (!parsed.key.isNullOrEmpty() && colon >= 0 && !cut) {
            val written = attr.name.substring(colon + 1)
            val held = clientKeyName(written.asciiLowercase())
            val keyStart = attr.nameStart + colon + 1
            val keyEnd = keyStart + written.substringBefore("__").length
            // data-signals:constructor="{a: 1}" puts an object under the last part, which the client then writes into the built-in one.
            val objectUnder = spec.name == "signals" && mayGiveObject(decodeEntities(attr.value ?: "").text)
            val path = signalPathProblem(held, objectUnder)
            val reach = if (path == null) signalNameProblem(held) else null
            if (path != null || reach != null) {
                out +=
                    Issue(
                        keyStart,
                        keyEnd,
                        "Datastar's client reads this key as the signal $held, which ${path ?: reach}.",
                        if (path != null) Severity.ERROR else Severity.WARNING,
                        "signal-name",
                        link,
                    )
            }
        }
        val value = attr.value
        if (value == null || value.isBlank() || hasTemplateSyntax(value)) return out
        val decoded = decodeEntities(value)

        fun source(
            from: Int,
            to: Int,
        ): Pair<Int, Int> = attr.valueStart + decoded.toSource(from) to attr.valueStart + decoded.toSource(to)
        if (spec.valueKind == ValueKind.SIGNAL && NAME_LIKE.matches(decoded.text)) {
            val held = decoded.text
            val path = signalPathProblem(held)
            val reach = if (path == null) signalNameProblem(held) else null
            if (path != null || reach != null) {
                val (start, end) = source(0, held.length)
                val fixes =
                    if (path == null && held.startsWith("$") && signalNameProblem(held.drop(1)) == null) {
                        val (fs, fe) = source(0, 1)
                        listOf(Fix("Change to ${held.drop(1)}", fs, fe, ""))
                    } else {
                        emptyList()
                    }
                out +=
                    Issue(
                        start,
                        end,
                        "$name declares the signal $held, as written, which ${path ?: reach}.",
                        if (path != null) Severity.ERROR else Severity.WARNING,
                        "signal-name",
                        link,
                        fixes,
                    )
            }
        }
        val text = decoded.text
        val open = text.indexOf('{')
        if (parsed.key == null && (spec.name == "signals" || spec.name == "computed") && open >= 0 &&
            text.substring(0, open).isBlank() && text.trimEnd().endsWith("}")
        ) {
            val body = text.substring(open + 1, text.lastIndexOf('}'))
            for (k in objectKeys(body)) {
                val (start, end) = source(open + 1 + k.start, open + 1 + k.end)
                when {
                    k.key == "__proto__" -> {
                        out +=
                            Issue(
                                start,
                                end,
                                "In an object literal __proto__ sets the object's prototype, so Datastar gets no signal from it.",
                                Severity.WARNING,
                                "signal-name",
                                link,
                            )
                    }

                    k.objectUnder && k.key in PROTOTYPE_NAMES -> {
                        out +=
                            Issue(
                                start,
                                end,
                                "Every object already has ${k.key}. The client holds the signal, but the next request writes " +
                                    "the object under it into the built-in one and sends the page's signals without it.",
                                Severity.ERROR,
                                "signal-name",
                                link,
                            )
                    }

                    else -> {
                        val parts = keyParts(k)
                        signalNameProblem(k.key, parts)?.let {
                            out +=
                                Issue(
                                    start,
                                    end,
                                    "This declares the signal ${parts.joinToString(".")}, as written, which $it.",
                                    Severity.WARNING,
                                    "signal-name",
                                    link,
                                )
                        }
                    }
                }
            }
        }
        return out
    }

    /**
     * `data-computed` without a key takes an object of functions (computed.ts in 1.0.4): any other
     * value declares nothing, and a value under a key that is no function raises ComputedExpectedFunction.
     * Only a value sure to be no function is judged: `fns`, `{total: $a ? f : g}` and
     * `{total: new Function(...)}` may well give one.
     */
    private fun validateComputedObject(
        attr: Attribute,
        prefix: String,
    ): List<Issue> {
        val value = attr.value ?: ""
        val decoded = decodeEntities(value)
        val text = decoded.text
        val open = text.indexOf('{')
        val name = "${prefix}computed"
        val link = Docs.attribute("computed")

        fun source(
            from: Int,
            to: Int,
        ): Pair<Int, Int> = attr.valueStart + decoded.toSource(from) to attr.valueStart + decoded.toSource(to)
        if (open < 0 || text.substring(0, open).isNotBlank()) {
            if (!certainlyNoFunction(text)) return emptyList()
            val (start, end) = source(0, text.length)
            return listOf(
                Issue(
                    start,
                    end,
                    "Without a key, $name takes an object of functions, such as {total: () => \$a + 1}: Datastar declares no signal " +
                        "from this value, and raises ComputedExpectedFunction when it is a string. Name the signal in the key instead: $name:total.",
                    Severity.WARNING,
                    "computed-value",
                    link,
                ),
            )
        }
        val close = text.lastIndexOf('}')
        val body = text.substring(open + 1, if (close < open) text.length else close)
        val regions = jsRegions(body)

        fun valueOf(k: ObjectKey): String = body.substring(k.valueStart, valueEnd(body, regions, k.valueStart))
        return objectKeys(body).filter { !it.objectUnder && certainlyNoFunction(valueOf(it)) }.map {
            val (start, end) = source(open + 1 + it.start, open + 1 + it.end)
            Issue(
                start,
                end,
                "Without a key, each value of $name must be a function, such as ${it.key}: () => ...; " +
                    "Datastar raises ComputedExpectedFunction for ${it.key}.",
                Severity.ERROR,
                "computed-value",
                link,
            )
        }
    }

    /**
     * The value of `data-json-signals` and `data-on-signal-patch-filter`: JSON, or else JavaScript the
     * client evaluates as `return (value)` without rewriting signals (`jsStrToObject` in text.ts),
     * whose `include` and `exclude` become regular expressions (`filtered` in signals.ts).
     */
    private fun validateFilter(
        attr: Attribute,
        spec: AttributeSpec,
        prefix: String,
    ): List<Issue> {
        val value = attr.value ?: ""
        val decoded = decodeEntities(value)
        val text = decoded.text
        val name = prefix + spec.name
        val link = Docs.attribute(spec.name)

        fun source(
            from: Int,
            to: Int,
        ): Pair<Int, Int> = attr.valueStart + decoded.toSource(from) to attr.valueStart + decoded.toSource(maxOf(to, from + 1))
        val out = ArrayList<Issue>()
        val json = runCatching { JsonParser.parse(text) }.isSuccess
        if (!json) {
            val err = expressionError(text)
            if (err != null) {
                val (start, end) = source(err.pos, err.raisedAt)
                return listOf(Issue(start, end, "Datastar filter: ${err.message}", Severity.ERROR, "expression-syntax", link))
            }
            val regions = jsRegions(text)
            for (m in FILTER_SIGNAL.findAll(text)) {
                val at = m.range.first
                val before = text.getOrNull(at - 1)
                if (regions[at] != Region.CODE ||
                    (before != null && (before == '$' || WORD_CHAR_CLASS.matches(before.toString())))
                ) {
                    continue
                }
                val (start, end) = source(at, m.range.last + 1)
                out +=
                    Issue(
                        start,
                        end,
                        "Datastar evaluates the value of $name without signals, so ${m.value} is a JavaScript variable here, which does not exist.",
                        Severity.WARNING,
                        "signal-not-read",
                        link,
                    )
            }
        }
        for (issue in filterPatternIssues(text, jsRegions(text), 0, text.length, json, link)) {
            val (start, end) = source(issue.start, issue.end)
            out +=
                issue.copy(
                    start = start,
                    end = end,
                    fixes = issue.fixes.map { f -> source(f.start, f.end).let { (s, e) -> f.copy(start = s, end = e) } },
                )
        }
        return out
    }

    /**
     * The browser lowercases attribute names, so a capital letter in a key never reaches Datastar:
     * `data-signals:fooBar` declares `$foobar`, `data-on:widgetLoaded` listens to `widgetloaded`.
     * The fix writes the key as [wireKey] says, so that the name the author typed is the name the
     * browser ends up with.
     */
    private fun validateKeyCase(
        attr: Attribute,
        parsed: ParsedAttribute,
        spec: AttributeSpec,
        prefix: String,
    ): List<Issue> {
        val colon = attr.name.indexOf(':')
        if (colon < 0 || spec.keyCase == null) return emptyList()
        val key = attr.name.substring(colon + 1).substringBefore("__")
        if (!HAS_CAPITAL.containsMatchIn(key)) return emptyList()
        // HTML written in capitals (DATA-ON:CLICK) is not camelCase: lowercased, the key is what the author meant.
        if (key.length > 1 && !HAS_LOWERCASE.containsMatchIn(key)) return emptyList()
        val keyStart = attr.nameStart + colon + 1
        val name = prefix + spec.name
        val existing = parsed.modifiers.firstOrNull { it.name == "case" }
        // With an explicit __case already there, only the key itself changes, when that case gives the name back.
        val fixed =
            if (existing != null) {
                kebab(key).takeIf { clientCase(it, listOf(existing), "camel") == key }
            } else {
                wireKey(key, spec.keyCase, null)
            }
        val reaches = keyReading(spec, key.lowercase(), key.lowercase())
        if (fixed == null) {
            val noKey =
                if (existing != null) {
                    val written = "__case." + existing.args.joinToString(".")
                    "No key with $written gives $key back: the browser lowercases the key, and from ${kebab(key)}$written " +
                        "Datastar reads ${clientCase(kebab(key), listOf(existing), "camel")}."
                } else {
                    "No key gives $key back: with __case.camel or __case.pascal Datastar turns every hyphen before a " +
                        "lower-case letter into a capital and reads ${wireKeyReads(key)}."
                }
            return listOf(
                Issue(
                    start = keyStart,
                    end = keyStart + key.length,
                    message =
                        "The browser lowercases attribute names, so this reaches Datastar as $reaches, not $key. " +
                            "$noKey Write it in one case.",
                    severity = Severity.WARNING,
                    code = "key-case",
                    link = Docs.attribute(spec.name),
                ),
            )
        }
        val keep =
            if (existing !=
                null
            ) {
                "__" + existing.name + (if (existing.args.isNotEmpty()) "." + existing.args.joinToString(".") else "")
            } else {
                ""
            }
        val camelNote = if (keyKind(spec) == KeyKind.SIGNAL) ", and Datastar reads a kebab-case signal key as camelCase" else ""
        if (keyKind(spec) == KeyKind.RAW) {
            // A kebab-case key would set view-box, not viewBox: for an attribute the object form keeps the
            // name, for a style property no form does, since the object form writes it kebab-case.
            val value = attr.value
            val quote =
                if (value == null || '"' !in value) {
                    '"'
                } else if ('\'' !in value) {
                    '\''
                } else {
                    null
                }
            val fixes =
                if (spec.name == "attr" && value != null && quote != null) {
                    val end = attr.valueEnd + if (attr.quoted) 1 else 0
                    listOf(Fix("Use the object form", attr.nameStart, end, "$name=$quote{$key: $value}$quote"))
                } else if (spec.name == "style" && !key.startsWith("--")) {
                    listOf(Fix("Change to ${kebab(key)}", keyStart, keyStart + key.length, kebab(key)))
                } else {
                    emptyList()
                }
            return listOf(
                Issue(
                    start = keyStart,
                    end = keyStart + key.length,
                    message = "The browser lowercases attribute names, so this sets ${key.lowercase()}, not $key.${rawKeyNote(
                        spec,
                        prefix,
                        key,
                    )}",
                    severity = Severity.WARNING,
                    code = "key-case",
                    link = Docs.attribute(spec.name),
                    fixes = fixes,
                ),
            )
        }
        return listOf(
            Issue(
                start = keyStart,
                end = keyStart + key.length,
                message =
                    "The browser lowercases attribute names, so this reaches Datastar as $reaches, not $key. " +
                        "Write $name:$fixed$keep: keys are kebab-case$camelNote.",
                severity = Severity.WARNING,
                code = "key-case",
                link = Docs.attribute(spec.name),
                fixes = listOf(Fix("Change to $fixed", keyStart, keyStart + key.length, fixed)),
            ),
        )
    }

    /**
     * `debounce_150ms` read as `debounce.150ms`, when the part before the first underscore is a
     * modifier of [spec] that takes arguments, the rest is plain words, and those words are
     * arguments it takes; null otherwise, so the fix never offers something that fails in turn.
     */
    private fun dottedArguments(
        name: String,
        spec: AttributeSpec,
    ): String? {
        val underscore = name.indexOf('_')
        if (underscore <= 0 || underscore == name.length - 1 || !MODIFIER_WORD.matches(name)) return null
        val head = name.substring(0, underscore)
        val modifier = spec.modifiers.firstOrNull { it.name == head } ?: return null
        if (modifier.type == ModifierType.FLAG) return null
        val args = name.substring(underscore + 1).split('_')
        if (validateModifierArgs(modifier, args, 0, 0, spec).isNotEmpty()) return null
        return head + "." + args.joinToString(".")
    }

    private fun validateModifierArgs(
        spec: Modifier,
        args: List<String>,
        start: Int,
        end: Int,
        attribute: AttributeSpec,
        present: Collection<String> = emptyList(),
        listensFor: String = "",
    ): List<Issue> {
        val issues = ArrayList<Issue>()

        fun fail(
            message: String,
            fixes: List<Fix> = emptyList(),
        ) {
            issues += Issue(start, end, message, Severity.ERROR, "modifier-args", fixes = fixes)
        }

        // Arguments the client never reads: the modifier still does what it does, so these are warnings.
        fun ignored(
            message: String,
            fixes: List<Fix> = emptyList(),
        ) {
            issues += Issue(start, end, message, Severity.WARNING, "modifier-args", fixes = fixes)
        }

        // An empty one, __debounce.500ms., is nothing the client reads: its tags are a set of the names.
        fun unknownFlag(f: String) {
            if (f.isEmpty()) return
            ignored(
                "Unknown flag .$f for __${spec.name}, which Datastar ignores. " +
                    "Allowed: ${spec.flags.joinToString(", ") { ".$it" }.ifEmpty { "none" }}.",
            )
        }
        when (spec.type) {
            ModifierType.FLAG -> {
                // __prevent.stop: a second modifier joined with a dot, read as an argument.
                val dropped = "__${spec.name} takes no arguments; Datastar ignores ${args.joinToString("") { ".$it" }}."
                val siblings = attribute.modifiers.filter { it.type == ModifierType.FLAG && it.name != spec.name }.map { it.name }
                if (args.isNotEmpty() && args.all { it in siblings }) {
                    // Each once, and not one the attribute already has.
                    val joined = args.distinct().filter { it !in present }.joinToString("") { "__$it" }
                    val at = start + spec.name.length
                    if (joined.isEmpty()) {
                        ignored(dropped, listOf(Fix("Change to __${spec.name}", at, end, "")))
                    } else {
                        ignored(
                            "$dropped Each modifier starts with two underscores: __${spec.name}$joined.",
                            listOf(Fix("Change to __${spec.name}$joined", at, end, joined)),
                        )
                    }
                } else if (args.isNotEmpty()) {
                    ignored(dropped)
                }
            }

            ModifierType.DURATION -> {
                val d = args.firstOrNull()
                val flags = args.drop(1)
                val later = flags.indexOfFirst { DURATION.matches(it) }
                val at = start + spec.name.length
                if (d != null && !DURATION.matches(d) && d in spec.flags && later >= 0) {
                    // __debounce.leading.500ms: the client reads the first argument as the duration.
                    val ordered = listOf(flags[later], d) + flags.filterIndexed { k, _ -> k != later }
                    val fixed = "__${spec.name}.${ordered.joinToString(".")}"
                    val unknown = flags.filterIndexed { k, f -> k != later && f !in spec.flags }
                    // Only with every other argument a flag it knows is the reordered modifier the one meant:
                    // __debounce.leading.1.5s would become __debounce.1.leading.5s.
                    fail(
                        "Datastar reads the first argument of __${spec.name} as its duration, " +
                            "so with .$d first it reads none and does not wait. The duration comes first" +
                            (if (unknown.isEmpty()) ": $fixed." else "."),
                        if (unknown.isEmpty()) listOf(Fix("Change to $fixed", at, end, "." + ordered.joinToString("."))) else emptyList(),
                    )
                    for (f in unknown) {
                        if (!DURATION.matches(f)) unknownFlag(f)
                    }
                    return issues
                }
                val next = flags.firstOrNull()
                if (d != null && WHOLE_NUMBER.matches(d) && next != null && DURATION.matches(next)) {
                    // __debounce.1.5s: the dot ends the duration, which the client reads as 1 ms.
                    val seconds = next.endsWith("s") && !next.endsWith("ms")
                    val ms = "$d.${next.removeSuffix("ms").removeSuffix("s")}".toDouble() * if (seconds) 1000 else 1
                    val whole = if (ms == Math.floor(ms) && !ms.isInfinite()) "${ms.toLong()}ms" else null
                    // With a flag it does not know after it, the whole duration would fail in turn, so it is no fix.
                    val fixable = whole != null && flags.drop(1).all { it in spec.flags }
                    fail(
                        "Datastar reads __${spec.name}.$d.$next as $d ms: a dot separates arguments, so a duration has no fraction." +
                            (whole?.let { " Write __${spec.name}.$it." } ?: ""),
                        if (fixable) {
                            listOf(
                                Fix("Change to __${spec.name}.$whole", at, at + 2 + d.length + next.length, ".$whole"),
                            )
                        } else {
                            emptyList()
                        },
                    )
                    for (f in flags.drop(1)) {
                        if (f !in spec.flags) unknownFlag(f)
                    }
                    return issues
                }
                if (d.isNullOrEmpty() || !DURATION.matches(d)) {
                    val at = start + spec.name.length
                    // A dot with nothing after it, __debounce., is no duration either, and the fix takes its place.
                    fail(
                        "__${spec.name} needs a duration such as __${spec.name}.500ms or __${spec.name}.1s.",
                        if (d.isNullOrEmpty() && args.size <= 1) listOf(Fix("Add .500ms", at, end, ".500ms")) else emptyList(),
                    )
                }
                for (f in args.drop(1)) {
                    if (f !in spec.flags) unknownFlag(f)
                }
            }

            ModifierType.ENUM -> {
                val v = args.firstOrNull()
                val rest = args.drop(1)
                val allowed = spec.values.joinToString(", ") { ".$it" }
                if (v == null || v !in spec.values) {
                    // The client leaves the key as it is for a case it has no function for. Where the default
                    // is kebab, which it has none for either, that is the key without the modifier.
                    if (spec.name == "case" && attribute.keyCase == KeyCase.KEBAB) {
                        val also = rest.filter { it !in CASE_FNS }.joinToString("") { ".$it" }
                        val head =
                            if (!v.isNullOrEmpty()) {
                                "Unknown value .$v for __case, which Datastar ignores" + if (also.isEmpty()) "" else ", as it does $also"
                            } else {
                                "Datastar ignores __case without a value"
                            }
                        ignored("$head$listensFor. Allowed: $allowed.")
                    } else {
                        fail("__${spec.name} needs one of: $allowed.")
                    }
                }
                // __case.camel.foo: the client applies each value it has a function for and skips the rest.
                val applied = if (spec.name == "case") rest.filter { it in CASE_FNS } else rest
                if (applied.isNotEmpty()) {
                    fail("__${spec.name} takes a single value.")
                } else if (rest.isNotEmpty() && v != null && v in spec.values) {
                    ignored("__${spec.name} takes a single value; Datastar ignores ${rest.joinToString("") { ".$it" }}$listensFor.")
                }
            }

            ModifierType.INT -> {
                // Written in ASCII digits, as the editor reads it: the client's Number takes no other digits.
                val n = args.firstOrNull()?.takeIf { ASCII_INTEGER.matches(it) }?.toIntOrNull()
                if (n == null || (spec.min != null && n < spec.min) || (spec.max != null && n > spec.max)) {
                    val range = if (spec.min != null) " between ${spec.min} and ${spec.max}" else ""
                    fail("__${spec.name} needs an integer$range, e.g. __${spec.name}.50.")
                }
                if (args.size > 1) fail("__${spec.name} takes a single value.")
            }

            ModifierType.IDENT -> {
                val v = args.firstOrNull()
                if (v == null || !IDENT.matches(v)) fail("__${spec.name} needs a name, e.g. __${spec.name}.value.")
                if (args.size > 1) fail("__${spec.name} takes a single name.")
            }

            ModifierType.IDENTS -> {
                if (args.isEmpty() || args.any { !IDENT.matches(it) }) {
                    fail("__${spec.name} needs one or more names, e.g. __${spec.name}.input.blur.")
                }
            }
        }
        return issues
    }
}
