package io.github.markusaugust.streamlord.analysis

import io.github.markusaugust.streamlord.core.catalog.CatalogNames
import io.github.markusaugust.streamlord.core.client.CLIENT_SIGNAL
import io.github.markusaugust.streamlord.core.client.ClientCasing
import io.github.markusaugust.streamlord.core.client.ClientRewrite
import io.github.markusaugust.streamlord.core.client.ClientScript
import io.github.markusaugust.streamlord.core.client.Rewrite
import io.github.markusaugust.streamlord.core.client.Run
import io.github.markusaugust.streamlord.core.client.asciiLowercase
import io.github.markusaugust.streamlord.core.client.clientRewrite
import io.github.markusaugust.streamlord.core.client.regex
import io.github.markusaugust.streamlord.core.domain.ElementsGuard

/*
 * Validation of Datastar expressions. A Datastar expression is JavaScript in which `$name` is a
 * signal and `@name(` is an action. The client rewrites both with one pattern before it compiles
 * the text (see [clientRewrite]), so the syntax is judged on the rewritten text, and what the
 * pattern does differently from JavaScript, it is told about.
 */

private val SPACED_ACTION = regex("""@([A-Za-z_$][A-Za-z0-9_$]*)(\s+)\(""")
private val SIGNAL_PREFIX = regex("""\$([A-Za-z_][A-Za-z0-9_.]*)?$""")
private val ACTION_PREFIX = regex("""@([A-Za-z_][A-Za-z0-9_]*)?$""")
private val WORD_CHAR = regex("""\w""")

/** A `${` with a brace inside before it closes: what keeps the client's pattern from taking a template literal whole. */
private val BRACED_INTERPOLATION = regex("""\$\{[^{}]*\{""")

/** The quote of the single-, double- or backtick-quoted JavaScript string literal the offset is inside, or null. */
internal fun quoteAt(
    text: String,
    offset: Int,
): Char? = QuoteScan(text).at(offset)

/** [quoteAt] for offsets asked in order, each read on from where the one before left off. */
internal class QuoteScan(
    private val text: String,
) {
    private var i = 0
    private var asked = 0
    private var quote: Char? = null

    fun at(offset: Int): Char? {
        if (offset < asked) {
            i = 0
            quote = null
        }
        asked = offset
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

private const val AWAIT = "await"

// A name standing on its own: no `.` or `@` before it (a property, an action), and no name character
// on either side, any character beyond ASCII counted as one.
private val NAME_EDGE = "A-Za-z0-9_$\\x{80}-\\x{10FFFF}"

private val BINDS_NAMES = regex("""=>|\bfunction\b|\b(?:let|const|var)\b""")

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
            regex("(?<![$NAME_EDGE.@])(" + names.joinToString("|") { Regex.escape(it) } + ")(?![$NAME_EDGE])")
        }

    /**
     * The same for a name every expression is compiled with and only some plugins hand a value:
     * `evt`, which genRx passes as undefined (engine.ts:567) unless the plugin hands the event.
     */
    val undefinedElsewhere: Map<String, List<String>> =
        catalog.attributes
            .filter { !it.pro }
            .flatMap { a -> a.argNames.filter { it in catalog.compiledParameters }.map { it to a.name } }
            .groupBy({ it.first }, { it.second })

    /** One of [undefinedElsewhere] read from: a property or an index of it, `evt.type` or `evt['x']`, not `evt?.x`. */
    val readFrom: Regex? =
        undefinedElsewhere.keys.takeIf { it.isNotEmpty() }?.let { names ->
            regex("(?<![$NAME_EDGE.@])(" + names.joinToString("|") { Regex.escape(it) } + ")(?=[ \t]*[.\\[])")
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
    prefix: String,
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
            val where = handed.attributes[m.value].orEmpty().joinToString(" and ") { "$prefix$it" }
            Issue(
                start = m.range.first,
                end = m.range.last + 1,
                message =
                    "${m.value} is not defined in $prefix${spec.name}: Datastar hands an expression ${m.value} only in $where, " +
                        "so here it throws a ReferenceError.",
                severity = Severity.WARNING,
                code = IssueCode.SCOPE_NAME,
                link = Docs.EXPRESSIONS,
            )
        }.toList() + undefinedNameIssues(text, spec, handed, regions, prefix)
}

