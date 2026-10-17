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

/** The characters `String.prototype.trim` removes: WhiteSpace and LineTerminator, which `Char.isWhitespace` is not. */
private const val JS_WHITESPACE = "\t\n\u000B\u000C\r \u00A0\u1680\u2028\u2029\u202F\u205F\u3000\uFEFF"

private fun Char.isJsWhitespace(): Boolean = this in JS_WHITESPACE || this in '\u2000'..'\u200A'

private const val JS_SPACE = """[\t\n\u000B\u000C\r \u00A0\u1680\u2000-\u200A\u2028\u2029\u202F\u205F\u3000\uFEFF]"""

/** The one IIFE shape `genRx` steps over whole: `(function() {...})()` or `(() => ...)()`, no arguments, no nesting. */
private val IIFE =
    Regex(
        """\($JS_SPACE*(?:(?:function)$JS_SPACE*\($JS_SPACE*\)|(?:\($JS_SPACE*\))$JS_SPACE*=>)$JS_SPACE*(?:\{[\s\S]*?\}|[^;){]*)$JS_SPACE*\)$JS_SPACE*\($JS_SPACE*\)""",
    )

/**
 * The end of the quoted run `X(?:\\X|[^X])*X` that opens at [from], or null when none closes. The
 * client's regex consumes an escaped delimiter as a pair, and when it runs out of text it gives the
 * last pair back and ends the run on that delimiter; that is reproduced here, so a run that is not
 * closed still ends where the browser's regex ends it.
 */
private fun delimitedEnd(
    text: String,
    from: Int,
    delimiter: Char,
): Int? {
    var j = from + 1
    var lastPair = -1
    while (j < text.length) {
        val c = text[j]
        if (c == '\\' && j + 1 < text.length && text[j + 1] == delimiter) {
            j += 2
            lastPair = j
        } else if (c != delimiter) {
            j++
        } else {
            return j + 1
        }
    }
    return if (lastPair >= 0) lastPair else null
}

/**
 * The statements of a value-returning expression, as Datastar's `genRx` matches them (engine.ts:412
 * in 1.0.4): runs of anything but `;`, where a quoted string, a regex literal and a no-argument
 * IIFE are stepped over whole, `;` and all. Written as a scan rather than the client's one regular
 * expression, which Java's engine recurses on and overflows for a few kilobytes of text; the two
 * agree token for token, since each alternative is tried in the client's order at every offset.
 */
internal fun clientStatements(text: String): List<IntRange> {
    val out = ArrayList<IntRange>()
    var start = -1
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (c == ';') {
            if (start >= 0) out += start until i
            start = -1
            i++
            continue
        }
        if (start < 0) start = i
        val tokenEnd =
            when (c) {
                '/', '"', '\'', '`' -> delimitedEnd(text, i, c)
                '(' -> IIFE.matchAt(text, i)?.range?.let { it.last + 1 }
                else -> null
            }
        i = tokenEnd ?: (i + 1)
    }
    if (start >= 0) out += start until text.length
    return out
}

/** JavaScript as Datastar compiles an expression, with a map from each of its offsets back to the expression. */
internal class ClientScript(
    val js: String,
    private val origin: IntArray,
) {
    fun toExpression(offset: Int): Int = origin[offset.coerceIn(0, origin.size - 1)]
}

/** A signal reference as the client reads one (engine.ts:448 in 1.0.4): `\w` and `.`/`-` joined parts. */
private val CLIENT_SIGNAL = Regex("""\$(\w+(?:[.-]\w+)*)""")

private fun signalPath(name: String): String = "$" + name.split('.').joinToString("") { "['$it']" }

/** What `.` in the client's pattern does not match. */
private fun Char.isLineTerminator(): Boolean = this == '\n' || this == '\r' || this == '\u2028' || this == '\u2029'

/**
 * The end of a quoted string as the client's pattern matches one, `"[^"\\]*(?:\\.[^"\\]*)*"`:
 * an escape takes the next character unless that is a line terminator, and a string that never
 * closes is no string. Null when the pattern would not match here.
 */
private fun quotedEnd(
    text: String,
    from: Int,
    quote: Char,
): Int? {
    var j = from + 1
    while (j < text.length) {
        val c = text[j]
        when {
            c == quote -> return j + 1
            c == '\\' -> if (j + 1 < text.length && !text[j + 1].isLineTerminator()) j += 2 else return null
            else -> j++
        }
    }
    return null
}

/** The end of a template literal with no `${` in it, as the client's pattern matches one; null otherwise. */
private fun templateEnd(
    text: String,
    from: Int,
): Int? {
    var j = from + 1
    while (j < text.length) {
        val c = text[j]
        when {
            c == '`' -> return j + 1
            c == '\\' -> if (j + 1 < text.length && !text[j + 1].isLineTerminator()) j += 2 else return null
            c == '$' && text.getOrNull(j + 1) == '{' -> return null
            else -> j++
        }
    }
    return null
}

/**
 * The text with every `$name.path` written as the client writes it before compiling (engine.ts:448
 * in 1.0.4), so a signal can be no parameter, declared name or property key, as it cannot be in the
 * browser. The client does it with one pattern: a quoted string or a template literal without `${`
 * is stepped over, a `${...}` with no brace inside has its signals rewritten, and a `$name.path`
 * outside them becomes `$['name']['path']`. Here it is a scan, since Java's engine recurses on
 * that pattern's loops and overflows on a long string of escapes; the alternatives are tried in the
 * client's order at every offset. Every character of a rewritten reference maps back to where the
 * reference began.
 */
