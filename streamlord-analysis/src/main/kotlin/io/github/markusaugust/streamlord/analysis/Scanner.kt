package io.github.markusaugust.streamlord.analysis

/*
 * Finds calls to known DSL functions in Kotlin source and splits their arguments, without a
 * Kotlin parser. The DSL names are distinctive enough that a call is `name(` not preceded by
 * `fun`; arguments are split on top-level commas while skipping strings, nested brackets,
 * lambdas and comments.
 */

public data class Arg(
    /** Source offset where the argument text starts (after `name =` if named). */
    val start: Int,
    val end: Int,
    val named: String?,
    val text: String,
    /** Set when the argument is exactly one string literal. */
    val string: KotlinString?,
)

public data class CallSite(
    val name: String,
    val nameStart: Int,
    val openParen: Int,
    val closeParen: Int,
    val args: List<Arg>,
    /** `true` when a trailing lambda follows the closing parenthesis. */
    val trailingLambda: Boolean,
) {
    public val positional: List<Arg> get() = args.filter { it.named == null }

    public fun named(name: String): Arg? = args.firstOrNull { it.named == name }

    /** Pick the string argument a call-site spec points at. */
    public fun selectString(spec: CallSiteSpec): KotlinString? = selectString(spec.arg, spec.named)

    public fun selectString(
        arg: Int?,
        named: String?,
    ): KotlinString? {
        if (named != null) {
            val byName = this.named(named)
            if (byName != null) return byName.string
        }
        val positional = positional
        if (arg == null) return positional.lastOrNull { it.string != null }?.string
        return positional.getOrNull(arg)?.string
    }
}

/** The string literals of a Kotlin file and a mask of which offsets are code. */
public class KotlinLex(
    public val strings: List<KotlinString>,
    /** `true` where the offset is code, as opposed to a string literal, character literal or comment. */
    public val mask: BooleanArray,
) {
    /**
     * The string literal that contains an offset, if any. Scans from the start of the file rather
     * than backwards from the offset: a quote inside a raw HTML string (`data-text="$count"`) is
     * not the start of a literal, and only a forward scan knows that.
     */
    public fun stringAt(offset: Int): KotlinString? {
        for (s in strings) {
            if (s.start >= offset) return null
            if (offset <= s.end) return s
        }
        return null
    }
}

private val FUN_BEFORE = Regex("""\bfun\s+(?:[A-Za-z_][A-Za-z0-9_<>.,?* ]*\.)?$""")
private val NAMED_ARG = Regex("""^([A-Za-z_][A-Za-z0-9_]*)\s*=(?!=)""")
private val TRAILING_LAMBDA = Regex("""^\s*\{""")

/** `.trimIndent()` and `.trimMargin()` change the indentation of a literal, not what it says. */
private val TRIM_CALL = Regex("""\.\s*trim(?:Indent|Margin)\s*\(\s*(?:"[^"\\\n]*"\s*)?\)""")

/** The offset of the first character from [from] that is neither whitespace nor part of a comment. */
private fun skipTrivia(
    src: String,
    from: Int,
    to: Int,
): Int {
    var i = from
    while (i < to) {
        if (src[i].isWhitespace()) {
            i++
        } else if (src.startsWith("//", i)) {
            val nl = src.indexOf('\n', i)
            i = if (nl < 0 || nl > to) to else nl
        } else if (src.startsWith("/*", i)) {
            val close = src.indexOf("*/", i + 2)
            i = if (close < 0 || close + 2 > to) to else close + 2
        } else {
            break
        }
    }
    return i
}

public fun findCallSites(
    src: String,
    names: Set<String>,
    lex: KotlinLex = lexKotlin(src),
): List<CallSite> {
    val sites = ArrayList<CallSite>()
    val mask = lex.mask
    var i = 0
    while (i < src.length) {
        val c = src[i]
        if (!c.isIdentStart() || (i > 0 && src[i - 1].isIdentPart())) {
            i++
            continue
        }
        var j = i
        while (j < src.length && src[j].isIdentPart()) j++
        val name = src.substring(i, j)
        var k = j
        while (k < src.length && src[k].isWhitespace()) k++
        if (k >= src.length || src[k] != '(' || name !in names || !mask[i]) {
            i = j
            continue
        }
        if (FUN_BEFORE.containsMatchIn(src.substring(maxOf(0, i - 80), i))) {
            i = j
            continue
        }
        val parsed = parseArgs(src, k)
        if (parsed == null) {
            i = j
            continue
        }
        sites += CallSite(name, i, k, parsed.closeParen, parsed.args, parsed.trailingLambda)
        i = k + 1
    }
    return sites
}

private class ParsedArgs(
    val closeParen: Int,
    val args: List<Arg>,
    val trailingLambda: Boolean,
)

