package io.github.markusaugust.streamlord.analysis

import io.github.markusaugust.streamlord.core.client.CLIENT_SIGNAL
import io.github.markusaugust.streamlord.core.client.ClientCasing
import io.github.markusaugust.streamlord.core.client.ClientScript
import io.github.markusaugust.streamlord.core.client.Rewrite
import io.github.markusaugust.streamlord.core.client.asciiLowercase
import io.github.markusaugust.streamlord.core.client.clientRewrite

/*
 * Validation of Datastar expressions. A Datastar expression is JavaScript in which `$name` is a
 * signal and `@name(` is an action. The client rewrites both with one pattern before it compiles
 * the text (see [clientRewrite]), so the syntax is judged on the rewritten text, and what the
 * pattern does differently from JavaScript, it is told about.
 */

private val SPACED_ACTION = Regex("""@([A-Za-z_$][A-Za-z0-9_$]*)(\s+)\(""")
private val SIGNAL_PREFIX = Regex("""\$([A-Za-z_][A-Za-z0-9_.]*)?$""")
private val ACTION_PREFIX = Regex("""@([A-Za-z_][A-Za-z0-9_]*)?$""")
private val WORD_CHAR = Regex("""\w""")

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

/** What a character of an expression is to JavaScript; see [jsRegions]. */
internal object Region {
    const val CODE: Byte = 0
    const val STRING: Byte = 1
    const val TEMPLATE: Byte = 2
    const val REGEX: Byte = 3
    const val COMMENT: Byte = 4
}

/** A character JavaScript continues a name with: ASCII, `_`, `$`, or a letter or decimal digit beyond ASCII. */
internal fun Char?.isJsNamePart(): Boolean =
    this != null &&
        (
            this in 'A'..'Z' || this in 'a'..'z' || this in '0'..'9' || this == '_' || this == '$' ||
                (this >= '\u0080' && (isLetter() || category == CharCategory.DECIMAL_DIGIT_NUMBER))
        )

/** The words after which a `/` opens a regular expression rather than dividing. */
private val REGEX_AFTER_WORD =
    setOf("return", "typeof", "instanceof", "in", "of", "new", "delete", "void", "throw", "case", "do", "else", "yield", "await")

/** The words whose parenthesised head may be followed by a statement that opens with a regular expression. */
private val HEAD_WORDS = setOf("if", "while", "for", "with")

/**
 * Where a regular expression literal ends when its scan reaches each offset, outside a class and
 * inside one (`2 * offset` and `2 * offset + 1`), or -1 when it does not close on its line: past
 * the closing `/` and its flags. Filled from the end in one pass, so that a text full of `/` that
 * never close is read in linear time rather than scanned to the end of the line once for each.
 */
private fun regexEnds(text: String): IntArray {
    val n = text.length
    val out = IntArray(2 * (n + 2)) { -1 }
    for (j in n - 1 downTo 0) {
        val c = text[j]
        for (inClass in 0..1) {
            out[2 * j + inClass] =
                when {
                    c == '\n' -> {
                        -1
                    }

                    c == '\\' -> {
                        out[2 * (j + 2) + inClass]
                    }

                    c == '[' -> {
                        out[2 * (j + 1) + 1]
                    }

                    c == ']' -> {
                        out[2 * (j + 1)]
                    }

                    c == '/' && inClass == 0 -> {
                        var end = j + 1
                        while (end < n && text[end].isJsNamePart()) end++
                        end
                    }

                    else -> {
                        out[2 * (j + 1) + inClass]
                    }
                }
        }
    }
    return out
}

// A name standing on its own: no `.` or `@` before it (a property, an action), and no name character
// on either side, any character beyond ASCII counted as one, as the editor's twin counts it.
private val NAME_EDGE = "A-Za-z0-9_$\\x{80}-\\x{10FFFF}"

private val BINDS_NAMES = Regex("""=>|\bfunction\b|\b(?:let|const|var)\b""")

/**
 * For each name a free attribute's expression is handed and the others are not, the attributes
 * that hand it over: `patch` in `data-on-signal-patch`. [pattern] finds the names, null for none.
 */
