package io.github.markusaugust.streamlord.analysis

import io.github.markusaugust.streamlord.core.client.asciiLowercase
import io.github.markusaugust.streamlord.core.client.decodeEntities
import io.github.markusaugust.streamlord.core.client.regex
import io.github.markusaugust.streamlord.core.domain.Position
import io.github.markusaugust.streamlord.core.domain.landingAsParsed

/*
 * Where a caret stands, for completion, hover and parameter info.
 */

/** The attribute value a caret stands in: the attribute's name as written, where its value starts, and the value before the caret, decoded. */
internal data class ValueAtCaret(
    val attribute: String,
    val valueStart: Int,
    val before: String,
)

/** The character the caret is given in the markup: one that neither closes a value nor starts a reference. */
private const val CARET = 'x'

/**
 * The quoted attribute value [offset] stands in, in [markup] read as the browser reads it, or null.
 * The reading is core's [landingAsParsed], the browser's as it parses a patch and the one [tokenize]
 * gives the checks, so a value over several lines, in single quotes or in a script's text is found,
 * and the text of a `<textarea>` or a `<title>` is text, though `interpolate`, which takes the
 * strictest reading, still judges a value there. With [templates], as in a template file, a value in
 * a template comment is not found, as the checks skip it.
 */
internal fun valueAtCaret(
    markup: String,
    offset: Int,
    prefix: String,
    templates: Boolean = true,
): ValueAtCaret? {
    if (offset !in 0..markup.length) return null
    // A hole is at least one character wide, so the caret gets one of its own.
    val probe = StringBuilder(withTemplateTagNames(markup)).insert(offset, CARET).toString()
    val landing = landingAsParsed(probe, offset..offset, listOf(prefix), templates)
    val attribute = landing.attribute ?: return null
    if (landing.valueStart !in 0..offset) return null
    return ValueAtCaret(attribute, landing.valueStart, decodeEntities(markup.substring(landing.valueStart, offset)).text)
}

/** The attribute name a caret stands on in a tag: the tag's name, lowercased, and the name as written up to the caret. */
internal class NameAtCaret(
    val tag: String,
    val typed: String,
)

private val TAG_NAME = regex("""<([A-Za-z][^\s/>]*)""")

/**
 * The attribute name [offset] stands on, in [markup] read as [valueAtCaret] reads it, or null in
 * text, a comment, raw text, a value or a tag's name. The tag may begin lines above, with its
 * attributes on lines of their own; the name is the browser's, so a value after `=` and white
 * space is none.
 */
internal fun nameAtCaret(
    markup: String,
    offset: Int,
    prefix: String,
    templates: Boolean = true,
): NameAtCaret? {
    if (offset !in 0..markup.length) return null
    val probe = StringBuilder(withTemplateTagNames(markup)).insert(offset, CARET).toString()
    val landing = landingAsParsed(probe, offset..offset, listOf(prefix), templates)
    if (landing.position != Position.STRUCTURE || landing.attribute != null || landing.tagStart !in 0..offset) return null
    if (landing.nameStart !in landing.tagStart..offset) return null
    val tag = TAG_NAME.matchAt(markup, landing.tagStart)?.groupValues?.get(1) ?: return null
    return NameAtCaret(tag.asciiLowercase(), markup.substring(landing.nameStart, offset))
}

/** [nameAtCaret] in an HTML string in Kotlin, read decoded, as its values are. */
internal fun nameAtCaret(
    html: KotlinString,
    offset: Int,
    prefix: String,
): NameAtCaret? = nameAtCaret(html.text, decodedIndex(html, offset), prefix, templates = false)

/** The Datastar expression before a caret, as Datastar is handed it, and what belongs there. */
internal data class ExpressionAtCaret(
    val before: String,
    /** Whether actions belong there: an expression, not a signal name. */
    val allowActions: Boolean,
    /** Whether `patch` is in scope: data-on-signal-patch only. */
    val patchInScope: Boolean,
    /** The quote a string written there takes: the one the attribute around it does not use. */
    val quote: Char = '\'',
    /** Whether that quote is written escaped, `\"`, as a Kotlin string that is not raw needs it. */
    val escaped: Boolean = false,
)