private fun parseArgs(
    src: String,
    openParen: Int,
): ParsedArgs? {
    val args = ArrayList<Arg>()
    var depth = 0
    var i = openParen + 1
    var argStart = i

    fun finish(end: Int) {
        // A comment after the last comma is not an argument.
        if (skipTrivia(src, argStart, end) == end) return
        args += makeArg(src, argStart, end)
    }
    while (i < src.length) {
        val c = src[i]
        if ((c == '"' || c == '$') && isStringStart(src, i)) {
            val s = readKotlinStringAt(src, i)
            i = if (s != null) maxOf(s.end, i + 1) else i + 1
            continue
        }
        if (c == '\'') {
            val close = src.indexOf('\'', i + 2 + if (src.getOrNull(i + 1) == '\\') 1 else 0)
            i = if (close > 0) close + 1 else i + 1
            continue
        }
        if (c == '/' && src.getOrNull(i + 1) == '/') {
            val nl = src.indexOf('\n', i)
            i = if (nl < 0) src.length else nl
            continue
        }
        if (c == '/' && src.getOrNull(i + 1) == '*') {
            val close = src.indexOf("*/", i + 2)
            i = if (close < 0) src.length else close + 2
            continue
        }
        when {
            c == '(' || c == '[' || c == '{' -> {
                depth++
            }

            c == ')' || c == ']' || c == '}' -> {
                if (depth == 0) {
                    if (c != ')') return null
                    finish(i)
                    val after = src.substring(i + 1, minOf(src.length, i + 40))
                    return ParsedArgs(i, args, TRAILING_LAMBDA.containsMatchIn(after))
                }
                depth--
            }

            c == ',' && depth == 0 -> {
                finish(i)
                argStart = i + 1
            }
        }
        i++
    }
    return null
}

private fun makeArg(
    src: String,
    from: Int,
    to: Int,
): Arg {
    var end = to
    while (end > from && src[end - 1].isWhitespace()) end--
    var start = skipTrivia(src, from, end)
    var named: String? = null
    val nm = NAMED_ARG.find(src.substring(start, end))
    if (nm != null) {
        named = nm.groupValues[1]
        start = skipTrivia(src, start + nm.value.length, end)
    }
    var string: KotlinString? = null
    if (isStringStart(src, start)) {
        val s = readKotlinStringAt(src, start)
        if (s != null) {
            // The argument is still one literal with a trim call or a comment after it.
            var after = skipTrivia(src, s.end, end)
            TRIM_CALL.matchAt(src, after)?.let { after = skipTrivia(src, it.range.last + 1, end) }
            if (after == end) string = s
        }
    }
    return Arg(start, end, named, src.substring(start, end), string)
}

/**
 * One pass over Kotlin source: the string literals, and a mask of which offsets are code as
 * opposed to string literals, character literals and comments. Kotlin block comments nest,
 * raw strings span lines, and a character literal may hold an escape such as `'A'`.
 */
public fun lexKotlin(src: String): KotlinLex {
    val strings = ArrayList<KotlinString>()
    val mask = BooleanArray(src.length) { true }

    fun blank(
        from: Int,
        to: Int,
    ) {
        for (k in from until minOf(to, src.length)) mask[k] = false
    }
    var i = 0
    while (i < src.length) {
        val c = src[i]
        if ((c == '"' || c == '$') && isStringStart(src, i)) {
            val s = readKotlinStringAt(src, i)
            if (s != null) strings += s
            val end = if (s != null) maxOf(s.end, i + 1) else i + 1
            blank(i, end)
            i = end
            continue
        }
        if (c == '\'') {
            val j = charLiteralEnd(src, i)
            blank(i, j)
            i = j
            continue
        }
        if (c == '/' && src.getOrNull(i + 1) == '/') {
            val nl = src.indexOf('\n', i)
            val end = if (nl < 0) src.length else nl
            blank(i, end)
            i = end
            continue
        }
        if (c == '/' && src.getOrNull(i + 1) == '*') {
            var depth = 1
            var j = i + 2
            while (j < src.length && depth > 0) {
                if (src.startsWith("/*", j)) {
                    depth++
                    j += 2
                } else if (src.startsWith("*/", j)) {
                    depth--
                    j += 2
                } else {
                    j++
                }
            }
            blank(i, j)
            i = j
            continue
        }
        i++
    }
    return KotlinLex(strings, mask)
}

/** Every string literal in the source that is not inside a comment, in order. */
public fun findKotlinStrings(src: String): List<KotlinString> = lexKotlin(src).strings

private val HTML_START = Regex("""^\s*<(?:[A-Za-z]|!--|!doctype)""", RegexOption.IGNORE_CASE)
private val HTML_MARKER = Regex("""@Language\(\s*"html"\s*\)|//\s*language\s*=\s*html\b""", RegexOption.IGNORE_CASE)
private val PARAMETER_AFTER_MARKER = Regex("""^\s*(?:va[lr]\s+)?[A-Za-z_][A-Za-z0-9_]*\s*:\s*String\??\s*(?:=[^,)]*)?[,)]""")

/**
 * Does a string literal hold HTML? Yes when its text opens with a tag, a comment or a doctype,
 * or when the code just before it carries IntelliJ's injection marker: `@Language("HTML")` on
 * the function or property, or a `// language=HTML` comment. The marker reaches the next
 * string literal only.
 */
public fun isHtmlString(
    src: String,
    s: KotlinString,
): Boolean {
    if (HTML_START.containsMatchIn(s.text)) return true
    val before = src.substring(maxOf(0, s.start - 240), s.start)
    val last = HTML_MARKER.findAll(before).lastOrNull() ?: return false
    val after = before.substring(last.range.last + 1)
    // `fun f(@Language("HTML") html: String? = null, ...)` annotates a parameter, not the next literal:
    // the marker is followed at once by a parameter declaration. A function's own parameters
    // further on (`fun greeting(name: String): String = """..."""`) do not count.
    if (PARAMETER_AFTER_MARKER.containsMatchIn(after)) return false
    return '"' !in after
}
