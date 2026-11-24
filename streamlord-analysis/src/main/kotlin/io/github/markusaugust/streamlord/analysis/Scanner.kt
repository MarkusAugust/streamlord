package io.github.markusaugust.streamlord.analysis

import io.github.markusaugust.streamlord.core.client.regex

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

    /** The string arguments of the parameters [CallSiteSpec.more] names, after the one [selectString] picks. */
    public fun moreStrings(spec: CallSiteSpec): List<KotlinString> =
        spec.more.mapIndexedNotNull { i, name -> spec.arg?.let { selectString(it + 1 + i, name) } }

    /** Every string argument a call-site spec points at: [selectString]'s and [moreStrings]. */
    public fun expressionStrings(spec: CallSiteSpec): List<KotlinString> = listOfNotNull(selectString(spec)) + moreStrings(spec)

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
public class KotlinLex internal constructor(
    public val strings: List<KotlinString>,
    /** `true` where the offset is code, as opposed to a string literal, character literal or comment. */
    public val mask: BooleanArray,
    /** `true` where the offset is in a comment. */
    internal val comments: BooleanArray,
) {
    public constructor(strings: List<KotlinString>, mask: BooleanArray) : this(strings, mask, BooleanArray(mask.size))

    /**
     * The string literal that contains an offset, if any. Taken from the literals the lexer found
     * reading forward from the start of the file, not by reading back from the offset: a quote inside
     * a raw HTML string (`data-text="$count"`) is not the start of a literal, and only the forward
     * reading knows that. They come in source order and apart, so the one that may hold the offset is
     * the last to start before it.
     */
    internal fun stringAt(offset: Int): KotlinString? {
        val before = strings.binarySearch { if (it.start < offset) -1 else 1 }.let { -it - 2 }
        return strings.getOrNull(before)?.takeIf { offset <= it.end }
    }

    /** The literals that lie wholly from [start] up to [end], found by where they start, since [strings] is in source order. */
    internal fun stringsIn(
        start: Int,
        end: Int,
    ): List<KotlinString> {
        var i = strings.binarySearch { it.start.compareTo(start) }.let { if (it < 0) -it - 1 else it }
        val out = ArrayList<KotlinString>()
        while (i < strings.size && strings[i].start < end) {
            if (strings[i].end <= end) out += strings[i]
            i++
        }
        return out
    }

    /**
     * The blocks of code in [src], each from its `{` to its `}`, in the order they open: a block
     * comes after every block it is in. One left open runs to the end of the file.
     */
    internal fun blocks(src: String): List<IntRange> {
        val out = ArrayList<IntRange>()
        val open = ArrayList<Int>()
        for (i in src.indices) {
            if (!mask[i]) continue
            if (src[i] == '{') {
                open += out.size
                out += i..src.length
            } else if (src[i] == '}' && open.isNotEmpty()) {
                val at = open.removeAt(open.lastIndex)
                out[at] = out[at].first..i
            }
        }
        return out
    }
}

/** The innermost of [this], blocks as [KotlinLex.blocks] gives them, that holds [at]. */
internal fun List<IntRange>.around(at: Int): IntRange? = lastOrNull { at in it }

private val FUN_BEFORE = regex("""\bfun\s+(?:[A-Za-z_][A-Za-z0-9_<>.,?* ]*\.)?$""")
private val NAMED_ARG = regex("""^([A-Za-z_][A-Za-z0-9_]*)\s*=(?!=)""")
private val TRAILING_LAMBDA = regex("""^\s*\{""")

/** `.trimIndent()` and `.trimMargin()` change the indentation of a literal, not what it says. */
private val TRIM_CALL = regex("""\.\s*trim(?:Indent|Margin)\s*\(\s*(?:"[^"\\\n]*"\s*)?\)""")

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

