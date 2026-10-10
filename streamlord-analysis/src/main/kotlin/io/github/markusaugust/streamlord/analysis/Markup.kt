package io.github.markusaugust.streamlord.analysis

/*
 * Markup checks for the HTML Datastar patches: complete elements, ids where the protocol needs
 * them, and well-formed `data-*` attributes. A deliberately small tokenizer, not a browser.
 */

/** An attribute name as a browser parses it, without template syntax or a spread in it. */
private val PLAIN_ATTRIBUTE_NAME = Regex("""[A-Za-z_:][A-Za-z0-9_:.@-]*""")

/** A modifier name with arguments run into it by underscores, and nothing else. */
private val MODIFIER_WORD = Regex("""[a-z][a-z0-9]*(?:_[A-Za-z0-9]+)+""")

private val VOID = setOf("area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param", "source", "track", "wbr")

/** Elements whose content is text, whatever it looks like: a `<div>` in a `<textarea>` or a `<title>` is not a tag. */
private val RAW_TEXT = setOf("script", "style", "textarea", "title")

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
private val IDENT = Regex("""^[A-Za-z_][A-Za-z0-9_-]*$""")
private val SIGNAL_NAME = Regex("""^[A-Za-z_$][A-Za-z0-9_.$-]*$""")
private val HAS_CAPITAL = Regex("""[A-Z]""")
private val LEADING_CAPITAL = Regex("""^[A-Z]""")
private val HAS_LOWERCASE = Regex("""[a-z]""")
private val ENTITY = Regex("""&(?:(amp|lt|gt|quot|apos)|#(\d{1,7})|#[xX]([0-9A-Fa-f]{1,6}));""")
private val NAMED_ENTITIES = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'")
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
 * named references a value needs (amp, lt, gt, quot, apos) and the numeric ones are decoded.
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
                m == null -> null
                m.groupValues[1].isNotEmpty() -> NAMED_ENTITIES[m.groupValues[1]]
                else -> codePoint(m.groupValues[2].toIntOrNull() ?: m.groupValues[3].toIntOrNull(16))
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