private class HandedIn(
    catalog: Catalog,
) {
    val attributes: Map<String, List<String>> =
        catalog.attributes
            .filter { !it.pro }
            .flatMap { a -> a.argNames.filter { it !in catalog.compiledParameters }.map { it to a.name } }
            .groupBy({ it.first }, { it.second })

    val pattern: Regex? =
        attributes.keys.takeIf { it.isNotEmpty() }?.let { names ->
            Regex("(?<![$NAME_EDGE.@])(" + names.joinToString("|") { Regex.escape(it) } + ")(?![$NAME_EDGE])")
        }
}

/**
 * `patch` in the expression of an attribute [spec] whose plugin does not hand it over: Datastar
 * compiles each expression with the names its plugin declares, so `patch` in `data-text` throws a
 * ReferenceError. [handed] names the attributes that hand each such name over. An expression
 * that declares a function or a variable may bind the name itself and is left alone, and so is a
 * Pro attribute, whose plugin is not read here.
 */
private fun scopeNameIssues(
    text: String,
    spec: AttributeSpec,
    handed: HandedIn,
): List<Issue> {
    val names = handed.pattern ?: return emptyList()
    if (spec.pro || spec.valueKind != ValueKind.EXPRESSION || BINDS_NAMES.containsMatchIn(text)) return emptyList()
    val regions = jsRegions(text)
    return names
        .findAll(text)
        .filter { m ->
            val name = m.value
            var b = m.range.first - 1
            while (b >= 0 && isJsSpace(text[b])) b--
            var a = m.range.last + 1
            while (a < text.length && isJsSpace(text[a])) a++
            val before = text.getOrNull(b)
            val after = text.getOrNull(a)
            // A key of an object literal, `{patch: 1}`, names nothing.
            name !in spec.argNames && regions[m.range.first] == Region.CODE && !(after == ':' && (before == '{' || before == ','))
        }.map { m ->
            val where = handed.attributes[m.value].orEmpty().joinToString(" and ") { "data-$it" }
            Issue(
                start = m.range.first,
                end = m.range.last + 1,
                message =
                    "${m.value} is not defined in data-${spec.name}: Datastar hands an expression ${m.value} only in $where, " +
                        "so here it throws a ReferenceError.",
                severity = Severity.WARNING,
                code = "scope-name",
                link = Docs.EXPRESSIONS,
            )
        }.toList()
}

/**
 * What each character of an expression is to JavaScript: code, a string, the text of a template
 * literal, a regular expression or a comment. The code of a `${ }` is code. Whether a `/` divides
 * or opens a regular expression is told by what precedes it, as a tokenizer tells it. Shared with
 * the VS Code extension (`jsRegions` in expression.ts), which this follows step by step.
 */
