package io.github.markusaugust.streamlord.analysis

import io.github.markusaugust.streamlord.core.client.regex
import io.github.markusaugust.streamlord.core.json.JsonArray
import io.github.markusaugust.streamlord.core.json.JsonObject
import io.github.markusaugust.streamlord.core.json.JsonParser

/**
 * The template engine that writes into a file before the browser reads it, where its rules are its
 * own. Every other engine's syntax, Pebble's, Twig's, PHP's, Thymeleaf's among them, is the same in
 * every HTML-side file (catalog/template-syntax.json). Each editor names it from its own file type.
 */
public enum class TemplateEngine {
    /** jte and kte: `${...}` is the engine's inside a JavaScript template too. */
    JTE,

    /** FreeMarker: as jte. */
    FREEMARKER,

    /** Velocity: as jte. */
    VELOCITY,

    /** Razor reads every `@` itself: `@name`, `@(...)`, `@{...}` and `@* *@` are C#, and `@@` writes the `@` Datastar reads. */
    RAZOR,
    ;

    internal val writesDollarBraces: Boolean get() = this != RAZOR
}

/** The constructs of catalog/template-syntax.json: those that end at a closer, those that open a brace, and the directive words. */
private class Constructs(
    val pairs: List<Pair<String, String>>,
    val braces: List<String>,
    val directives: Map<String, List<String>>,
)

private val CONSTRUCTS: Constructs by lazy {
    val raw = JsonParser.parseObject(CatalogText.TEMPLATE_SYNTAX)
    val words = raw["directives"] as JsonObject
    Constructs(
        raw.array("pairs")!!.map { (it as JsonArray).strings().let { (open, close) -> open to close } },
        raw.array("braces")!!.strings(),
        words.keys.associateWith { words.array(it)!!.strings() },
    )
}

/**
 * Where a template engine or a Kotlin template will substitute text, the expression cannot be
 * judged before rendering: the openers of catalog/template-syntax.json, a directive's word after
 * white space or a tag's end, and the placeholder for a Kotlin interpolation.
 */
internal val TEMPLATE_SYNTAX: Regex by lazy {
    val openers = (CONSTRUCTS.pairs.map { it.first } + CONSTRUCTS.braces).sortedByDescending { it.length }.map(Regex::escape)
    val directives = CONSTRUCTS.directives.map { (sigil, words) -> """(?:^|[\s>])${Regex.escape(sigil)}(?:${words.joinToString("|")})\b""" }
    regex((openers + directives + PLACEHOLDER).joinToString("|"))
}

/** Where the construct that starts at [s] of [value] ends. */
internal fun placeholderEnd(
    value: String,
    s: Int,
): Int {
    CONSTRUCTS.pairs.firstOrNull { value.startsWith(it.first, s) }?.let { (open, close) ->
        return value.indexOf(close, s + open.length).let { if (it < 0) value.length else it + close.length }
    }
    if (value.startsWith(PLACEHOLDER, s)) return s + PLACEHOLDER.length
    CONSTRUCTS.braces.firstOrNull { value.startsWith(it, s) }?.let { return balanced(value, s + it.length - 1, '{', '}') }
    // A directive: its word and the condition in parentheses after it.
    var j = s + 1
    while (j < value.length && value[j].isLetter()) j++
    return if (value.getOrNull(j) == '(') balanced(value, j, '(', ')') else j
}

/** The offset after the bracket that closes the one at [open], or the end. */
internal fun balanced(
    text: String,
    open: Int,
    opening: Char,
    closing: Char,
): Int {
    var depth = 0
    var j = open
    while (j < text.length) {
        if (text[j] == opening) depth++
        if (text[j] == closing && --depth == 0) return j + 1
        j++
    }
    return text.length
}

/** A Razor construct: an `@` that is not half of `@@`, before a name, a bracket or a comment's star. */
private val RAZOR_CODE = regex("""(?<!@)@(?!@)(?=[\p{L}_({*])""")

/** Razor's constructs in [value]: each `@` of [RAZOR_CODE] to the end of its name and what it calls, or its brackets. */
internal fun razorConstructs(value: String): List<IntRange> =
    RAZOR_CODE
        .findAll(value)
        .map { m ->
            val s = m.range.first
            val end =
                when (value.getOrNull(s + 1)) {
                    '(' -> {
                        balanced(value, s + 1, '(', ')')
                    }

                    '{' -> {
                        balanced(value, s + 1, '{', '}')
                    }

                    '*' -> {
                        value.indexOf("*@", s + 2).let { if (it < 0) value.length else it + 2 }
                    }

                    else -> {
                        var j = s + 1
                        while (j < value.length &&
                            (
                                value[j].isLetterOrDigit() || value[j] == '_' ||
                                    (value[j] == '.' && value.getOrNull(j + 1)?.isLetter() == true)
                            )
                        ) {
                            j++
                        }
                        if (value.getOrNull(j) == '(') balanced(value, j, '(', ')') else j
                    }
                }
            s until end
        }.toList()

/**
 * The value as the checks judge it in a file of [engine], or null where an engine's syntax leaves it
 * to be judged after rendering. The engine's own constructs that stand where JavaScript allows
 * any text are masked, keeping every offset: Razor's `@@` is the `@` the browser reads, and a
 * `${...}` inside a JavaScript template in jte, FreeMarker or Velocity is text of the template.
 * With [aroundPlaceholders], an expression is judged around the [PLACEHOLDER] of a Kotlin template.
 */
internal fun judgedValue(
    value: String,
    engine: TemplateEngine?,
    aroundPlaceholders: Boolean = false,
): String? {
    var text = value
    if (engine == TemplateEngine.RAZOR) {
        if (razorConstructs(value).isNotEmpty()) return null
        text = text.replace("@@", " @")
    }
    if (engine?.writesDollarBraces == true) {
        val quotes = QuoteScan(text)
        val chars = text.toCharArray()
        for (m in TEMPLATE_SYNTAX.findAll(text)) {
            if (m.value != "\${" || quotes.at(m.range.first) != '`') continue
            for (k in m.range.first until balanced(text, m.range.first + 1, '{', '}')) chars[k] = '_'
        }
        text = chars.concatToString()
    }
    return text.takeUnless { if (aroundPlaceholders) hasForeignTemplateSyntax(it) else hasTemplateSyntax(it) }
}