private fun codePoint(cp: Int?): String? =
    if (cp == null || cp == 0 || cp > 0x10FFFF || cp in 0xD800..0xDFFF) null else String(Character.toChars(cp))

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
            if (!closing && !selfClosing && name !in VOID) {
                if (name in RAW_TEXT) {
                    val closeIdx = html.indexOfAsciiIgnoreCase("</$name", end)
                    i = if (closeIdx < 0) html.length else closeIdx
                    depth++
                    continue
                }
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
public fun kebab(name: String): String {
    val out = HAS_CAPITAL.replace(name) { "-" + it.value.lowercase() }
    // Only the hyphen a leading capital introduced is dropped; a CSS custom property keeps its `--`.
    return if (LEADING_CAPITAL.containsMatchIn(name)) out.removePrefix("-") else out
}

/**
 * The key to write, and the `__case` to add, so that a key the author typed with capitals comes
 * back as that name: `foo-bar` for a signal (Datastar reads it as camelCase), `widget-loaded__case.camel`
 * for an event or class (kebab by default), `aria-label` where the key is used as it is. An
 * explicit `__case` is kept: the key is still kebab-cased, because the browser lowercases it
 * either way. Shared by the HTML warning and the Kotlin wire hint.
 */
public fun wireKey(
    key: String,
    keyCase: KeyCase?,
    explicitCase: String?,
): String {
    val base = kebab(key)
    if (explicitCase != null) return "${base}__case.$explicitCase"
    val wanted = if (LEADING_CAPITAL.containsMatchIn(key)) "pascal" else "camel"
    if (keyCase == KeyCase.RAW) return base
    val defaultCase = if (keyCase == KeyCase.CAMEL) "camel" else "kebab"
    return if (wanted == defaultCase) base else "${base}__case.$wanted"
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

/** For a raw key the only way to keep a capital is the object form; said once, where it applies. */
public fun rawKeyNote(
    spec: AttributeSpec,
    prefix: String,
    key: String,
): String =
    if (keyKind(spec) == KeyKind.RAW && spec.valueKind == ValueKind.EXPRESSION) {
        " For a name that really has capitals, such as SVG's viewBox, use the object form: $prefix${spec.name}=\"{$key: ...}\"."
    } else {
        ""
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
            if (opts.checkAttributes) issues += validateAttributes(tag, opts.prefix, html.substring(tag.start, tag.end))
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
     * construct the tokenizer stepped over inside the tag is seen too.
     */
    public fun validateAttributes(
        tag: Tag,
        prefix: String,
        tagSource: String?,
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
                if (near != null) {
                    val baseEnd = attr.nameStart + prefix.length + parsed.base.length
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
                }
                continue
            }
            val link = Docs.attribute(spec.name)
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
            if (spec.keyRequired && parsed.key.isNullOrEmpty()) {
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
            if (!spec.keyed && parsed.key != null) {
                issues += Issue(attr.nameStart, nameEnd, "$prefix${spec.name} does not take a key.", Severity.ERROR, "unexpected-key", link)
            }
            if (parsed.key != null) issues += validateKeyCase(attr, parsed, spec, prefix)
            val hasKey = !parsed.key.isNullOrEmpty()
            val hasValue = !attr.value.isNullOrEmpty()
            if (spec.requires == Requires.VALUE && !hasValue) {
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
            for (mod in parsed.modifiers) {
                val mend = attr.nameStart + mod.offset + mod.text.length
                // An empty modifier has no text to underline: the `__` that opens it is marked.
                val mstart = if (mod.text.isEmpty()) mend - 2 else mend - mod.text.length
                val mspec = spec.modifiers.firstOrNull { it.name == mod.name }
                if (mspec == null) {
                    // __debounce_150ms: an argument joined with an underscore, which Datastar reads
                    // as one unknown name. Arguments follow a dot.
                    val dotted = dottedArguments(mod.name, spec)
                    if (dotted != null) {
                        issues +=
                            Issue(
                                start = mstart,
                                end = mend,
                                message =
                                    "Unknown modifier __${mod.name} on $prefix${spec.name}. A modifier's arguments follow a dot: __$dotted.",
                                severity = Severity.ERROR,
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
                                if (near != null) {
                                    "Unknown modifier __${mod.name} on $prefix${spec.name}. Did you mean __$near?"
                                } else {
                                    "Unknown modifier __${mod.name} on $prefix${spec.name}."
                                },
                            severity = Severity.ERROR,
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
                issues += validateModifierArgs(mspec, mod.args, mstart, mend, spec).map { it.copy(link = link) }
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
                for (issue in expressions.validate(decoded.text)) {
                    val (start, end) = source(issue.start, issue.end)
                    issues +=
                        issue.copy(
                            start = start,
                            end = end,
                            fixes = issue.fixes.map { source(it.start, it.end).let { (s, e) -> it.copy(start = s, end = e) } },
                        )
                }
            }
            if (value != null && spec.valueKind == ValueKind.SIGNAL && value.isNotBlank() && !hasTemplateSyntax(value) &&
                !SIGNAL_NAME.matches(value.trim())
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
                lower == "${prefix}indicator" || lower.startsWith("${prefix}indicator:") || lower.startsWith("${prefix}indicator__")
            } ?: return emptyList()
        // Template syntax in a value, or attributes spread into the tag by name, leave what it
        // sends unknown until rendered.
        if ((tagSource != null && hasTemplateSyntax(tagSource)) ||
            tag.attributes.any { (it.value != null && hasTemplateSyntax(it.value)) || !PLAIN_ATTRIBUTE_NAME.matches(it.name) }
        ) {
            return emptyList()
        }
        val sends =
            tag.attributes.any {
                it.name.asciiLowercase().startsWith(prefix) && it.value != null && backendAction.containsMatchIn(decodeEntities(it.value).text)
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
        // With an explicit __case already there, only the key itself changes.
        val fixed = if (existing != null) kebab(key) else wireKey(key, spec.keyCase, null)
        val reaches = keyReading(spec, key.lowercase(), key.lowercase())
        val keep =
            if (existing !=
                null
            ) {
                "__" + existing.name + (if (existing.args.isNotEmpty()) "." + existing.args.joinToString(".") else "")
            } else {
                ""
            }
        val camelNote = if (keyKind(spec) == KeyKind.SIGNAL) ", and Datastar reads a kebab-case signal key as camelCase" else ""
        return listOf(
            Issue(
                start = keyStart,
                end = keyStart + key.length,
                message =
                    "The browser lowercases attribute names, so this reaches Datastar as $reaches, not $key. " +
                        "Write $name:$fixed$keep: keys are kebab-case$camelNote.${rawKeyNote(spec, prefix, key)}",
                severity = Severity.WARNING,
                code = "key-case",
                link = Docs.attribute(spec.name),
                fixes = listOf(Fix("Change to $fixed", keyStart, keyStart + key.length, fixed)),
            ),
        )
    }

    /**
     * `debounce_150ms` read as `debounce.150ms`, when the part before the first underscore is a
     * modifier of [spec] that takes arguments and the rest is plain words; null otherwise.
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
        return head + "." + name.substring(underscore + 1).replace('_', '.')
    }

    private fun validateModifierArgs(
        spec: Modifier,
        args: List<String>,
        start: Int,
        end: Int,
        attribute: AttributeSpec,
    ): List<Issue> {
        val issues = ArrayList<Issue>()

        fun fail(
            message: String,
            fixes: List<Fix> = emptyList(),
        ) {
            issues += Issue(start, end, message, Severity.ERROR, "modifier-args", fixes = fixes)
        }
        when (spec.type) {
            ModifierType.FLAG -> {
                // __prevent.stop: a second modifier joined with a dot, read as an argument.
                val siblings = attribute.modifiers.filter { it.type == ModifierType.FLAG && it.name != spec.name }.map { it.name }
                if (args.isNotEmpty() && args.all { it in siblings }) {
                    val joined = args.joinToString("") { "__$it" }
                    val at = start + spec.name.length
                    fail(
                        "__${spec.name} takes no arguments. Each modifier starts with two underscores: __${spec.name}$joined.",
                        listOf(Fix("Change to __${spec.name}$joined", at, end, joined)),
                    )
                } else if (args.isNotEmpty()) {
                    fail("__${spec.name} takes no arguments.")
                }
            }

            ModifierType.DURATION -> {
                val d = args.firstOrNull()
                if (d == null || !DURATION.matches(d)) {
                    val at = start + spec.name.length
                    fail(
                        "__${spec.name} needs a duration such as __${spec.name}.500ms or __${spec.name}.1s.",
                        if (d == null) listOf(Fix("Add .500ms", at, at, ".500ms")) else emptyList(),
                    )
                }
                for (f in args.drop(1)) {
                    if (f !in spec.flags) {
                        fail(
                            "Unknown flag .$f for __${spec.name}. Allowed: ${spec.flags.joinToString(", ") { ".$it" }.ifEmpty { "none" }}.",
                        )
                    }
                }
            }

            ModifierType.ENUM -> {
                val v = args.firstOrNull()
                if (v == null || v !in spec.values) fail("__${spec.name} needs one of: ${spec.values.joinToString(", ") { ".$it" }}.")
                if (args.size > 1) fail("__${spec.name} takes a single value.")
            }

            ModifierType.INT -> {
                val n = args.firstOrNull()?.toIntOrNull()
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