internal fun jsRegions(text: String): ByteArray {
    val kind = ByteArray(text.length)
    // One entry per open `${`, counting the braces opened inside it.
    val braces = ArrayList<Int>()
    // One entry per open parenthesis: whether it is the head of an `if`, `while`, `for` or `with`.
    val parens = ArrayList<Boolean>()
    var regexAllowed = true
    var lastWord = ""
    var ends: IntArray? = null

    /** The end of the regular expression literal that opens at [from], or null when none closes on its line. */
    fun regexEnd(from: Int): Int? {
        val all = ends ?: regexEnds(text).also { ends = it }
        return all[2 * (from + 1)].takeIf { it >= 0 }
    }

    fun mark(
        from: Int,
        to: Int,
        k: Byte,
    ): Int {
        val end = minOf(to, text.length)
        for (x in from until end) kind[x] = k
        return end
    }

    /** From just inside a template literal to just past its closing backtick, or past the `${` that opens code. */
    fun template(from: Int): Int {
        var j = from
        while (j < text.length) {
            when {
                text[j] == '\\' -> {
                    j = mark(j, j + 2, Region.TEMPLATE)
                }

                text[j] == '`' -> {
                    regexAllowed = false
                    return mark(j, j + 1, Region.TEMPLATE)
                }

                text.startsWith("\${", j) -> {
                    braces += 0
                    regexAllowed = true
                    return mark(j, j + 2, Region.TEMPLATE)
                }

                else -> {
                    j = mark(j, j + 1, Region.TEMPLATE)
                }
            }
        }
        return j
    }
    var i = 0
    while (i < text.length) {
        val c = text[i]
        val word = lastWord
        lastWord = ""
        when {
            c == '\'' || c == '"' -> {
                var j = i + 1
                while (j < text.length && text[j] != c) j += if (text[j] == '\\') 2 else 1
                i = mark(i, j + 1, Region.STRING)
                regexAllowed = false
            }

            c == '`' -> {
                kind[i] = Region.TEMPLATE
                i = template(i + 1)
            }

            c == '/' && text.getOrNull(i + 1) == '/' -> {
                val nl = text.indexOf('\n', i)
                i = mark(i, if (nl < 0) text.length else nl, Region.COMMENT)
                lastWord = word
            }

            c == '/' && text.getOrNull(i + 1) == '*' -> {
                val close = text.indexOf("*/", i + 2)
                i = mark(i, if (close < 0) text.length else close + 2, Region.COMMENT)
                lastWord = word
            }

            c == '/' && regexAllowed && regexEnd(i) != null -> {
                i = mark(i, regexEnd(i)!!, Region.REGEX)
                regexAllowed = false
            }

            c == '{' -> {
                if (braces.isNotEmpty()) braces[braces.size - 1]++
                i++
                regexAllowed = true
            }

            c == '}' -> {
                if (braces.isNotEmpty() && braces.last() == 0) {
                    braces.removeAt(braces.size - 1)
                    kind[i] = Region.TEMPLATE
                    i = template(i + 1)
                } else {
                    if (braces.isNotEmpty()) braces[braces.size - 1]--
                    i++
                    regexAllowed = false
                }
            }

            c == '(' -> {
                parens += word in HEAD_WORDS
                i++
                regexAllowed = true
            }

            c == ')' -> {
                regexAllowed = parens.removeLastOrNull() ?: false
                i++
            }

            c == ']' -> {
                i++
                regexAllowed = false
            }

            (c == '+' || c == '-') && text.getOrNull(i + 1) == c -> {
                // A postfix `++` leaves a value, a prefix one expects one: what came before still decides.
                i += 2
            }

            c in '0'..'9' -> {
                var j = i + 1
                while (j < text.length &&
                    (text[j].let { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '_' || it == '.' })
                ) {
                    j++
                }
                i = j
                regexAllowed = false
            }

            c.isJsNamePart() -> {
                var j = i + 1
                while (j < text.length && text[j].isJsNamePart()) j++
                lastWord = text.substring(i, j)
                regexAllowed = lastWord in REGEX_AFTER_WORD
                i = j
            }

            c.isJsWhitespace() -> {
                lastWord = word
                i++
            }

            else -> {
                i++
                regexAllowed = true
            }
        }
    }
    return kind
}

/** The characters `String.prototype.trim` removes: WhiteSpace and LineTerminator, which `Char.isWhitespace` is not. */
private const val JS_WHITESPACE = "\t\n\u000B\u000C\r \u00A0\u1680\u2028\u2029\u202F\u205F\u3000\uFEFF"

internal fun Char.isJsWhitespace(): Boolean = this in JS_WHITESPACE || this in '\u2000'..'\u200A'

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
 * The statements of a value-returning expression, as `statementRe` of Datastar's `genRx` matches
 * them (engine.ts:415-416 in 1.0.4): runs of anything but `;`, where a quoted string, a regex
 * literal and a no-argument IIFE are stepped over whole, `;` and all. Written as a scan rather than
 * the client's one regular expression, which Java's engine recurses on and overflows for a few
 * kilobytes of text; the two agree token for token, since each alternative is tried in the client's
 * order at every offset, and `ClientRewriteTest` holds the scan to the client's expression as written.
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

/**
 * The signals the client reads in [text]: the references it rewrites where JavaScript runs them,
 * not in a string, a template's text, a regular expression or a comment, and not the tail of a
 * longer name such as `foo$bar`.
 */