/** `evt.type` in an attribute whose plugin hands no event: [HandedIn.undefinedElsewhere]. */
private fun undefinedNameIssues(
    text: String,
    spec: AttributeSpec,
    handed: HandedIn,
    regions: ByteArray,
    prefix: String,
): List<Issue> {
    val names = handed.readFrom ?: return emptyList()
    return names
        .findAll(text)
        .filter { m -> m.value !in spec.argNames && regions[m.range.first] == Region.CODE }
        .map { m ->
            val where = handed.undefinedElsewhere[m.value].orEmpty().joinToString(" and ") { "$prefix$it" }
            Issue(
                start = m.range.first,
                end = m.range.last + 1,
                message =
                    "${m.value} is undefined in $prefix${spec.name}: Datastar hands an expression the event only in $where, " +
                        "so reading from it throws a TypeError.",
                severity = Severity.WARNING,
                code = IssueCode.SCOPE_NAME,
                link = Docs.EXPRESSIONS,
            )
        }.toList()
}

/**
 * What each character of an expression is to JavaScript: code, a string, the text of a template
 * literal, a regular expression or a comment. The code of a `${ }` is code. Whether a `/` divides
 * or opens a regular expression is told by what precedes it, as a tokenizer tells it.
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
    regex(
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
 * them (engine.ts in 1.0.5): runs of anything but `;`, where a quoted string, a regex
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
 * Whether [trimmed] opens with `return` and white space, the client's `/^return\s/`: a function
 * body the author wrote, which `genRx` compiles as it stands and does not split into statements.
 */
internal fun opensWithReturn(trimmed: String): Boolean = trimmed.startsWith("return") && trimmed.getOrNull(6)?.isJsWhitespace() == true

/**
 * The function body Datastar compiles from [text] (engine.ts, `genRx`, in 1.0.5). The text is
 * trimmed as JavaScript trims. With [returnsValue], unless it opens with `return`, it is split
 * into statements, the last of them wrapped in `return (...);` unless it starts with `return`, and
 * the statements joined with `;\n`; the separators and the wrapping map to where the statement
 * started or ended. A text with no statement in it, only `;`, is compiled as it stands.
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
    val statements = if (returnsValue && !opensWithReturn(trimmed)) clientStatements(trimmed) else emptyList()
    if (statements.isEmpty()) {
        append(trimmed, start)
    } else {
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

/**
 * Why the client rewrote a reference that JavaScript reads as the text of a template literal. Asked
 * once for each such reference, so each answer is looked up rather than read from the text again.
 */
