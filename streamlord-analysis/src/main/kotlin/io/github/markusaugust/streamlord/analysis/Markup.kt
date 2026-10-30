package io.github.markusaugust.streamlord.analysis

import io.github.markusaugust.streamlord.core.catalog.CatalogNames
import io.github.markusaugust.streamlord.core.client.ClientCasing
import io.github.markusaugust.streamlord.core.client.HTML_SPACE
import io.github.markusaugust.streamlord.core.client.RAW_TEXT
import io.github.markusaugust.streamlord.core.client.asciiLowercase
import io.github.markusaugust.streamlord.core.client.commentEnd
import io.github.markusaugust.streamlord.core.client.decodeEntities
import io.github.markusaugust.streamlord.core.client.rawTextEnd
import io.github.markusaugust.streamlord.core.domain.AttributeKeys
import io.github.markusaugust.streamlord.core.domain.ElementsGuard
import io.github.markusaugust.streamlord.core.domain.SignalNames
import io.github.markusaugust.streamlord.core.json.JsonParser
import kotlin.math.abs

/*
 * Markup checks for the HTML Datastar patches: complete elements, ids where the protocol needs
 * them, and well-formed `data-*` attributes. A deliberately small tokenizer, not a browser.
 */

/** An attribute name as a browser parses it, without template syntax or a spread in it. */
private val PLAIN_ATTRIBUTE_NAME = Regex("""[A-Za-z_:][A-Za-z0-9_:.@-]*""")

/** A modifier name with arguments run into it by underscores, and nothing else. */
private val MODIFIER_WORD = Regex("""[a-z][a-z0-9]*(?:_[A-Za-z0-9]+)+""")

private val VOID = setOf("area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param", "source", "track", "wbr")

private val JS_DECIMAL = Regex("[+-]?[0-9]+(?:e[+-]?[0-9]+)?")

private val JS_RADIX = Regex("0([xob])([0-9a-f]+)")

/** The modifiers whose value the client reads from the first tag, empty or not: `tagFirst` and `tagToMs` in tags.ts. */
private val FIRST_TAG_READ = setOf(ModifierType.DURATION, ModifierType.INT, ModifierType.IDENT)

/** The white space JavaScript's `Number` trims: its WhiteSpace and LineTerminator. */
private const val JS_SPACE =
    "\t\u000B\u000C\uFEFF \u00A0\u1680\u2000\u2001\u2002\u2003\u2004\u2005\u2006\u2007\u2008\u2009\u200A" +
        "\u202F\u205F\u3000\n\r\u2028\u2029"

/**
 * JavaScript's `Number` of [tag], a modifier argument, which holds no dot and which the browser
 * has lowercased: none is 0, and `1e1` and `0x32` are numbers too. Null where it gives NaN.
 */
private fun jsNumber(tag: String): Double? {
    val t = tag.trim { it in JS_SPACE }.asciiLowercase()
    if (t.isEmpty()) return 0.0
    if (JS_DECIMAL.matches(t)) return t.toDouble()
    val m = JS_RADIX.matchEntire(t) ?: return null
    val radix =
        when (m.groupValues[1]) {
            "x" -> 16
            "o" -> 8
            else -> 2
        }
    return m.groupValues[2].toBigIntegerOrNull(radix)?.toDouble()
}

/** What JavaScript's `parseFloat` reads from the start of a tag: digits, and an exponent if one follows. */
private val JS_FLOAT_PREFIX = Regex("^[+-]?[0-9]+(?:e[+-]?[0-9]+)?")

/**
 * `tagToMs` in tags.ts for one tag: the milliseconds the client waits, `Number` of what comes
 * before an `ms` or an `s`, and `parseFloat` of any other tag, which reads `5x` as 5. Null where
 * it gives NaN.
 */
private fun clientMs(tag: String): Double? {
    val t = tag.asciiLowercase()
    return when {
        t.endsWith("ms") -> jsNumber(t.dropLast(2))
        t.endsWith("s") -> jsNumber(t.dropLast(1))?.times(1000)
        else -> JS_FLOAT_PREFIX.find(t)?.value?.toDouble()
    }
}

