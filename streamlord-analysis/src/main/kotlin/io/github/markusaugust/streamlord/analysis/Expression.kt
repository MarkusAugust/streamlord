package io.github.markusaugust.streamlord.analysis

/*
 * Validation of Datastar expressions. A Datastar expression is JavaScript in which `$name` is a
 * signal (a legal JS identifier already) and `@name(...)` is an action (not legal JS). Replacing
 * every `@` with `_` yields JavaScript of identical length, so the parser's positions map 1:1.
 */

/**
 * `$foo-bar`, `$count-1`, `$total-el.offsetWidth`: Datastar reads a signal with the pattern `\$(\w+(?:[.-]\w+)*)`,
 * so a hyphen followed by a word character is swallowed into the name. Only `$a-$b` is a subtraction.
 */
private val HYPHENATED_SIGNAL = Regex("""\$[A-Za-z_][A-Za-z0-9_.]*(?:-[A-Za-z0-9_][A-Za-z0-9_.]*)+""")
private val HYPHEN_WORD = Regex("""-([A-Za-z0-9_])""")
private val ACTION = Regex("""@([A-Za-z_$][A-Za-z0-9_$]*)(\s*)\(""")
private val SIGNAL_PATH = Regex("""\$\w+(?:\.\w+)+""")
private val NUMERIC_SEGMENT = Regex("""\.\d""")
private val SIGNAL_PREFIX = Regex("""\$([A-Za-z_][A-Za-z0-9_.]*)?$""")
private val ACTION_PREFIX = Regex("""@([A-Za-z_][A-Za-z0-9_]*)?$""")
private val KEBAB_PART = Regex("""-([a-z])""")

/** The quote of the single-, double- or backtick-quoted JavaScript string literal the offset is inside, or null. */
internal fun quoteAt(
    text: String,
    offset: Int,
): Char? {
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
    return quote
}

/**
 * Which offsets of an expression are code: not a string literal, not the text of a template
 * literal, not a comment. Datastar rewrites signals and actions in code only, and inside the
 * `${ }` of a template literal, which is code here too. A regular expression literal is not
 * recognised; a quote inside one opens a string.
 */
internal fun jsCodeMask(text: String): BooleanArray {
    val mask = BooleanArray(text.length) { true }
    // One entry per open `${`, counting the braces opened inside it.
    val braces = ArrayList<Int>()

    fun blank(
        from: Int,
        to: Int,
    ): Int {
        val end = minOf(to, text.length)
        for (k in from until end) mask[k] = false
        return end
    }

    /** From just inside a template literal to just past its closing backtick, or past the `${` that opens code. */
    fun template(from: Int): Int {
        var j = from
        while (j < text.length) {
            when {
                text[j] == '\\' -> {
                    j = blank(j, j + 2)
                }

                text[j] == '`' -> {
                    return blank(j, j + 1)
                }

                text.startsWith("\${", j) -> {
                    braces += 0
                    return j + 2
                }

                else -> {
                    j = blank(j, j + 1)
                }
            }
        }
        return j
    }
    var i = 0
    while (i < text.length) {
        val c = text[i]
        when {
            c == '\'' || c == '"' -> {
                var j = i + 1
                while (j < text.length && text[j] != c) j += if (text[j] == '\\') 2 else 1
                i = blank(i, j + 1)
            }

            c == '`' -> {
                mask[i] = false
                i = template(i + 1)
            }

            c == '/' && text.getOrNull(i + 1) == '/' -> {
                val nl = text.indexOf('\n', i)
                i = blank(i, if (nl < 0) text.length else nl)
            }

            c == '/' && text.getOrNull(i + 1) == '*' -> {
                val close = text.indexOf("*/", i + 2)
                i = blank(i, if (close < 0) text.length else close + 2)
            }

            c == '{' && braces.isNotEmpty() -> {
                braces[braces.size - 1]++
                i++
            }

            c == '}' && braces.isNotEmpty() -> {
                if (braces.last() == 0) {
                    braces.removeAt(braces.size - 1)
                    i = template(i + 1)
                } else {
                    braces[braces.size - 1]--
                    i++
                }
            }

            else -> {
                i++
            }
        }
    }
    return mask
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
        // `$foo.0.name` is the path foo, 0, name: Datastar rewrites it to bracket form before the browser
        // parses it. To a JavaScript parser `.0` is an error, so the digit is read as a letter, at the same width.
        val js = SIGNAL_PATH.replace(text.replace('@', '_')) { path -> NUMERIC_SEGMENT.replace(path.value, "._") }
        val err = JsParser.error(js)?.takeUnless { objectLiteralParses(js) }
        if (err != null) {
            val pos = minOf(err.pos, text.length)
            val end = maxOf(pos + 1, minOf(err.raisedAt, text.length))
            issues += Issue(pos, end, "Datastar expression: ${err.message}", Severity.ERROR, "expression-syntax", Docs.EXPRESSIONS)
        }
        val code = jsCodeMask(text)
        for (k in HYPHENATED_SIGNAL.findAll(text)) {
            val written = k.value
            if (!code[k.range.first]) continue
            val head = written.substringBefore('-')
            val rest = written.substringAfter('-')
            // `-1`, `-2px`: nobody names a signal that; the author subtracts. `-bar`: a kebab-case key, or a subtraction of a variable.
            val subtraction = rest.first().isDigit()
            val camel = written.replace(HYPHEN_WORD) { it.groupValues[1].uppercase() }
            val spaced = written.replace(HYPHEN_WORD) { " - " + it.groupValues[1] }
            val fixes = ArrayList<Fix>()
            if (!subtraction) fixes += Fix("Change to $camel", k.range.first, k.range.last + 1, camel)
            fixes += Fix("Write $spaced", k.range.first, k.range.last + 1, spaced)
            issues +=
                Issue(
                    start = k.range.first,
                    end = k.range.last + 1,
                    message =
                        if (subtraction) {
                            "Datastar reads $written as one signal named ${written.substring(1)}, not as $head minus $rest. " +
                                "For a subtraction write $spaced, with spaces."
                        } else {
                            "Datastar reads $written as one signal named ${written.substring(1)}, which no key declares: " +
                                "a key ${head.substring(
                                    1,
                                )}-${rest.substringBefore('.')} is the signal $camel. For a subtraction write $spaced, with spaces."
                        },
                    severity = Severity.WARNING,
                    code = "signal-kebab",
                    link = Docs.SIGNALS,
                    fixes = fixes,
                )
        }
        for (m in ACTION.findAll(text)) {
            val name = m.groupValues[1]
            val spec = catalog.actionsByName[name]
            val start = m.range.first
            val end = start + 1 + name.length
            if (!code[start]) continue
            if (m.groupValues[2].isNotEmpty()) {
                issues +=
                    Issue(
                        start = start,
                        end = m.range.last,
                        message =
                            "Datastar reads an action only as @$name( with nothing before the parenthesis. " +
                                "With a space the @ reaches the browser, which cannot parse it.",
                        severity = Severity.ERROR,
                        code = "action-space",
                        link = Docs.ACTIONS,
                        fixes = listOf(Fix("Remove the space", end, m.range.last, "")),
                    )
                continue
            }
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
