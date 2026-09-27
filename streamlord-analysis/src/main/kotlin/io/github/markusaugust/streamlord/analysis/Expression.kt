package io.github.markusaugust.streamlord.analysis

/*
 * Validation of Datastar expressions. A Datastar expression is JavaScript in which `$name` is a
 * signal (a legal JS identifier already) and `@name(...)` is an action (not legal JS). Replacing
 * every `@` with `_` yields JavaScript of identical length, so the parser's positions map 1:1.
 */

/** `$foo-bar`: a kebab-case key written where the camelCase signal belongs. */
private val KEBAB_SIGNAL = Regex("""\$[A-Za-z_][A-Za-z0-9_.]*(?:-[a-z][A-Za-z0-9_]*)+""")
private val ACTION = Regex("""@([A-Za-z_][A-Za-z0-9_]*)\s*\(""")
private val SIGNAL_PREFIX = Regex("""\$([A-Za-z_][A-Za-z0-9_.]*)?$""")
private val ACTION_PREFIX = Regex("""@([A-Za-z_][A-Za-z0-9_]*)?$""")
private val KEBAB_PART = Regex("""-([a-z])""")

/** Is the offset inside a single-, double- or backtick-quoted JavaScript string literal? */
internal fun insideQuotes(
    text: String,
    offset: Int,
): Boolean {
    var quote: Char? = null
    var i = 0
    while (i < offset) {
        val c = text[i]
        if (c == '\\') {
            i += 2
            continue
        }
        if (quote != null) {
            if (c == quote) quote = null
        } else if (c == '\'' || c == '"' || c == '`') {
            quote = c
        }
        i++
    }
    return quote != null
}

/** `foo-bar` -> `fooBar`, as Datastar reads a kebab-case key. */
public fun toCamel(name: String): String = if ('-' in name) KEBAB_PART.replace(name) { it.groupValues[1].uppercase() } else name

/** The checks of one Datastar expression: syntax, kebab-case signals, unknown and Pro actions. */
public class ExpressionValidator(
    private val catalog: Catalog = Catalog.default,
) {
    public fun validate(text: String): List<Issue> {
        val issues = ArrayList<Issue>()
        if (text.isBlank()) {
            return listOf(Issue(0, maxOf(text.length, 1), "Empty Datastar expression.", Severity.WARNING, "empty-expression"))
        }
        val js = text.replace('@', '_')
        val err = JsParser.error(js)?.takeUnless { objectLiteralParses(js) }
        if (err != null) {
            val pos = minOf(err.pos, text.length)
            val end = maxOf(pos + 1, minOf(err.raisedAt, text.length))
            issues += Issue(pos, end, "Datastar expression: ${err.message}", Severity.ERROR, "expression-syntax", Docs.EXPRESSIONS)
        }
        for (k in KEBAB_SIGNAL.findAll(text)) {
            val written = k.value
            val after = text.getOrNull(k.range.last + 1)
            // `$total-el.offsetWidth` and `$count-evt.detail.delta` are subtractions of a scope variable;
            // a `$id-preview` inside a quoted string is text.
            if (after == '.' || after == '(' || after == '[') continue
            if (insideQuotes(text, k.range.first)) continue
            val camel = toCamel(written)
            issues +=
                Issue(
                    start = k.range.first,
                    end = k.range.last + 1,
                    message =
                        "$written reads as ${written.substringBefore('-')} minus the rest. " +
                            "Datastar names signals in camelCase: a key foo-bar is the signal \$fooBar.",
                    severity = Severity.WARNING,
                    code = "signal-kebab",
                    link = Docs.SIGNALS,
                    fixes = listOf(Fix("Change to $camel", k.range.first, k.range.last + 1, camel)),
                )
        }
        for (m in ACTION.findAll(text)) {
            val name = m.groupValues[1]
            val spec = catalog.actionsByName[name]
            val start = m.range.first
            val end = start + 1 + name.length
            if (spec == null) {
                val near = catalog.actions.map { it.name }.firstOrNull { it.equals(name, ignoreCase = true) }
                issues +=
                    Issue(
                        start = start,
                        end = end,
                        message = if (near != null) "Unknown action @$name. Did you mean @$near?" else "Unknown action @$name.",
                        severity = Severity.WARNING,
                        code = "unknown-action",
                        link = Docs.ACTIONS,
                        fixes = if (near != null) listOf(Fix("Change to @$near", start, end, "@$near")) else emptyList(),
                    )
            } else if (spec.pro) {
                issues +=
                    Issue(
                        start,
                        end,
                        "@$name is a Datastar Pro action; it needs the Pro bundle.",
                        Severity.HINT,
                        "pro-action",
                        Docs.ACTIONS,
                    )
            }
        }
        return issues
    }
}

/**
 * An expression that opens with `{` is an object literal to Datastar, which wraps the last statement
 * in `return (...)` for the attributes that take a value; to a script parser it is a block. When the
 * script parse fails, the text is tried once more inside parentheses.
 */
private fun objectLiteralParses(js: String): Boolean = js.trimStart().startsWith("{") && JsParser.error("($js)") == null

/** The signal name typed so far just before [offset] (`$cou|` gives `cou`, `$|` gives ""), or null when not in a signal. */
public fun signalPrefixAt(
    text: String,
    offset: Int,
): String? = SIGNAL_PREFIX.find(text.substring(0, offset.coerceIn(0, text.length)))?.let { it.groupValues[1] }

/** The action name typed so far just before [offset], or null when not in an action. */
public fun actionPrefixAt(
    text: String,
    offset: Int,
): String? = ACTION_PREFIX.find(text.substring(0, offset.coerceIn(0, text.length)))?.let { it.groupValues[1] }