internal fun clientSignalReads(text: String): List<Rewrite> {
    val regions = jsRegions(text)
    return clientRewrite(text).rewrites.filter {
        it.signal && regions[it.start] == Region.CODE && !text.getOrNull(it.start - 1).isJsNamePart()
    }
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

/** The offsets of the `;` at which the client splits a value-returning [text] into statements. */
private fun statementSplits(text: String): Set<Int> {
    val start = text.indexOfFirst { !it.isJsWhitespace() }
    if (start < 0) return emptySet()
    val trimmed = text.substring(start, text.indexOfLast { !it.isJsWhitespace() } + 1)
    val covered = BooleanArray(trimmed.length)
    for (r in clientStatements(trimmed)) for (k in r) covered[k] = true
    return trimmed.indices
        .filter { !covered[it] }
        .map { start + it }
        .toSet()
}

/**
 * What to write for a `$name` in the text of a template literal that the client rewrites: `${$name}`
 * for the signal's value, `${'$'}name` to keep the text. A name that opens with a digit, `$5`, is
 * text: nobody names a signal that.
 */
private fun templateAdvice(written: String): String {
    val text = "\${'$'}" + written.drop(1)
    val keep = "write $text or put the text in a quoted string, which Datastar leaves as written."
    val digit = written.getOrNull(1)?.isDigit() == true
    return if (digit) " To keep the text, $keep" else " Write \${$written} for the signal's value. To keep the text, $keep"
}

/**
 * For each offset, where the run of string characters it is in starts: the quote that opens the
 * string. One pass, so that a string full of rewrites is not walked back once for each.
 */
private fun stringStarts(regions: ByteArray): IntArray {
    val out = IntArray(regions.size)
    for (k in regions.indices) out[k] = if (k > 0 && regions[k - 1] == Region.STRING) out[k - 1] else k
    return out
}

/** Where a character the client paired as a quote sits, for the message that names it. */
private fun placeOf(region: Byte): String =
    when (region) {
        Region.COMMENT -> " in a comment"
        Region.REGEX -> " in a regular expression"
        Region.TEMPLATE -> " in a template literal"
        Region.STRING -> " in a string"
        else -> ""
    }

/** `include: '` or `"exclude": "`, a pattern given as a string that opens with a slash. */
private val STRING_PATTERN_HEAD = Regex("""(["']?)(include|exclude)\1\s*:\s*(["'`])/""")

/**
 * The flags a filter's pattern may end with. `d` is left out: it changes nothing a filter does, so
 * nobody writes it there, and the tail of a path such as `'/users/id'` is then not read as flags.
 */
private val REGEX_FLAGS = Regex("""[gimsuyv]+""")

/** A pattern of a filter written as a string with flags after its last slash; see [stringPatterns]. */
internal data class StringPattern(
    val key: String,
    val start: Int,
    val end: Int,
    val pattern: String,
    val flags: String,
)

/** Whether the key that opens at [at] is a key of the code: bare in code, or quoted with the quote opening a string there. */
private fun keyOpens(
    text: String,
    regions: ByteArray,
    at: Int,
    quoted: Boolean,
): Boolean {
    val opens =
        if (quoted) {
            regions[at] == Region.STRING && regions.getOrNull(at - 1) != Region.STRING
        } else {
            regions[at] == Region.CODE
        }
    return opens && !text.getOrNull(at - 1).isJsNamePart()
}

/**
 * The patterns of a filter written as a string with a slash before them and flags after the last
 * one: `include: '/^foo/i'`. The client's `toRegExp` (signals.ts:752 in 1.0.4) cuts one `/` from
 * each end and nothing else, so `'/^foo/'` works and `'/^foo/i'` looks for `/i`. Only the keys of
 * the object between [from] and [to] count: not `xinclude`, nor a key in a string or a comment. A
 * template literal counts when it holds no `${`. A scan rather than one regular expression, which
 * backtracks without end on a run of escapes. Shared with the VS Code extension (`stringPatterns`
 * in expression.ts).
 */
internal fun stringPatterns(
    text: String,
    regions: ByteArray = jsRegions(text),
    from: Int = 0,
    to: Int = text.length,
): List<StringPattern> {
    val out = ArrayList<StringPattern>()
    var m = STRING_PATTERN_HEAD.find(text, from)
    while (m != null && m.range.first < to) {
        val match = m
        m = m.next()
        if (!keyOpens(text, regions, match.range.first, match.groupValues[1].isNotEmpty())) continue
        val quote = match.groupValues[3][0]
        val start = match.range.last - 1
        var j = start + 1
        while (j < to && text[j] != quote && !(quote == '`' && text.startsWith("\${", j))) j += if (text[j] == '\\') 2 else 1
        if (j >= to || text[j] != quote) continue
        val content = text.substring(start + 1, j)
        // An escaped slash is a slash all the same once the string is read: '\/' is '/'.
        val slash = content.lastIndexOf('/')
        val flags = content.substring(slash + 1)
        if (slash <= 0 || !REGEX_FLAGS.matches(flags)) continue
        out += StringPattern(match.groupValues[2], start, j + 1, content.substring(1, slash), flags)
    }
    return out
}

/** Flags a regular expression literal takes: each once, and `u` and `v` not together. */
private fun validFlags(flags: String): Boolean = flags.toSet().size == flags.length && !('u' in flags && 'v' in flags)

/** [pattern] as the body of a literal: a `/` escaped, as `SignalFilter.regexLiteral` in streamlord-html writes it. */
private fun literalBody(pattern: String): String {
    if (pattern.isEmpty()) return "(?:)"
    return buildString {
        var i = 0
        while (i < pattern.length) {
            if (pattern[i] == '\\') {
                append(pattern, i, minOf(i + 2, pattern.length))
                i += 2
                continue
            }
            append(if (pattern[i] == '/') "\\/" else pattern[i].toString())
            i++
        }
    }
}

/** A character that would not stand in a literal as it stands in the string: an escape, a quote, a reference. */
private val UNFIXABLE_IN_PATTERN = Regex("""[\\'"`&]""")

/**
 * The `filter-pattern` warnings for the string patterns of the object between [from] and [to] in
 * [text]: a filter's value, [json] when it is JSON, or the `filterSignals` of an action. Shared
 * with the VS Code extension (`filterPatternIssues` in expression.ts).
 */
internal fun filterPatternIssues(
    text: String,
    regions: ByteArray,
    from: Int,
    to: Int,
    json: Boolean,
    link: String,
): List<Issue> =
    stringPatterns(text, regions, from, to).map { p ->
        val literal = "/${literalBody(p.pattern)}/${p.flags}"
        val valid = validFlags(p.flags)
        val advice =
            when {
                !valid -> {
                    "As flags ${p.flags} would not do either: each is given once, and u and v not together. " +
                        "Write a regular expression literal${if (json) ", in JavaScript rather than JSON," else ""} with the flags meant."
                }

                json -> {
                    "JSON holds no regular expression: write the value as JavaScript, with the literal $literal."
                }

                else -> {
                    "Write the regular expression literal $literal."
                }
            }
        // The string's escapes would mean something else in a literal, and a quote or a reference in an attribute would need its own,
        // so the fix is offered only without them.
        val fixable = valid && !json && !UNFIXABLE_IN_PATTERN.containsMatchIn(p.pattern)
        Issue(
            p.start,
            p.end,
            "Datastar cuts one / from each end of a string ${p.key} and takes the rest as the pattern, " +
                "so /${p.flags} stays in it and is matched as text, not read as flags. $advice",
            Severity.WARNING,
            "filter-pattern",
            link,
            if (fixable) listOf(Fix("Change to $literal", p.start, p.end, literal)) else emptyList(),
        )
    }

/** `filterSignals: {` in the options of an action, which the client reads with the same `toRegExp`. */
private val FILTER_SIGNALS = Regex("""(["']?)filterSignals\1\s*:\s*\{""")

/** The `filter-pattern` warnings for the `filterSignals` of the actions in [text]. */
private fun filterSignalsIssues(text: String): List<Issue> {
    if ("filterSignals" !in text) return emptyList()
    val regions = jsRegions(text)
    val out = ArrayList<Issue>()
    for (m in FILTER_SIGNALS.findAll(text)) {
        if (!keyOpens(text, regions, m.range.first, m.groupValues[1].isNotEmpty())) continue
        val open = m.range.last
        var depth = 0
        var close = text.length
        for (i in open until text.length) {
            if (regions[i] != Region.CODE) continue
            if (text[i] == '{') {
                depth++
            } else if (text[i] == '}' && --depth == 0) {
                close = i + 1
                break
            }
        }
        out += filterPatternIssues(text, regions, open, close, false, Docs.ACTIONS)
    }
    return out
}

/** The syntax error of [js] read as one expression, the way a filter value is evaluated (`return (value)`), or null. */
internal fun expressionError(js: String): JsSyntaxError? {
    val err = JsParser.error("return ($js\n)") ?: return null

    fun shift(n: Int): Int = (n - 8).coerceIn(0, js.length)
    return JsSyntaxError(err.message ?: "Syntax error", shift(err.pos), shift(err.raisedAt))
}

/** The checks of one Datastar expression: syntax, kebab-case signals, unknown and Pro actions. */
public class ExpressionValidator(
    private val catalog: Catalog = Catalog.default,
) {
    private val handed = HandedIn(catalog)

    /**
     * The issues of [text]. With [returnsValue] known, the syntax is judged on the script Datastar
     * compiles, see [clientScript]; without it, on the text as a script, with an object literal
     * allowed to open it. A `$my-value` named in [hyphenatedSignals] is a declared signal, not a
     * kebab-case slip. With the [attribute] it is the value of, whose [AttributeSpec.returnsValue]
     * stands in for [returnsValue], a name only another attribute's expression is handed is flagged
     * too ([scopeNameIssues]).
     */
    public fun validate(
        text: String,
        returnsValue: Boolean? = null,
        hyphenatedSignals: Set<String> = emptySet(),
        attribute: AttributeSpec? = null,
    ): List<Issue> =
        validateText(text, returnsValue ?: attribute?.returnsValue, hyphenatedSignals) +
            (attribute?.let { scopeNameIssues(text, it, handed) } ?: emptyList())

    private fun validateText(
        text: String,
        returnsValue: Boolean?,
        hyphenatedSignals: Set<String>,
    ): List<Issue> {
        val issues = ArrayList<Issue>()
        if (text.isBlank()) {
            return listOf(Issue(0, maxOf(text.length, 1), "Empty Datastar expression.", Severity.WARNING, "empty-expression"))
        }
        // As the client does it: the statements split and wrapped first, the signals and actions rewritten after.
        val script = returnsValue?.let { clientScript(text, it) } ?: ClientScript(text, IntArray(text.length + 1) { it })
        val js = script.js
        val rewritten = clientRewrite(js)
        val regions = jsRegions(js)

        fun fromScript(offset: Int): Int = script.toExpression(offset)

        fun toText(offset: Int): Int = script.toExpression(rewritten.toExpression(offset))

        fun span(
            start: Int,
            end: Int,
        ): Pair<Int, Int> = fromScript(start) to fromScript(end - 1) + 1

        // The client leaves `@get (` as written, which the browser cannot parse; this says why.
        val spaced = HashSet<Int>()
        for (m in SPACED_ACTION.findAll(js)) {
            if (regions[m.range.first] != Region.CODE) continue
            val name = m.groupValues[1]
            val start = fromScript(m.range.first)
            val end = start + 1 + name.length
            val paren = start + m.value.length - 1
            spaced += start
            issues +=
                Issue(
                    start = start,
                    end = paren,
                    message =
                        "Datastar reads an action only as @$name( with nothing before the parenthesis. " +
                            "With a space the @ reaches the browser, which cannot parse it.",
                    severity = Severity.ERROR,
                    code = "action-space",
                    link = Docs.ACTIONS,
                    fixes = listOf(Fix("Remove the space", end, paren, "")),
                )
        }

        val parseError = JsParser.error(rewritten.js)
        // Without a known attribute an expression may also be an object literal, which a script reads as a block.
        val err = if (returnsValue == null) parseError?.takeUnless { objectLiteralParses(rewritten.js) } else parseError
        if (err != null) {
            val pos = minOf(toText(err.pos), text.length)
            val end = maxOf(pos + 1, minOf(toText(err.raisedAt), text.length))
            val message = "Datastar expression: ${err.message}"
            if (pos !in spaced) issues += Issue(pos, end, message, Severity.ERROR, "expression-syntax", Docs.EXPRESSIONS)
        }

        val starts by lazy { stringStarts(regions) }
        for (r in rewritten.rewrites) {
            val region = regions[r.start]
            if (region == Region.COMMENT) continue
            val written = js.substring(r.start, if (r.signal) r.end else r.end - 1)
            val (start, end) = span(r.start, r.start + written.length)
            if (region != Region.CODE) {
                issues += literalRewritten(r, written, region, js.getOrNull(starts[r.start]), start, end)
                continue
            }
            if (r.signal) {
                if ('-' in r.name) kebabIssue(r, written, start, end, hyphenatedSignals)?.let { issues += it }
                continue
            }
            issues += actionIssues(r.name, start)
        }

        // A `$name` in code the client did not rewrite reaches the browser as a JavaScript variable.
        val rewrittenAt = rewritten.rewrites.mapTo(HashSet()) { it.start }
        for (p in js.indices) {
            if (js[p] != '$' || regions[p] != Region.CODE || p in rewrittenAt || js.getOrNull(p - 1).isJsNamePart()) continue
            val next = js.getOrNull(p + 1) ?: continue
            if (next == '$') continue
            if (WORD_CHAR.matches(next.toString())) {
                val written = CLIENT_SIGNAL.matchAt(js, p)?.value ?: "$"
                val run = rewritten.runs.firstOrNull { p in it }
                val why =
                    run?.let {
                        val q = js[it.first]
                        ": its pattern takes the $q${placeOf(regions[it.first])} before it for the start of a string, " +
                            "and leaves everything up to the next $q as written"
                    } ?: ""
                val (start, end) = span(p, p + written.length)
                issues +=
                    Issue(
                        start,
                        end,
                        "Datastar does not rewrite $written$why. The browser reads $written as a JavaScript variable, not as the signal.",
                        Severity.WARNING,
                        "signal-not-read",
                        Docs.EXPRESSIONS,
                    )
            } else if (next.isJsNamePart()) {
                var q = p + 1
                while (q < js.length && js[q].isJsNamePart()) q++
                val written = js.substring(p, q)
                val (start, end) = span(p, q)
                issues +=
                    Issue(
                        start,
                        end,
                        "Datastar reads only ASCII letters, digits and _ after a \$, so $written is no signal: " +
                            "the browser looks for a JavaScript variable named $written and fails.",
                        Severity.WARNING,
                        "signal-not-read",
                        Docs.SIGNALS,
                    )
            }
        }

        // A `;` inside brackets splits the expression all the same, and what it computed is lost.
        if (returnsValue == true && err == null) issues += statementSplitIssue(text)
        issues += filterSignalsIssues(text)
        return issues
    }

    private fun literalRewritten(
        r: Rewrite,
        written: String,
        region: Byte,
        quote: Char?,
        start: Int,
        end: Int,
    ): Issue {
        val becomes = if (r.signal) r.written else "${r.written}...)"
        val advice =
            when {
                region == Region.TEMPLATE && r.signal -> {
                    templateAdvice(written)
                }

                region == Region.TEMPLATE -> {
                    " Put the text in a quoted string, which Datastar leaves as written."
                }

                region == Region.REGEX -> {
                    " Build the pattern with new RegExp('...'): Datastar leaves a quoted string as written."
                }

                else -> {
                    ""
                }
            }
        // The rewrite writes a quote of its own, which ends a string opened with the same one.
        val inserted = if (r.signal) '\'' else '"'
        val why =
            when {
                region == Region.TEMPLATE -> {
                    ", because the literal also holds a \${...}, so the text holds that"
                }

                region == Region.REGEX -> {
                    ", which changes the pattern"
                }

                quote == inserted -> {
                    ", because its pattern pairs the quotes before it differently. The $inserted it writes ends the string, " +
                        "so the browser cannot parse the expression"
                }

                else -> {
                    ", because its pattern pairs the quotes before it differently, so the string holds that"
                }
            }
        return Issue(
            start,
            end,
            "Datastar rewrites $written${placeOf(region)} into $becomes$why.$advice",
            Severity.WARNING,
            "literal-rewritten",
            Docs.EXPRESSIONS,
        )
    }

    private fun kebabIssue(
        r: Rewrite,
        written: String,
        start: Int,
        end: Int,
        hyphenatedSignals: Set<String>,
    ): Issue? {
        // data-signals:my-value__case.kebab, data-bind="my-value" or {'my-value': 1} declares $my-value as written, and the expression reaches it.
        if (hyphenatedSignals.any { r.name == it || r.name.startsWith("$it.") }) return null
        val head = written.substringBefore('-')
        val rest = written.substringAfter('-')
        // `-1`, `-2px`: nobody names a signal that; the author subtracts. `-bar`: a kebab-case key, or a subtraction of a variable.
        val subtraction = rest.first().isDigit()
        // The browser lowercases the key, dots and all, and the client raises only a lowercase letter
        // after a hyphen (caseFns.camel in text.ts): a key a-b-1 is the signal aB-1, a-b.c-d is aB.cD,
        // and foo-_bar keeps its name.
        val key = written.substring(1).asciiLowercase()
        val signal = ClientCasing.camel(key)
        val camel = "$" + signal
        val spaced = written.replace("-", " - ")
        val fixes = ArrayList<Fix>()
        if (!subtraction && signal != key) fixes += Fix("Change to $camel", start, end, camel)
        fixes += Fix("Write $spaced", start, end, spaced)
        val reads = "Datastar reads $written as one signal named ${r.name}"
        val subtract = "For a subtraction write $spaced, with spaces."
        return Issue(
            start = start,
            end = end,
            message =
                when {
                    subtraction -> {
                        "$reads, not as $head minus $rest. $subtract"
                    }

                    signal == key -> {
                        "$reads, and nothing here declares it: a key $key keeps that name, " +
                            "since Datastar camel-cases only a lowercase letter after a hyphen. $subtract"
                    }

                    else -> {
                        "$reads, and nothing here declares it: a key $key is the signal \$$signal. $subtract"
                    }
                },
            severity = Severity.WARNING,
            code = "signal-kebab",
            link = Docs.SIGNALS,
            fixes = fixes,
        )
    }

    private fun actionIssues(
        name: String,
        start: Int,
    ): List<Issue> {
        val spec = catalog.actionsByName[name]
        val end = start + 1 + name.length
        if (spec == null) {
            val near = catalog.actions.map { it.name }.firstOrNull { it.equals(name, ignoreCase = true) }
            return listOf(
                Issue(
                    start = start,
                    end = end,
                    message = if (near != null) "Unknown action @$name. Did you mean @$near?" else "Unknown action @$name.",
                    severity = Severity.WARNING,
                    code = "unknown-action",
                    link = Docs.ACTIONS,
                    fixes = if (near != null) listOf(Fix("Change to @$near", start, end, "@$near")) else emptyList(),
                ),
            )
        }
        if (spec.pro) {
            return listOf(
                Issue(start, end, "@$name is a Datastar Pro action; it needs the Pro bundle.", Severity.HINT, "pro-action", Docs.ACTIONS),
            )
        }
        return emptyList()
    }

    private fun statementSplitIssue(text: String): List<Issue> {
        val regions = jsRegions(text)
        val splits = statementSplits(text)
        var depth = 0
        for (k in text.indices) {
            if (k in splits && depth > 0) {
                return listOf(
                    Issue(
                        k,
                        k + 1,
                        "Datastar splits an expression whose value is used at every ; outside quotes, this one inside brackets too, " +
                            "and returns only what the last piece gives: the value is lost. " +
                            "A function without parameters called at once, (() => { ... })(), is kept whole.",
                        Severity.WARNING,
                        "statement-split",
                        Docs.EXPRESSIONS,
                    ),
                )
            }
            if (regions[k] != Region.CODE) continue
            when (text[k]) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> if (depth > 0) depth--
            }
        }
        return emptyList()
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
