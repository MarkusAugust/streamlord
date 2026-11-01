package io.github.markusaugust.streamlord.analysis

import io.github.markusaugust.streamlord.core.client.decodeEntities
import io.github.markusaugust.streamlord.core.domain.holeLandings

/*
 * Where a caret stands, for completion, hover and parameter info; shared with completionContext.ts.
 */

/** The attribute value a caret stands in: the attribute's name as written, where its value starts, and the value before the caret, decoded. */
public data class ValueAtCaret(
    val attribute: String,
    val valueStart: Int,
    val before: String,
)

/** The character the caret is given in the markup: one that neither closes a value nor starts a reference. */
private const val CARET = 'x'

/**
 * The quoted attribute value [offset] stands in, in [markup] read as the browser reads it, or null.
 * The reading is core's [holeLandings], so a value over several lines, in single quotes or in a
 * script's text is told as `interpolate` tells it.
 */
public fun valueAtCaret(
    markup: String,
    offset: Int,
    prefix: String,
): ValueAtCaret? {
    if (offset !in 0..markup.length) return null
    // A hole is at least one character wide, so the caret gets one of its own.
    val probe = StringBuilder(markup).insert(offset, CARET).toString()
    val landing = holeLandings(probe, listOf(offset..offset), listOf(prefix)).single()
    val attribute = landing.attribute ?: return null
    if (landing.valueStart !in 0..offset) return null
    return ValueAtCaret(attribute, landing.valueStart, decodeEntities(markup.substring(landing.valueStart, offset)).text)
}

/** The Datastar expression before a caret, as Datastar is handed it, and what belongs there. */
public data class ExpressionAtCaret(
    val before: String,
    /** Whether actions belong there: an expression, not a signal name. */
    val allowActions: Boolean,
    /** Whether `patch` is in scope: data-on-signal-patch only. */
    val patchInScope: Boolean,
)

/**
 * The Datastar expression [offset] stands in, in [src], read as Kotlin when [kotlin] and as markup
 * otherwise: an attribute value by [valueAtCaret], in markup or in an HTML string in Kotlin, or the
 * string handed to a DSL expression call. Kotlin's escapes and character references are decoded.
 */
public fun Analyzer.expressionAtCaret(
    src: String,
    offset: Int,
    kotlin: Boolean,
    prefix: String,
): ExpressionAtCaret? {
    if (offset !in 0..src.length) return null
    if (!kotlin) return expressionInMarkup(src, offset, prefix)
    htmlStringAt(src, offset)?.let { html ->
        if (offset <= html.contentStart || offset > html.contentEnd) return null
        return expressionInMarkup(html.text, decodedIndex(html, offset), prefix)
    }
    val site =
        findCallSites(src, catalog.expressionCallSites.keys, lex(src)).firstOrNull { it.openParen < offset && offset <= it.closeParen }
            ?: return null
    val spec = catalog.expressionCallSites[site.name] ?: return null
    val s =
        site.selectString(spec) ?: site.args.mapNotNull { it.string }.firstOrNull { it.start < offset && offset <= it.end } ?: return null
    if (offset <= s.start || offset > s.contentEnd + (if (s.unterminated) 1 else 0)) return null
    // dataOnSignalPatch also writes the filter, so the call is looked up among the attribute's own names.
    val patch = catalog.attributesByName["on-signal-patch"]?.kotlin?.contains(site.name) == true
    return ExpressionAtCaret(s.text.substring(0, decodedIndex(s, offset)), allowActions = true, patchInScope = patch)
}

/** The expression in the attribute value [caret] stands in, in [markup], or null. */
private fun Analyzer.expressionInMarkup(
    markup: String,
    caret: Int,
    prefix: String,
): ExpressionAtCaret? {
    val at = valueAtCaret(markup, caret, prefix) ?: return null
    val spec = catalog.parseAttributeName(at.attribute.lowercase(), prefix)?.spec ?: return null
    if (spec.valueKind != ValueKind.EXPRESSION && spec.valueKind != ValueKind.SIGNAL) return null
    return ExpressionAtCaret(at.before, spec.valueKind == ValueKind.EXPRESSION, spec.name == "on-signal-patch")
}