/** [ms] as JavaScript writes a number of no more than fifteen digits: `5`, `1.5`. */
private fun jsFormat(ms: Double): String = if (ms % 1 == 0.0) ms.toLong().toString() else ms.toString()

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

// A Kotlin template as the name, `<$tag>`, is a tag all the same: the browser sees a name there.
private val TAG_OPEN = Regex("""^<(/?)((?:[A-Za-z]|__kt__)[A-Za-z0-9:._-]*)""")

/** A name also ends where a template construct starts: `data-x{{/if}}`, `data-x</#if>`. */
private val ATTR_NAME = Regex("""^[^\t\n\f\r "'<>/={]+""")
private val UNQUOTED_VALUE = Regex("""^[^\t\n\f\r >]*""")

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

private val TEMPLATE_CALL = Regex("""^[@#][A-Za-z]+\s*\(""")

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
    val content = ForeignContent()

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
        if (html.startsWith("<!--", i)) {
            flushText(i)
            i = commentEnd(html, i)
            continue
        }
        // A FreeMarker comment, which only `-->` ends.
        if (html.startsWith("<#--", i)) {
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
        // Where the current element is foreign, `<![CDATA[` opens a section of text that only `]]>` ends;
        // in HTML it is a bogus comment, which the first `>` ends.
        if (html.startsWith("<![CDATA[", i) && content.foreign) {
            flushText(i)
            val close = html.indexOf("]]>", i + 9)
            i = if (close < 0) html.length else close + 3
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
                if (c in HTML_SPACE) {
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
                while (k < html.length && html[k] in HTML_SPACE) k++
                var value: String? = null
                var valueStart = -1
                var quoted = false
                if (k < html.length && html[k] == '=') {
                    k++
                    while (k < html.length && html[k] in HTML_SPACE) k++
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
            // As the browser reads it: a `/>` closes no raw-text element, plaintext runs to the end,
            // and only `</name` as a whole name ends the others (rawTextEnd, which core's scanner
            // shares; it reads svg and math by walking the markup each way instead).
            if (closing) content.end(name)
            val foreign = !closing && content.start(name, selfClosing, attributes)
            val rawText = !closing && name in RAW_TEXT && !foreign
            tags += Tag(name, i, end, closing, (selfClosing && !rawText) || name in VOID, attributes)
            if (rawText) {
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

private val FOREIGN_ROOTS = setOf("svg", "math")

/**
 * Whether [name], opened in the foreign element [root], is an HTML integration point, in which the
 * browser reads the content as HTML: a foreignObject, desc or title in an svg, and in a math the
 * text elements and an annotation-xml whose encoding is HTML.
 */
private fun integrationPoint(
    root: String,
    name: String,
    attributes: List<Attribute>,
): Boolean =
    when (root) {
        "svg" -> {
            name == "foreignobject" || name == "desc" || name == "title"
        }

        else -> {
            name in MATHML_TEXT ||
                (
                    name == "annotation-xml" &&
                        attributes.any { it.name.asciiLowercase() == "encoding" && it.value?.asciiLowercase() in HTML_ENCODINGS }
                )
        }
    }

private val MATHML_TEXT = setOf("mi", "mo", "mn", "ms", "mtext")

private val HTML_ENCODINGS = setOf("text/html", "application/xhtml+xml")

/**
 * The start tags that end foreign content, so that the browser reads them, and what follows, as
 * HTML: `<svg><p><style>` holds an HTML style. A font does so with a color, face or size.
 */
private val BREAKOUT =
    (
        "b big blockquote body br center code dd div dl dt em embed h1 h2 h3 h4 h5 h6 head hr i img li listing menu meta nobr ol p pre " +
            "ruby s small span strong strike sub sup table tt u ul var"
    ).split(" ").toSet()
private val FONT_BREAKOUT = setOf("color", "face", "size")

/** The end tags that do the same. */
private val BREAKOUT_END = setOf("br", "p")

/**
 * The HTML elements an end tag cannot close past in HTML content (the "special" category of the
 * HTML standard): `</b>` inside a `div` inside a `b` leaves the `b` open.
 */
private val SPECIAL =
    (
        "address applet area article aside base basefont bgsound blockquote body br button caption center col colgroup dd " +
            "details dir div dl dt embed fieldset figcaption figure footer form frame frameset h1 h2 h3 h4 h5 h6 head header " +
            "hgroup hr html iframe img input keygen li link listing main marquee menu meta nav noembed noframes noscript object " +
            "ol p param plaintext pre script search section select source style summary table tbody td template textarea tfoot " +
            "th thead title tr track ul wbr xmp"
    ).split(" ").toSet()

/**
 * Where the tokenizer stands in foreign content, as the browser's tree builder reads it: the svg
 * and math elements open, the HTML integration points open in them, and the elements open inside
 * each, which decide where an svg begins and what an end tag closes. Inside an svg or a math a
 * `style` or a `script` is no raw text and `/>` closes an element; inside an integration point,
 * such as a foreignObject, the content is HTML again.
 */
private class ForeignContent {
    /** An element that changes how its content is read, and the elements open inside it. */
    private class Scope(
        val name: String,
        val foreign: Boolean,
        val namespace: String,
    ) {
        val open = ArrayList<String>()
    }

    // The document first, whose open elements an end tag can close an svg or a math by.
    private val scopes = arrayListOf(Scope("", false, "html"))

    /** Whether the current node is an svg or MathML element, in which `<![CDATA[` opens a section of text. */
    val foreign: Boolean get() = scopes.last().foreign

    /** Reads a start tag; returns whether it stands in foreign content, where no element holds raw text. */
    fun start(
        name: String,
        selfClosing: Boolean,
        attributes: List<Attribute>,
    ): Boolean {
        if (foreign && breaksOut(name, false, attributes)) popForeign()
        val top = scopes.last()
        if (top.foreign) {
            when {
                // Inside a math an svg is a MathML element, unless it opens right in an annotation-xml.
                name == "svg" && top.namespace == "math" && top.open.lastOrNull() == "annotation-xml" -> {
                    if (!selfClosing) scopes += Scope(name, true, "svg")
                }

                integrationPoint(top.namespace, name, attributes) -> {
                    if (!selfClosing) scopes += Scope(name, false, top.namespace)
                }

                !selfClosing -> {
                    top.open += name
                }
            }
            return true
        }
        when {
            // Right inside a MathML text element, mglyph and malignmark stay MathML.
            top.open.isEmpty() && top.name in MATHML_TEXT && (name == "mglyph" || name == "malignmark") -> {
                if (!selfClosing) scopes += Scope(name, true, "math")
            }

            name in FOREIGN_ROOTS -> {
                if (!selfClosing) scopes += Scope(name, true, name)
            }

            name !in VOID && name !in RAW_TEXT -> {
                top.open += name
            }
        }
        return false
    }

    /** Reads an end tag. */
    fun end(name: String) {
        if (foreign && breaksOut(name, true, emptyList())) return popForeign()
        val top = scopes.last()
        if (top.foreign || (top.open.isEmpty() && scopes.size > 1)) {
            // Foreign content's rule, which an integration point with nothing open inside it follows
            // too: the nearest open element of the name, until an HTML element is open, whose rule
            // takes over from there.
            for (k in scopes.indices.reversed()) {
                val scope = scopes[k]
                if (!scope.foreign && scope.open.isNotEmpty() && k != scopes.lastIndex) {
                    // Every integration point is special, so one open in between stops it.
                    if (scopes.subList(k + 1, scopes.size).any { !it.foreign }) return
                    return closeHtml(k, name)
                }
                val at = if (scope.foreign) scope.open.lastIndexOf(name) else -1
                if (at >= 0) {
                    scopes.subList(k + 1, scopes.size).clear()
                    return scope.open.subList(at, scope.open.size).clear()
                }
                if (k > 0 && scope.name == name) return scopes.subList(k, scopes.size).clear()
            }
            return
        }
        closeHtml(scopes.lastIndex, name)
    }

    /**
     * HTML content's rule for an end tag, from the elements open in the scope at [k]: the nearest
     * open element of the name, unless a special one stands between, then the integration point
     * itself, when the end tag spells its name. foreignObject's, which the element has in camel
     * case, it never does.
     */
    private fun closeHtml(
        k: Int,
        name: String,
    ) {
        val scope = scopes[k]
        for (e in scope.open.indices.reversed()) {
            if (scope.open[e] == name) {
                scopes.subList(k + 1, scopes.size).clear()
                return scope.open.subList(e, scope.open.size).clear()
            }
            if (scope.open[e] in SPECIAL) return
        }
        if (k > 0 && k == scopes.lastIndex && name == scope.name && name != "foreignobject") scopes.removeAt(k)
    }

    private fun popForeign() {
        while (scopes.last().foreign) scopes.removeAt(scopes.lastIndex)
    }
}

/** Whether a tag ends the foreign content it stands in, as the tree builder reads it. */
private fun breaksOut(
    name: String,
    closing: Boolean,
    attributes: List<Attribute>,
): Boolean {
    if (closing) return name in BREAKOUT_END
    return name in BREAKOUT || (name == "font" && attributes.any { it.name.asciiLowercase() in FONT_BREAKOUT })
}

/**
 * The key to write, and the `__case` to add, so that a key the author typed with capitals comes
 * back as that name: `foo-bar` for a signal (Datastar reads it as camelCase), `widget-loaded__case.camel`
 * for an event or class (kebab by default), `aria-label` where the key is used as it is. An
 * explicit `__case` is kept: the key is still kebab-cased, because the browser lowercases it
 * either way. A signal key is read as camelCase, so `fooBar-baz` comes back as `fooBarBaz`, the
 * name the DSL's `signal()` reads from it too; [ClientCasing.readKey] says what. Null for an event or
 * class name that mixes capitals with a hyphen before a lower-case letter, `fooBar-baz` or `A-b`,
 * since the client's camel and pascal turn that hyphen into a capital too (`modifyCasing` in
 * text.ts) and no key gives it back; [ClientCasing.readsAs] says what it would read. Shared by the HTML
 * warning and the Kotlin wire hint.
 */
public fun wireKey(
    key: String,
    keyCase: KeyCase?,
    explicitCase: String?,
): String? =
    ClientCasing.keyFor(key, explicitCase, keyCase?.takeIf { it != KeyCase.RAW }?.name?.lowercase())?.let { choice ->
        choice.key + (choice.case?.let { "__case.$it" } ?: "")
    }

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

/** The key as the client recases it: each value of the last `__case` in turn, or the attribute's default without one. */
private fun clientCase(
    key: String,
    modifiers: List<ParsedModifier>,
    defaultCase: String,
): String = ClientCasing.modifyCasing(key, modifiers.lastOrNull { it.name == "case" }?.args?.distinct(), defaultCase)

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
            " No form keeps a capital in a custom property: name it in kebab-case, here and in the CSS, as ${ClientCasing.kebab(key)}."
        }

        spec.name == "style" -> {
            " A CSS property is written in kebab-case: $prefix${spec.name}:${ClientCasing.kebab(key)}."
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
            if (prefix == CatalogNames.PREFIX && lower.startsWith(CatalogNames.ALIASED_PREFIX)) {
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
                // The rule of the runtime guard, which a key or a modifier after the name makes stricter.
                val near =
                    ElementsGuard
                        .nearAttribute(parsed.base, qualified = parsed.key != null || parsed.modifiers.isNotEmpty())
                        ?.let { catalog.attributesByName.getValue(it) }
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
                for (issue in expressions.validate(decoded.text, hyphenatedSignals = hyphenatedSignals, attribute = spec)) {
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
     * it: the key as the client reads it ([ClientCasing.readKey]), the name in the value of `data-bind`,
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
            val held = ClientCasing.readKey(written)
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

                    k.objectUnder && k.key in SignalNames.PROTOTYPE_NAMES -> {
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
                ClientCasing.kebab(key).takeIf { clientCase(it, listOf(existing), "camel") == key }
            } else {
                wireKey(key, spec.keyCase, null)
            }
        val reaches = keyReading(spec, key.lowercase(), key.lowercase())
        if (fixed == null) {
            val noKey =
                if (existing != null) {
                    val written = "__case." + existing.args.joinToString(".")
                    "No key with $written gives $key back: the browser lowercases the key, and from ${ClientCasing.kebab(key)}$written " +
                        "Datastar reads ${clientCase(ClientCasing.kebab(key), listOf(existing), "camel")}."
                } else {
                    "No key gives $key back: with __case.camel or __case.pascal Datastar turns every hyphen before a " +
                        "lower-case letter into a capital and reads ${ClientCasing.readsAs(key)}."
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
                    listOf(Fix("Change to ${ClientCasing.kebab(key)}", keyStart, keyStart + key.length, ClientCasing.kebab(key)))
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
        written: List<String>,
        start: Int,
        end: Int,
        attribute: AttributeSpec,
        present: Collection<String> = emptyList(),
        listensFor: String = "",
    ): List<Issue> {
        val issues = ArrayList<Issue>()
        // An empty tag after the value, as in __threshold.50., or the one of __once., is nothing the client reads.
        val args = written.filter { it.isNotEmpty() }

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

        fun unknownFlag(f: String) {
            ignored(
                "Unknown flag .$f for __${spec.name}, which Datastar ignores. " +
                    "Allowed: ${spec.flags.joinToString(", ") { ".$it" }.ifEmpty { "none" }}.",
            )
        }
        spec.ignoredWith.firstOrNull { it in present }?.let {
            // __half__threshold.50: onIntersect.ts takes __half and never reads __threshold.
            ignored("Datastar ignores __${spec.name} with __$it.")
            return issues
        }
        if (spec.type in FIRST_TAG_READ && written.firstOrNull() == "" && args.isNotEmpty()) {
            // __debounce..500ms: the client reads the empty tag as the value and never comes to .500ms.
            // Without it, the modifier is the fix only if it holds up itself: __debounce..leading has no duration.
            val fixed = "." + args.joinToString(".")
            val holds = validateModifierArgs(spec, args, start, end, attribute, present, listensFor).none { it.severity == Severity.ERROR }
            fail(
                "Datastar reads the first argument of __${spec.name}, which is empty here, and not .${args.first()}." +
                    if (holds) " Write __${spec.name}$fixed." else "",
                if (holds) listOf(Fix("Change to __${spec.name}$fixed", start + spec.name.length, end, fixed)) else emptyList(),
            )
            return issues
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
                val read = d?.takeIf { it.isNotEmpty() && !DURATION.matches(it) }?.let(::clientMs)?.takeIf { abs(it) < 1e15 }
                if (d != null && read != null) {
                    // Not written as a duration, but read as one: __debounce.5x waits 5 ms.
                    val at = start + spec.name.length
                    val fix = "${jsFormat(read)}ms".takeIf { read >= 0 && read % 1 == 0.0 }
                    ignored(
                        "Datastar reads __${spec.name}.$d as ${jsFormat(read)} ms." + (fix?.let { " Write __${spec.name}.$it." } ?: ""),
                        fix?.let { listOf(Fix("Change to __${spec.name}.$it", at, at + 1 + d.length, ".$it")) } ?: emptyList(),
                    )
                } else if (d.isNullOrEmpty() || !DURATION.matches(d)) {
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
                        val also = rest.filter { it !in ClientCasing.CASES }.joinToString("") { ".$it" }
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
                val applied = if (spec.name == "case") rest.filter { it in ClientCasing.CASES } else rest
                if (applied.isNotEmpty()) {
                    fail("__${spec.name} takes a single value.")
                } else if (rest.isNotEmpty() && v != null && v in spec.values) {
                    ignored("__${spec.name} takes a single value; Datastar ignores ${rest.joinToString("") { ".$it" }}$listensFor.")
                }
            }

            ModifierType.INT -> {
                // The client reads Number of the first tag and clamps it (onIntersect.ts); NaN fails in the observer.
                val v = args.firstOrNull()
                val n = jsNumber(v ?: "")
                val min = spec.min
                val max = spec.max
                if (n == null) {
                    val range = if (min != null) " between $min and $max" else ""
                    fail("__${spec.name} needs an integer$range, e.g. __${spec.name}.50.")
                } else if (v == null) {
                    ignored("Datastar reads __${spec.name} without a value as 0.")
                } else if ((min != null && n < min) || (max != null && n > max)) {
                    ignored("Datastar clamps __${spec.name}.$v to ${if (min != null && n < min) min else max}.")
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