/**
 * The Datastar expression [offset] stands in, in [src], read as Kotlin when [kotlin] and as markup
 * otherwise: an attribute value by [valueAtCaret], in markup or in an HTML string in Kotlin, the value of
 * an `attributes["data-..."] =`, or the string handed to a DSL expression call. Kotlin's escapes and character references are decoded.
 */
internal fun Analyzer.expressionAtCaret(
    src: String,
    offset: Int,
    kotlin: Boolean,
    prefix: String,
): ExpressionAtCaret? {
    if (offset !in 0..src.length) return null
    if (!kotlin) return expressionInMarkup(src, offset, prefix)
    // The diagnostics read kotlinx.html's own form before a free HTML string, and so does the caret.
    attributesIndexes(src).firstOrNull { it.nameStart <= offset && offset <= (it.value?.end ?: it.nameStart) }?.let { at ->
        val s = at.value ?: return null
        if (offset <= s.start || offset > s.contentEnd + (if (s.unterminated) 1 else 0)) return null
        val spec = catalog.parseAttributeName(at.name.asciiLowercase(), prefix)?.spec ?: return null
        if (spec.valueKind != ValueKind.EXPRESSION && spec.valueKind != ValueKind.SIGNAL) return null
        val before = s.text.substring(0, decodedIndex(s, offset))
        return ExpressionAtCaret(before, spec.valueKind == ValueKind.EXPRESSION, spec.name == "on-signal-patch")
    }
    htmlStringAt(src, offset)?.let { html ->
        if (offset <= html.contentStart || offset > html.contentEnd) return null
        return expressionInMarkup(html.text, decodedIndex(html, offset), prefix, templates = false, kotlinEscapes = !html.raw)
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
    templates: Boolean = true,
    kotlinEscapes: Boolean = false,
): ExpressionAtCaret? {
    val at = valueAtCaret(markup, caret, prefix, templates) ?: return null
    val spec = catalog.parseAttributeName(at.attribute.lowercase(), prefix)?.spec ?: return null
    if (spec.valueKind != ValueKind.EXPRESSION && spec.valueKind != ValueKind.SIGNAL) return null
    val quote = if (markup.getOrNull(at.valueStart - 1) == '\'') '"' else '\''
    return ExpressionAtCaret(
        at.before,
        spec.valueKind == ValueKind.EXPRESSION,
        spec.name == "on-signal-patch",
        quote,
        escaped = quote == '"' && kotlinEscapes,
    )
}

/** The decoded index of a source offset in a Kotlin string: the last character that starts at or before it. */
internal fun decodedIndex(
    s: KotlinString,
    offset: Int,
): Int {
    var i = 0
    while (i + 1 < s.map.size && s.map[i + 1] <= offset) i++
    return minOf(i, s.text.length)
}

/** The action call a caret stands in: the action's name, which argument, and the key or the value of an object it reads when the caret is on one. */
internal data class ActionCall(
    val action: String,
    val argument: Int,
    val option: OptionKeyAt?,
    val value: OptionValueAt? = null,
)

/** A key of an object an action reads at the caret: what is typed of it, the keys the object already holds, and the keys it takes. */
internal data class OptionKeyAt(
    val typed: String,
    val written: List<String>,
    val fields: List<ValueField> = emptyList(),
)

/** The value of a key of such an object at the caret: the key, what is typed, and whether in a string already opened. */
internal data class OptionValueAt(
    val field: ValueField,
    val typed: String,
    val quoted: Boolean,
)

private val ACTION_OPEN = regex("""@([A-Za-z_$][\w$]*)\s*\(""")
private val WRITTEN_KEY = regex("""(?:^|,)\s*([A-Za-z_$][\w$]*)\s*:""")
private val TYPED_KEY = regex("""\s*([A-Za-z_$][\w$]*)?""")
private val VALUE_KEY = regex("""\s*([A-Za-z_$][\w$]*)\s*:\s*(#*)""")

/** Something the keys of an object hold that is no key: a string, a regular expression, a bracket. */
private const val OTHER = '#'

/** A bracket open at the caret, with the action whose argument list it is and the keys of the object an action reads. */
private class OpenBracket(
    val close: Char,
    val action: String?,
    val fields: List<ValueField>?,
) {
    var argument = 0

    /** The code of such an object at its own depth, everything else written as [OTHER]: where its keys are read. */
    val keys = StringBuilder()

    /** The key whose value the text after the last comma begins, or null. */
    fun valueKey(): ValueField? {
        val m = VALUE_KEY.matchEntire(keys.substring(keys.lastIndexOf(",") + 1)) ?: return null
        return fields?.firstOrNull { it.name == m.groupValues[1] }
    }
}

/** The keys an object opened in [top] takes: the options or a filter as an argument, or a filter as the value of an option. */
private fun fieldsOf(top: OpenBracket?): List<ValueField>? {
    val catalog = Catalog.default
    val kind =
        when {
            top == null -> null
            top.action != null -> catalog.actionKinds[top.action]?.getOrNull(top.argument)
            else -> top.valueKey()?.kind
        }
    return when (kind) {
        ArgumentKind.OPTIONS -> catalog.optionFields
        ArgumentKind.FILTER -> catalog.filterFields
        else -> null
    }
}

/** The innermost `@name(` still open at [offset] in the expression [text], read from the text before the caret, or null. */
internal fun actionCallAt(
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
        val top = stack.lastOrNull()
        if (regions[i] != Region.CODE) {
            if (top?.fields != null) top.keys.append(OTHER)
            i++
            continue
        }
        val open = if (c == '@') ACTION_OPEN.matchAt(source, i) else null
        when {
            open != null -> {
                if (top?.fields != null) top.keys.append(OTHER)
                stack += OpenBracket(')', open.groupValues[1], null)
                i = open.range.last + 1
                continue
            }

            c == '(' || c == '[' || c == '{' -> {
                if (top?.fields != null) top.keys.append(OTHER)
                stack += OpenBracket(CLOSERS.getValue(c), null, if (c == '{') fieldsOf(top) else null)
            }

            c == ')' || c == ']' || c == '}' -> {
                if (top?.close == c) stack.removeAt(stack.lastIndex)
            }

            c == ',' && top?.action != null -> {
                top.argument++
            }

            top?.fields != null -> {
                top.keys.append(c)
            }
        }
        i++
    }
    val index = stack.indexOfLast { it.action != null }
    if (index < 0) return null
    val call = stack[index]
    val top = stack.last()
    val fields = top.fields ?: return ActionCall(call.action!!, call.argument, null)
    val keys = top.keys.toString()
    val atEnd = regions[source.length]
    if (atEnd == Region.CODE) {
        val option =
            TYPED_KEY.matchEntire(keys.substringAfterLast(','))?.let { typed ->
                OptionKeyAt(typed.groupValues[1], WRITTEN_KEY.findAll(keys).map { it.groupValues[1] }.toList(), fields)
            }
        val value = top.valueKey()?.takeIf { keys.endsWith(OTHER).not() }?.let { OptionValueAt(it, "", quoted = false) }
        return ActionCall(call.action!!, call.argument, option, value)
    }
    // In a string opened as the value: what is typed since its quote.
    var quote = source.length
    while (quote > 0 && regions[quote - 1] == atEnd) quote--
    val typed = source.substring(minOf(quote + 1, source.length))
    val other =
        VALUE_KEY
            .matchEntire(keys.substringAfterLast(','))
            ?.groupValues
            ?.get(2)
            ?.length
    val value =
        top.valueKey()?.takeIf {
            (atEnd == Region.STRING || atEnd == Region.TEMPLATE) && other == source.length - quote && '\\' !in typed && "\${" !in typed
        }
    return ActionCall(call.action!!, call.argument, null, value?.let { OptionValueAt(it, typed, quoted = true) })
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