/** The decoded index of a source offset in a Kotlin string: the last character that starts at or before it. */
private fun decodedIndex(
    s: KotlinString,
    offset: Int,
): Int {
    var i = 0
    while (i + 1 < s.map.size && s.map[i + 1] <= offset) i++
    return minOf(i, s.text.length)
}

/** The action call a caret stands in: the action's name, which argument, and the key of the options object when the caret is on one. */
public data class ActionCall(
    val action: String,
    val argument: Int,
    val option: OptionKeyAt?,
)

/** A key of a backend action's options object at the caret: what is typed of it, and the keys the object already holds. */
public data class OptionKeyAt(
    val typed: String,
    val written: List<String>,
)

private val ACTION_OPEN = Regex("""@([A-Za-z_$][\w$]*)\s*\(""")
private val WRITTEN_KEY = Regex("""(?:^|,)\s*([A-Za-z_$][\w$]*)\s*:""")
private val TYPED_KEY = Regex("""\s*([A-Za-z_$][\w$]*)?""")
private val BACKEND_ACTIONS = Catalog.default.backendActions.toSet()

/** A bracket open at the caret, with the action whose argument list it is and the key text of an options object. */
private class OpenBracket(
    val close: Char,
    val action: String?,
    val options: Boolean,
) {
    var argument = 0

    /** The code of an options object at its own depth, without strings or nested brackets: where its keys are read. */
    val keys = StringBuilder()
}

/** The innermost `@name(` still open at [offset] in the expression [text], read from the text before the caret, or null. */
public fun actionCallAt(
    text: String,
    offset: Int,
): ActionCall? {
    val source = text.substring(0, offset.coerceIn(0, text.length))
    // A space after the caret is code exactly when the caret stands in code.
    val regions = jsRegions("$source ")
    val stack = ArrayList<OpenBracket>()
    var i = 0
    while (i < source.length) {
        val c = source[i]
        if (regions[i] != Region.CODE) {
            i++
            continue
        }
        val top = stack.lastOrNull()
        val open = if (c == '@') ACTION_OPEN.matchAt(source, i) else null
        when {
            open != null -> {
                stack += OpenBracket(')', open.groupValues[1], options = false)
                i = open.range.last + 1
                continue
            }

            c == '(' || c == '[' || c == '{' -> {
                val options = c == '{' && top?.action in BACKEND_ACTIONS && top?.argument == 1
                stack += OpenBracket(CLOSERS.getValue(c), null, options)
            }

            c == ')' || c == ']' || c == '}' -> {
                if (top?.close == c) stack.removeAt(stack.lastIndex)
            }

            c == ',' && top?.action != null -> {
                top.argument++
            }

            top?.options == true -> {
                top.keys.append(c)
            }
        }
        i++
    }
    val index = stack.indexOfLast { it.action != null }
    if (index < 0) return null
    val call = stack[index]
    val top = stack.last()
    val option =
        if (top.options && regions[source.length] == Region.CODE) {
            val keys = top.keys.toString()
            TYPED_KEY.matchEntire(keys.substringAfterLast(','))?.let { typed ->
                OptionKeyAt(typed.groupValues[1], WRITTEN_KEY.findAll(keys).map { it.groupValues[1] }.toList())
            }
        } else {
            null
        }
    return ActionCall(call.action!!, call.argument, option)
}

private val CLOSERS = mapOf('(' to ')', '[' to ']', '{' to '}')

/**
 * The prose of an expression: the runs of its string and template literals, with their quotes, as
 * offsets into [text]. A spell checker reads these and leaves signals, actions and code alone.
 */
public fun proseRanges(text: String): List<IntRange> {
    val regions = jsRegions(text)
    val out = ArrayList<IntRange>()
    var i = 0
    while (i < text.length) {
        val kind = regions[i]
        if (kind != Region.STRING && kind != Region.TEMPLATE) {
            i++
            continue
        }
        val start = i
        while (i < text.length && regions[i] == kind) i++
        out += start until i
    }
    return out
}