private class TemplateReasons(
    private val js: String,
    runs: List<Run>,
) {
    // Between two runs of one literal lies a ${...} of a literal the client took whole: the gaps
    // sorted by start, each with the furthest end of any gap that starts no later.
    private val gaps =
        runs
            .zipWithNext()
            .filter { (a, b) -> a.quote == b.quote }
            .map { (a, b) -> a.range.last to b.range.first }
            .sortedBy { it.first }
    private val furthest = gaps.map { it.second }.runningReduce(::maxOf)

    // Where the template literal around each position opens, and whether a ${...} in it holds a brace.
    private val lastTick = IntArray(js.length).also { for (k in js.indices) it[k] = if (js[k] == '`') k else it.getOrElse(k - 1) { -1 } }
    private val braced = HashMap<Int, Boolean>()

    fun why(at: Int): String {
        val before = gaps.binarySearch { if (it.first < at) -1 else 1 }.let { -it - 2 }
        if (before >= 0 && furthest[before] > at) {
            return ", because it stands inside a \${...}, where Datastar rewrites the text of an inner template literal too, " +
                "so the text holds that"
        }
        val open = lastTick.getOrElse(at) { -1 }
        val held =
            open >= 0 &&
                braced.getOrPut(open) {
                    val close = js.indexOf('`', at).let { if (it < 0) js.length else it }
                    BRACED_INTERPOLATION.containsMatchIn(js.substring(open, close))
                }
        return if (held) {
            ", because a \${...} in the literal holds a brace and Datastar then reads the whole literal as code, so the text holds that"
        } else {
            ", because Datastar does not take this template literal whole and reads its text as code, so the text holds that"
        }
    }
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

/**
 * The flags a filter's pattern may end with. `d` is left out: it changes nothing a filter does, so
 * nobody writes it there, and the tail of a path such as `'/users/id'` is then not read as flags.
 */
private val REGEX_FLAGS = regex("""[gimsuyv]+""")

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
private val UNFIXABLE_IN_PATTERN = regex("""[\\'"`&]""")

private val FILTER_KEYS = setOf("include", "exclude")

/**
 * What stands between the quotes of the string from [start] to [end] of [text]; null where the author wrote no such
 * string: an unclosed quote before a `$signal` is no string to the client, which rewrites the signal, and the parser
 * then reads a string out of what the client wrote.
 */
internal fun writtenString(
    text: String,
    start: Int,
    end: Int,
): String? = if (end - start >= 2 && text[start] in "'\"`" && text[end - 1] == text[start]) text.substring(start + 1, end - 1) else null

/**
 * The `filter-pattern` warnings for the patterns among [properties], those of a filter, that are written as a string
 * with a slash before them and flags after the last one: `include: '/^foo/i'`. The client's `toRegExp` (signals.ts:804
 * in 1.0.5) cuts one `/` from each end and nothing else, so `'/^foo/'` works and `'/^foo/i'` looks for `/i`. A template
 * literal counts when it holds no `${`. [at] says where in [text] an offset of a node stands; [json] when the filter
 * is JSON.
 */
internal fun filterPatternIssues(
    text: String,
    properties: List<JsNode?>,
    at: (Int) -> Int,
    json: Boolean,
    link: String,
): List<Issue> =
    properties.filterNotNull().sortedBy { it.start }.mapNotNull { property ->
        val key = property.key?.name?.takeIf { it in FILTER_KEYS && property.plain } ?: return@mapNotNull null
        val value = property.inner!!
        if (value.type != JsKind.STRING && !(value.type == JsKind.TEMPLATE && value.items.isEmpty())) return@mapNotNull null
        val start = at(value.start)
        val end = at(value.end)
        val content = writtenString(text, start, end) ?: return@mapNotNull null
        // An escaped slash is a slash all the same once the string is read: '\/' is '/'.
        val slash = content.lastIndexOf('/')
        val flags = content.substring(slash + 1)
        if (!content.startsWith("/") || slash <= 0 || REGEX_FLAGS.matchEntire(flags) == null) return@mapNotNull null
        val pattern = content.substring(1, slash)
        val literal = "/${literalBody(pattern)}/$flags"
        val valid = validFlags(flags)
        val advice =
            when {
                !valid -> {
                    "As flags $flags would not do either: each is given once, and u and v not together. " +
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
        val fixable = valid && !json && !UNFIXABLE_IN_PATTERN.containsMatchIn(pattern)
        Issue(
            start,
            end,
            "Datastar cuts one / from each end of a string $key and takes the rest as the pattern, " +
                "so /$flags stays in it and is matched as text, not read as flags. $advice",
            Severity.WARNING,
            IssueCode.FILTER_PATTERN,
            link,
            if (fixable) listOf(Fix("Change to $literal", start, end, literal)) else emptyList(),
        )
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
     * too ([scopeNameIssues]). With the [event] a data-on listens for, a type compared with
     * `evt.detail.type` that datastar-fetch never comes with is flagged too ([fetchTypeIssues]).
     * Messages name attributes with the [prefix] the page is written with.
     */
    public fun validate(
        text: String,
        returnsValue: Boolean? = null,
        hyphenatedSignals: Set<String> = emptySet(),
        attribute: AttributeSpec? = null,
        event: String? = null,
        prefix: String = CatalogNames.PREFIX,
    ): List<Issue> =
        validateText(text, returnsValue ?: attribute?.returnsValue, hyphenatedSignals, event) +
            (attribute?.let { scopeNameIssues(text, it, handed, prefix) + computedSideEffects(text, it, prefix) } ?: emptyList()) +
            emptyList()

    /**
     * A signal changed or an action with a side effect called in `data-computed`, which the
     * reference says must do neither: the client runs the expression again each time a signal it
     * reads changes. `@peek` and the Pro formatters only compute a value.
     */
    private fun computedSideEffects(
        text: String,
        spec: AttributeSpec,
        prefix: String,
    ): List<Issue> {
        if (spec.name != "computed") return emptyList()
        val regions = jsRegions(text)
        val rewritten = clientRewrite(text)
        // Which function a change stands in is the tree's to say: a value that does not parse is not judged.
        val inFunctions = rewriteDepths(rewritten) ?: return emptyList()
        // The keyed form stores what the expression gives, a function too, which nothing calls; the
        // object form calls each function it holds, and a function those return is never called.
        val runs = if (text.trimStart().startsWith("{")) 1 else 0
        val out = ArrayList<Issue>()
        for ((n, r) in rewritten.rewrites.withIndex()) {
            if (regions[r.start] != Region.CODE || inFunctions[n] > runs) continue
            val written = if (r.signal) text.substring(r.start, r.end) else "@${r.name}"
            val message =
                when {
                    r.signal && (ASSIGNS_AFTER.matchAt(text, r.end) != null || updatedBefore(text, r.start)) -> {
                        "${prefix}computed must not change signals, and this changes $written"
                    }

                    !r.signal && catalog.actionsByName[r.name]?.let { it.kind == "backend" || it.name in SIDE_EFFECT_ACTIONS } == true -> {
                        "${prefix}computed must not call actions, and this calls $written"
                    }

                    else -> {
                        continue
                    }
                }
            out +=
                Issue(
                    r.start,
                    r.start + written.length,
                    "$message: Datastar runs it again each time a signal it reads changes. Use ${prefix}effect for a side effect.",
                    Severity.WARNING,
                    IssueCode.COMPUTED_VALUE,
                    Docs.attribute("computed"),
                )
        }
        return out
    }

    private fun validateText(
        text: String,
        returnsValue: Boolean?,
        hyphenatedSignals: Set<String>,
        event: String?,
    ): List<Issue> {
        val issues = ArrayList<Issue>()
        if (text.isBlank()) {
            return listOf(Issue(0, maxOf(text.length, 1), "Empty Datastar expression.", Severity.WARNING, IssueCode.EMPTY_EXPRESSION))
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
                    code = IssueCode.ACTION_SPACE,
                    link = Docs.ACTIONS,
                    fixes = listOf(Fix("Remove the space", end, paren, "")),
                )
        }

        // Without a known attribute an expression may also be an object literal, which a script reads as a block.
        val parsed = if (returnsValue == null) scriptOrLiteral(rewritten.js) else JsParser.parse(rewritten.js)
        val err = parsed.error
        if (err != null) {
            val pos = minOf(toText(err.pos), text.length)
            val end = maxOf(pos + 1, minOf(toText(err.raisedAt), text.length))
            // The `);` the client closes a value with stands at one place of the text and is no token of
            // it: met there, the parser is asked what it says of the value without it.
            val closed = js.endsWith(");") && fromScript(js.length - 1) == fromScript(js.length - 2)
            val atClosing = closed && rewritten.toExpression(err.pos) >= js.length - 2
            val said = if (atClosing) JsParser.error(rewritten.js.dropLast(2))?.message ?: err.message else err.message
            // `await @post(...)`: the parser stops after a name await, which the browser refuses as such.
            // After a line break the name ends its statement, and as a property it is no name of its own.
            val awaitEnd = err.after + AWAIT.length
            val awaited =
                awaitEnd <= err.pos && rewritten.js.startsWith(AWAIT, err.after) && !rewritten.js.getOrNull(awaitEnd).isJsNamePart() &&
                    (awaitEnd until err.pos).none { rewritten.js[it] == '\n' || rewritten.js[it] == '\r' } &&
                    rewritten.js
                        .substring(0, err.after)
                        .trimEnd { it.isJsWhitespace() }
                        .lastOrNull() != '.' &&
                    parsed.finished.none { it.type == JsKind.MEMBER && it.end == awaitEnd }
            val awaitAt = if (awaited) toText(err.after) else null
            if (awaitAt != null) {
                val message =
                    "Datastar expression: await is only valid in an async function, and Datastar compiles an expression as a plain one. " +
                        "An action sends its request without it."
                issues += Issue(awaitAt, awaitAt + AWAIT.length, message, Severity.ERROR, IssueCode.EXPRESSION_SYNTAX, Docs.EXPRESSIONS)
            } else if (pos !in spaced) {
                issues += Issue(pos, end, "Datastar expression: $said", Severity.ERROR, IssueCode.EXPRESSION_SYNTAX, Docs.EXPRESSIONS)
            }
        }

        if (err == null) issues += actionArgumentIssues(catalog, js, rewritten, parsed, ::span)

        val starts by lazy { stringStarts(regions) }
        val templates by lazy { TemplateReasons(js, rewritten.runs) }
        for (r in rewritten.rewrites) {
            val region = regions[r.start]
            if (region == Region.COMMENT) continue
            val written = js.substring(r.start, if (r.signal) r.end else r.end - 1)
            val (start, end) = span(r.start, r.start + written.length)
            if (region != Region.CODE) {
                issues +=
                    literalRewritten(
                        r,
                        written,
                        region,
                        js.getOrNull(starts[r.start]),
                        start,
                        end,
                        if (region == Region.TEMPLATE) templates.why(r.start) else "",
                    )
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
            if (next == '$') {
                // $$count: the client rewrites the last $count, and the dollars before it make a name the browser does not know.
                var last = p + 1
                while (js.getOrNull(last + 1) == '$') last++
                if (last !in rewrittenAt) continue
                // A Kotlin template there is the interpolation check's to speak for.
                val signal = CLIENT_SIGNAL.matchAt(js, last)?.value?.takeUnless { PLACEHOLDER in it } ?: continue
                val extra = js.substring(p, last)
                val (start, end) = span(p, last)
                issues +=
                    Issue(
                        start,
                        end,
                        "Datastar rewrites $signal and leaves the $extra before it, so the browser reads $extra$ " +
                            "as a JavaScript variable and throws a ReferenceError.",
                        Severity.WARNING,
                        IssueCode.SIGNAL_NOT_READ,
                        Docs.EXPRESSIONS,
                        fixes = listOf(Fix("Remove the extra $extra", start, end, "")),
                    )
                continue
            }
            if (WORD_CHAR.matchEntire(next.toString()) != null) {
                val written = CLIENT_SIGNAL.matchAt(js, p)?.value ?: "$"
                val run = rewritten.runs.firstOrNull { p in it.range }
                val why =
                    run?.let {
                        // A later stretch of a literal the client took whole: JavaScript is still in the ${...} the client ended.
                        if (it.range.first !=
                            it.quote
                        ) {
                            return@let ": a } before it ends the \${...} for Datastar's pattern, which leaves what follows as written"
                        }
                        val q = js[it.quote]
                        ": its pattern takes the $q${placeOf(regions[it.quote])} before it for the start of a string, " +
                            "and leaves everything up to the next $q as written"
                    } ?: ""
                val (start, end) = span(p, p + written.length)
                issues +=
                    Issue(
                        start,
                        end,
                        "Datastar does not rewrite $written$why. The browser reads $written as a JavaScript variable, not as the signal.",
                        Severity.WARNING,
                        IssueCode.SIGNAL_NOT_READ,
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
                        IssueCode.SIGNAL_NOT_READ,
                        Docs.SIGNALS,
                    )
            }
        }

        // A `;` inside brackets splits the expression all the same, and what it computed is lost.
        if (returnsValue == true && err == null && !opensWithReturn(text.trim { it.isJsWhitespace() })) issues += statementSplitIssue(text)
        // An object under `filterSignals`, which the client reads with the same `toRegExp`: its properties are known for
        // its own once it is closed.
        val filters = parsed.finished.filter { it.key?.name == "filterSignals" && it.inner?.type == JsKind.OBJECT }
        issues += filterPatternIssues(text, filters.flatMap { it.inner!!.items }, ::toText, false, Docs.ACTIONS)
        if (event == DATASTAR_FETCH) issues += fetchTypeIssues(catalog, text, parsed, ::toText)
        return issues
    }

    private fun literalRewritten(
        r: Rewrite,
        written: String,
        region: Byte,
        quote: Char?,
        start: Int,
        end: Int,
        template: String,
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
                    template
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
            IssueCode.LITERAL_REWRITTEN,
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
            code = IssueCode.SIGNAL_KEBAB,
            link = Docs.SIGNALS,
            fixes = fixes,
        )
    }

    private fun actionIssues(
        name: String,
        start: Int,
    ): List<Issue> {
        // A name with a Kotlin template in it is one the code writes.
        if (PLACEHOLDER in name) return emptyList()
        val spec = catalog.actionsByName[name]
        val end = start + 1 + name.length
        if (spec == null) {
            val names = catalog.actions.map { it.name }
            val near =
                names.firstOrNull { it.equals(name, ignoreCase = true) }
                    ?: names.singleOrNull { ElementsGuard.oneEditAway(name, it, swaps = true) }
            return listOf(
                Issue(
                    start = start,
                    end = end,
                    message = if (near != null) "Unknown action @$name. Did you mean @$near?" else "Unknown action @$name.",
                    severity = Severity.WARNING,
                    code = IssueCode.UNKNOWN_ACTION,
                    link = Docs.ACTIONS,
                    fixes = if (near != null) listOf(Fix("Change to @$near", start, end, "@$near")) else emptyList(),
                ),
            )
        }
        if (spec.pro) {
            return listOf(
                Issue(
                    start,
                    end,
                    "@$name is a Datastar Pro action; it needs the Pro bundle.",
                    Severity.HINT,
                    IssueCode.PRO_ACTION,
                    Docs.ACTIONS,
                ),
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
                        IssueCode.STATEMENT_SPLIT,
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

/** Where the client wrote each of its rewrites in the text it gives, in their order. */
internal fun ClientRewrite.writtenAt(): IntArray {
    var grown = 0
    return IntArray(rewrites.size) { n ->
        val r = rewrites[n]
        (r.start + grown).also { grown += r.written.length - (r.end - r.start) }
    }
}

/**
 * How many functions each of the client's rewrites in [rewritten] stands in, in their order, by the tree of the text
 * ([scriptOrLiteral]); null where it does not parse, and no depth is known.
 */
internal fun rewriteDepths(rewritten: ClientRewrite): IntArray? {
    val parsed = scriptOrLiteral(rewritten.js)
    if (parsed.error != null) return null
    val depth = IntArray(rewritten.js.length + 2)
    for (node in parsed.nodes) {
        if (node.type != JsKind.FUNCTION && node.type != JsKind.ARROW) continue
        depth[node.start]++
        depth[node.end]--
    }
    for (i in 1 until depth.size) depth[i] += depth[i - 1]
    val at = rewritten.writtenAt()
    return IntArray(at.size) { depth[at[it]] }
}

/** An assignment after a signal, `= 1`, `+= 1`, or its update, `++`: not `==` or `=>`. */
private val ASSIGNS_AFTER = regex("""\s*(?:(?:[-+*/%&|^]|\*\*|<<|>>>?|&&|\|\||\?\?)?=(?![=>])|\+\+|--)""")

/** The actions besides the backend ones that change something: signals, the clipboard. */
private val SIDE_EFFECT_ACTIONS = setOf("setAll", "toggleAll", "clipboard")

/** Whether `++` or `--` stands before [at] in [text] as a prefix update, not as the postfix one of what precedes it. */
private fun updatedBefore(
    text: String,
    at: Int,
): Boolean {
    var k = at
    while (k > 0 && text[k - 1].isJsWhitespace()) k--
    if (k < 2 || !(text.startsWith("++", k - 2) || text.startsWith("--", k - 2))) return false
    var b = k - 2
    while (b > 0 && text[b - 1].isJsWhitespace()) b--
    val before = text.getOrNull(b - 1)
    return !(before.isJsNamePart() || before == ')' || before == ']')
}

/**
 * [js] read as a script. An expression that opens with `{` may be an object literal Datastar wraps
 * in `return (...)`, which a script reads as a block: when the script does not parse, such a text
 * is read once more as the one expression it then is.
 */
private fun scriptOrLiteral(js: String): Parsed {
    val script = JsParser.parse(js)
    if (script.error == null || !js.trimStart().startsWith("{")) return script
    return JsParser.expression(js).takeIf { it.error == null } ?: script
}

/** The signal name typed so far just before [offset] (`$cou|` gives `cou`, `$|` gives ""), or null when not in a signal. */
internal fun signalPrefixAt(
    text: String,
    offset: Int,
): String? = SIGNAL_PREFIX.find(text.substring(0, offset.coerceIn(0, text.length)))?.let { it.groupValues[1] }

/** The action name typed so far just before [offset], or null when not in an action. */
internal fun actionPrefixAt(
    text: String,
    offset: Int,
): String? = ACTION_PREFIX.find(text.substring(0, offset.coerceIn(0, text.length)))?.let { it.groupValues[1] }