internal fun clientSignals(text: String): ClientScript {
    val js = StringBuilder()
    val origin = ArrayList<Int>(text.length + 16)

    fun copy(
        from: Int,
        to: Int,
    ) {
        for (k in from until to) {
            origin += k
            js.append(text[k])
        }
    }

    /** A signal reference starting at [from], rewritten; the offset just past it, or null when none starts here. */
    fun signal(from: Int): Int? {
        val m = CLIENT_SIGNAL.matchAt(text, from) ?: return null
        val written = signalPath(m.groupValues[1])
        repeat(written.length) { origin += from }
        js.append(written)
        return m.range.last + 1
    }
    var i = 0
    while (i < text.length) {
        val c = text[i]
        val end =
            when {
                c == '"' || c == '\'' -> {
                    quotedEnd(text, i, c)?.also { copy(i, it) }
                }

                c == '`' -> {
                    templateEnd(text, i)?.also { copy(i, it) }
                }

                c == '$' && text.getOrNull(i + 1) == '{' -> {
                    val close = text.indexOfAny(charArrayOf('{', '}'), i + 2)
                    if (close < 0 || text[close] != '}') {
                        null
                    } else {
                        copy(i, i + 2)
                        var k = i + 2
                        while (k < close) {
                            k = signal(k) ?: run {
                                copy(k, k + 1)
                                k + 1
                            }
                        }
                        copy(close, close + 1)
                        close + 1
                    }
                }

                c == '$' -> {
                    signal(i)
                }

                else -> {
                    null
                }
            }
        i = end ?: run {
            copy(i, i + 1)
            i + 1
        }
    }
    origin += text.length
    return ClientScript(js.toString(), origin.toIntArray())
}

/**
 * The function body Datastar compiles from [text] (engine.ts:385-430 in 1.0.4). The text is
 * trimmed as JavaScript trims. With [returnsValue] it is split into statements, the last of them
 * wrapped in `return (...);` unless it starts with `return`, and the statements joined with `;\n`;
 * the separators and the wrapping map to where the statement started or ended.
 */
internal fun clientScript(
    text: String,
    returnsValue: Boolean,
): ClientScript {
    val js = StringBuilder()
    val origin = ArrayList<Int>(text.length + 16)

    fun append(
        piece: String,
        from: Int,
    ) {
        for (k in piece.indices) origin += from + k
        js.append(piece)
    }

    fun synthetic(
        piece: String,
        at: Int,
    ) {
        repeat(piece.length) { origin += at }
        js.append(piece)
    }

    fun jsTrim(s: String): String = s.trim { it.isJsWhitespace() }
    val start = text.indexOfFirst { !it.isJsWhitespace() }
    if (start < 0) return ClientScript("", intArrayOf(text.length))
    val end = text.indexOfLast { !it.isJsWhitespace() } + 1
    val trimmed = text.substring(start, end)
    if (!returnsValue) {
        append(trimmed, start)
    } else {
        val statements = clientStatements(trimmed)
        statements.forEachIndexed { i, r ->
            val statement = trimmed.substring(r)
            if (i > 0) synthetic(";\n", start + r.first - 1)
            val last = jsTrim(statement)
            if (i == statements.lastIndex && !last.startsWith("return")) {
                val lastStart = start + r.first + statement.indexOf(last)
                synthetic("return (", lastStart)
                append(last, lastStart)
                synthetic(");", lastStart + last.length)
            } else {
                append(statement, start + r.first)
            }
        }
    }
    origin += end
    return ClientScript(js.toString(), origin.toIntArray())
}

/** The checks of one Datastar expression: syntax, kebab-case signals, unknown and Pro actions. */
public class ExpressionValidator(
    private val catalog: Catalog = Catalog.default,
) {
    /**
     * The issues of [text]. With [returnsValue] known, the syntax is judged on the script Datastar
     * compiles, see [clientScript]; without it, on the text as a script, with an object literal
     * allowed to open it. A `$my-value` named in [hyphenatedSignals] is a declared signal, not a
     * kebab-case slip.
     */
    public fun validate(
        text: String,
        returnsValue: Boolean? = null,
        hyphenatedSignals: Set<String> = emptySet(),
    ): List<Issue> {
        val issues = ArrayList<Issue>()
        if (text.isBlank()) {
            return listOf(Issue(0, maxOf(text.length, 1), "Empty Datastar expression.", Severity.WARNING, "empty-expression"))
        }
        // An action is legal JavaScript once its @ is a letter; a signal is written as the client writes it.
        val signals = clientSignals(text.replace('@', '_'))
        val script = returnsValue?.let { clientScript(signals.js, it) }
        val js = script?.js ?: signals.js
        val err = if (script != null) JsParser.error(js) else JsParser.error(js)?.takeUnless { objectLiteralParses(js) }
        if (err != null) {
            fun toText(offset: Int): Int = signals.toExpression(script?.toExpression(offset) ?: offset)
            val pos = minOf(toText(err.pos), text.length)
            val end = maxOf(pos + 1, minOf(toText(err.raisedAt), text.length))
            issues += Issue(pos, end, "Datastar expression: ${err.message}", Severity.ERROR, "expression-syntax", Docs.EXPRESSIONS)
        }
        val code = jsCodeMask(text)
        for (k in HYPHENATED_SIGNAL.findAll(text)) {
            val written = k.value
            if (!code[k.range.first]) continue
            // data-signals:my-value__case.kebab declares $my-value as written, and the expression reaches it.
            val named = written.substring(1)
            if (hyphenatedSignals.any { named == it || named.startsWith("$it.") }) continue
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
 * With no attribute to say whether the value is read, an expression that opens with `{` may be an
 * object literal Datastar wraps in `return (...)`; to a script parser it is a block. When the
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