/** [a] up to the end of its last token, without the comments and whitespace after it: `x /* id */` as `x`. */
internal fun uncommented(
    src: String,
    a: Arg,
): Arg {
    var i = a.start
    var last = a.start
    while (true) {
        i = skipTrivia(src, i, a.end)
        if (i >= a.end) break
        i =
            when {
                isStringStart(src, i) -> readKotlinStringAt(src, i)?.end ?: (i + 1)
                src[i] == '\'' -> charLiteralEnd(src, i)
                else -> i + 1
            }.coerceAtMost(a.end)
        last = i
    }
    return if (last == a.end) a else a.copy(end = last, text = src.substring(a.start, last))
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
internal fun lexKotlin(src: String): KotlinLex {
    val strings = ArrayList<KotlinString>()
    val mask = BooleanArray(src.length) { true }
    val comments = BooleanArray(src.length)

    fun blank(
        from: Int,
        to: Int,
        comment: Boolean = false,
    ) {
        for (k in from until minOf(to, src.length)) {
            mask[k] = false
            comments[k] = comment
        }
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
        if (c == '/' && (src.getOrNull(i + 1) == '/' || src.getOrNull(i + 1) == '*')) {
            val j = commentEnd(src, i)
            blank(i, j, comment = true)
            i = j
            continue
        }
        i++
    }
    return KotlinLex(strings, mask, comments)
}

/** IntelliJ's injection annotation for HTML, by its short name or its qualified one, on a use site and with a prefix or a suffix too. */
internal const val HTML_LANGUAGE =
    """@(?:[a-z]+:\s*)?(?:org\.intellij\.lang\.annotations\.)?Language\s*\(\s*(?:value\s*=\s*)?"html"\s*(?:,[^()]*)?\)"""

private val HTML_MARKER = regex("""$HTML_LANGUAGE|//\s*language\s*=\s*html\b""", RegexOption.IGNORE_CASE)
private val PARAMETER_AFTER_MARKER =
    regex(
        """^\s*(?:(?:private|protected|internal|public|override|open|final|vararg|crossinline|noinline)\s+)*(?:va[lr]\s+)?[A-Za-z_][A-Za-z0-9_]*\s*:\s*String\??\s*(?:=[^,)]*)?[,)]""",
    )

/**
 * Does a string literal hold HTML? Yes when its text opens with a tag, a comment or a doctype, or when IntelliJ's
 * mark stands before it, `@Language("HTML")` on the function or property or a `// language=HTML` comment, which
 * reaches the next literal only, past those of other annotations' arguments. A literal handed straight to a parameter
 * of the file's own function that carries the mark is HTML too, and so is one that is markup by where it stands, in
 * the block of `interpolate` or handed to `Trusted`. No literal of an annotation's arguments is.
 */
public fun isHtmlString(
    src: String,
    s: KotlinString,
): Boolean {
    val facts = fileFacts(src)
    // An annotation's arguments say something of the code: no literal of them is markup.
    if (facts.inAnnotation(s.start)) return false
    if (textOpensWithTag(markupView(src, s))) return true
    if (s.start in facts.htmlArguments || s.start in facts.markupLiterals) return true
    // A mark reaches the next literal after it, past those of other annotations' arguments.
    val from = maxOf(0, s.start - 240)
    val before = src.substring(from, s.start)
    // `@Language("HTML")` in a comment marks nothing; `// language=HTML` is a comment.
    val last = HTML_MARKER.findAll(before).lastOrNull { it.value.startsWith("/") || facts.code[from + it.range.first] } ?: return false
    val after = before.substring(last.range.last + 1)
    // `fun f(@Language("HTML") html: String? = null, ...)` marks a parameter, not the next literal, behind other
    // annotations too.
    val at = from + last.range.last + 1
    // Annotations and comments read as space: `@Named("x")body` and `@Named("x") /* c */ body` hold a parameter.
    val bare = CharArray(after.length) { k -> if (facts.inAnnotation(at + k) || !facts.code[at + k]) ' ' else after[k] }.concatToString()
    if (PARAMETER_AFTER_MARKER.containsMatchIn(bare)) return false
    // A literal between the mark and this one takes it: a quote in a comment or an annotation's arguments is none.
    return after.indices.none { after[it] == '"' && !facts.inAnnotation(at + it) && !inComment(src, facts.code, at + it) }
}
