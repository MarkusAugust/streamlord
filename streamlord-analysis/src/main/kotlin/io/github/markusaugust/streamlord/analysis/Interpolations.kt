package io.github.markusaugust.streamlord.analysis

import io.github.markusaugust.streamlord.core.client.groupRange
import io.github.markusaugust.streamlord.core.client.regex
import kotlin.concurrent.Volatile

/*
 * The pieces of the interpolation judgement that are about Kotlin rather than about Datastar:
 * which values are joined into an HTML string, which of them the analysis can see are safe or
 * are markup themselves, what the text looks like once `trimMargin` has run, and the rewrite to
 * `interpolate`. Where a placeholder lands is core's `valueLandings`.
 */

// A trim call, read to its end where it has no argument or a margin written as a literal, by position or by name.
private val TRIM = regex("""\s*\.\s*trim(Indent|Margin)\s*\((\s*(?:(?:marginPrefix\s*=\s*)?"([^"\\\n]*)"\s*)?\))?""")
internal val KOTLIN_NAME = regex("""[\p{L}_][\p{L}\p{Nd}_]*""")
internal val LITERAL = regex("""(?:-?\d[\d_]*(?:\.\d+)?[LlFfDd]?|true|false|null)""")
internal val STRING_LITERAL = regex(""""(?:[^"\\$]|\\.)*"""")

internal val UNSIGNED_SUFFIX = regex("""[uU][lL]?$""")
internal val MARKUP_CALL_START = regex("""^(?:interpolate|Trusted)\s*\(""")
private val WORD_CHAR = regex("""[\p{L}\p{Nd}_]""")

/** An expression as a message quotes it: on one line, and cut short past 30 characters. */
internal fun shortened(text: String): String {
    val e = text.trim().replace(SPACES, " ")
    return if (e.length <= 30) e else e.substring(0, 29) + "…"
}

private val SPACES = regex("""\s+""")

/**
 * How many dollars open the template, as its own literal has it: literals joined with `+` each
 * have their own prefix, so the string they make says nothing of it. None for a value joined with `+`.
 */
internal val Interpolation.dollars: Int
    get() =
        when (kind) {
            InterpolationKind.SIMPLE -> end - start - text.length
            InterpolationKind.BRACED -> end - start - text.length - 2
            InterpolationKind.OPERAND -> 0
        }

/** How a placeholder is named in a message, as it is written: `$name`, `${expr}` shortened, or an operand of `+` as the value it is. */
internal fun valueShown(ip: Interpolation): String {
    if (ip.kind == InterpolationKind.SIMPLE) return "$".repeat(ip.dollars) + ip.text
    if (ip.unread) return "a value joined with + that cannot be read as a whole"
    if (ip.kind == InterpolationKind.OPERAND) return "the value `${shortened(ip.text)}`"
    return "$".repeat(ip.dollars) + "{" + shortened(ip.text) + "}"
}

/** Whether the literal [ip] is written in is a raw one: the literals of a `+` chain may differ. */
internal fun KotlinString.rawAt(ip: Interpolation): Boolean = literals.firstOrNull { ip.start >= it.start && ip.end <= it.end }?.raw ?: raw

/**
 * The string's text as `trimMargin` leaves it for the markup checks: each margin marker at the
 * start of a line becomes a space, so nothing moves and the map still holds. In a `+` chain each
 * literal is trimmed by the call after it alone, as Kotlin binds it.
 */
internal fun markupView(
    src: String,
    s: KotlinString,
): String {
    val literals = s.literals.ifEmpty { listOf(s) }
    val margins = literals.map { trimAfter(src, it)?.margin.orEmpty() }
    if (margins.all { it.isEmpty() }) return s.text
    val out = StringBuilder()
    // The literal the text at [i] is written in, by the source it maps to: each starts a first line of its own.
    var part = -1
    var lineStart = true
    var i = 0
    while (i < s.text.length) {
        while (part + 1 < literals.size && s.map[i] >= literals[part + 1].contentStart) {
            part++
            lineStart = true
        }
        val marker = if (part >= 0 && s.map[i] < literals[part].contentEnd) margins[part] else ""
        val c = s.text[i]
        if (lineStart && c != ' ' && c != '\t') {
            lineStart = false
            if (marker.isNotEmpty() && s.text.startsWith(marker, i)) {
                out.append(" ".repeat(marker.length))
                i += marker.length
                continue
            }
        }
        if (c == '\n') lineStart = true
        out.append(c)
        i++
    }
    return out.toString()
}

/**
 * The trim call after a literal: [text] as it is written, null where its argument is no literal,
 * which nothing here reads; [margin] is what `trimMargin` strips, and null for `trimIndent`.
 */
internal class Trim(
    val text: String?,
    val margin: String?,
)

/** The trim call that follows the literal, or null where none does: one after a `+` chain is its last literal's, and none of the chain's. */
internal fun trimAfter(
    src: String,
    s: KotlinString,
): Trim? {
    if (s.literals.isNotEmpty()) return null
    val call = TRIM.matchAt(src, s.end) ?: return null
    if (call.groups[2] == null) return Trim(null, null)
    return Trim(call.value, if (call.groupValues[1] == "Margin") call.groups[3]?.value ?: "|" else null)
}

/** Every literal the lexer found, and those inside their templates. */
internal fun withNested(strings: List<KotlinString>): List<KotlinString> {
    val out = ArrayList<KotlinString>()

    fun visit(s: KotlinString) {
        out += s
        for (n in s.nested) visit(n)
    }
    for (s in strings) visit(s)
    return out
}

/**
 * A braced template whose every result is a literal the author wrote: `${if (n == 1) "error"
 * else "errors"}`, a number, `true`. Its value is markup the source spells out.
 */
internal fun literalOnly(expr: String): Boolean =
    (branches(expr) ?: listOf(expr)).all { LITERAL.matchEntire(it.trim()) != null || plainString(it.trim()) }

internal val QUOTE_OPENING = regex("""^\$*"""")

/** [t] read as one whole string literal, its templates and all, or null when it is anything more or less. */
internal fun stringLiteral(t: String): KotlinString? {
    if (!QUOTE_OPENING.containsMatchIn(t)) return null
    return readKotlinStringAt(t, 0)?.takeIf { !it.unterminated && it.end == t.length }
}

/** Whether [t] is one string literal without a template: `"x"`, and `"$"` too, whose dollar opens none. */
internal fun plainString(t: String): Boolean = STRING_LITERAL.matchEntire(t) != null || stringLiteral(t)?.interpolations?.isEmpty() == true

private fun wordAt(
    e: String,
    at: Int,
    w: String,
): Boolean =
    e.startsWith(w, at) &&
        WORD_CHAR.matchEntire(e.getOrNull(at - 1)?.toString() ?: "") == null &&
        WORD_CHAR.matchEntire(e.getOrNull(at + w.length)?.toString() ?: "") == null

/** Just past the string or character literal that opens at [at], templates and all. */
internal fun literalEnd(
    e: String,
    at: Int,
): Int {
    if (e[at] == '\'') {
        val close = e.indexOf('\'', at + (if (e.getOrNull(at + 1) == '\\') 3 else 2))
        return if (close < 0) e.length else close + 1
    }
    val raw = e.startsWith("\"\"\"", at)
    var j = at + (if (raw) 3 else 1)
    while (j < e.length) {
        if (!raw && e[j] == '\\') {
            j += 2
        } else if (e.startsWith("\${", j)) {
            var depth = 1
            j += 2
            while (j < e.length && depth > 0) {
                if (e[j] == '"' || e[j] == '\'') {
                    j = literalEnd(e, j)
                    continue
                }
                if (e[j] == '{') {
                    depth++
                } else if (e[j] == '}') {
                    depth--
                }
                j++
            }
        } else if (if (raw) e.startsWith("\"\"\"", j) else e[j] == '"') {
            return j + (if (raw) 3 else 1)
        } else {
            j++
        }
    }
    return e.length
}

/** How deep in brackets each character of [e] stands, a bracket counting as outside itself; -1 inside a literal. */
internal fun depths(e: String): IntArray {
    val out = IntArray(e.length) { -1 }
    var depth = 0
    var i = 0
    while (i < e.length) {
        val c = e[i]
        if (c == '"' || c == '\'') {
            i = literalEnd(e, i)
            continue
        }
        if (c == ')' || c == ']' || c == '}') depth--
        out[i] = depth
        if (c == '(' || c == '[' || c == '{') depth++
        i++
    }
    return out
}

/** Just past the bracket that closes the one at [open], or -1. */
internal fun closing(
    e: String,
    d: IntArray,
    open: Int,
): Int {
    for (k in open + 1 until e.length) if (d[k] == d[open] && e[k] in ")]}") return k + 1
    return -1
}

/** A call that makes a list, a set or an array of the values it is given. */
private val COLLECTION_CALL =
    regex(
        """^(?:listOf|mutableListOf|arrayListOf|setOf|mutableSetOf|arrayOf|(?:int|long|short|byte|double|float|boolean|char)ArrayOf)""" +
            """\s*(?:<[^()]*>)?\s*\(""",
    )

/** The elements of [text] when it is one call that makes a list, a set or an array of them, `listOf(a, b)`; null otherwise. */
internal fun collectionElements(text: String): List<String>? {
    val t = text.trim()
    val open = (COLLECTION_CALL.find(t) ?: return null).value.length - 1
    if (closing(t, depths(t), open) != t.length) return null
    return splitTop(t.substring(open + 1, t.length - 1), ",").map { it.trim() }.filter { it.isNotEmpty() }
}

/** A branch as the value it gives: a block of one expression is that expression. */
private fun unblock(v: String): String {
    val t = v.trim()
    return if (t.startsWith("{") && closing(t, depths(t), 0) == t.length) t.substring(1, t.length - 1).trim() else t
}

/**
 * The values an `if` or a `when` can result in, as written, a branch that is itself one read in
 * turn: `if (b) "x" else q` gives `"x"` and `q`. Null when the expression is neither, or one this
 * reading cannot take apart.
 */
internal fun branches(expr: String): List<String>? {
    val e = expr.trim()
    val d = depths(e)

    fun expand(v: String): List<String> {
        val t = unblock(v)
        return branches(t) ?: listOf(t)
    }

    fun skipSpace(from: Int): Int {
        var k = from
        while (k < e.length && e[k].isWhitespace()) k++
        return k
    }
    if (wordAt(e, 0, "if")) {
        val open = skipSpace(2)
        if (e.getOrNull(open) != '(') return null
        val close = closing(e, d, open)
        if (close < 0) return null
        for (k in close until e.length) {
            if (d[k] == 0 && wordAt(e, k, "else")) return expand(e.substring(close, k)) + expand(e.substring(k + 4))
        }
        return null
    }
    if (wordAt(e, 0, "when")) {
        var open = skipSpace(4)
        if (e.getOrNull(open) == '(') {
            open = closing(e, d, open)
            if (open < 0) return null
            open = skipSpace(open)
        }
        if (e.getOrNull(open) != '{' || closing(e, d, open) != e.length) return null
        val values = ArrayList<String>()
        var entry = open + 1
        var arrow = -1
        var k = open + 1
        while (k < e.length) {
            if (d[k] == 1 && arrow < 0 && e.startsWith("->", k)) {
                arrow = k
                k += 2
                continue
            }
            if (k == e.length - 1 || (d[k] == 1 && (e[k] == ';' || e[k] == '\n'))) {
                if (arrow >= 0) {
                    values += expand(e.substring(arrow + 2, k))
                } else if (e.substring(entry, k).isNotBlank()) {
                    return null
                }
                arrow = -1
                entry = k + 1
            }
            k++
        }
        return values.takeIf { it.isNotEmpty() }
    }
    return null
}

/**
 * The `+` chain the literal [first] stands in, read as one string: each literal's text, and a placeholder
 * for each value joined to them, mapped back to the source. The values are read as Kotlin has
 * them ([Operands]): what is joined before the first literal is one value, a chain in brackets
 * that starts with a literal is read through, and an `if` goes on to the end of the expression.
 * A call or an index after a literal is passed over and noted ([KotlinString.called]), and so is
 * anything joined that could not be read as a value ([KotlinString.unread]). Null when the
 * literal is joined to nothing. [mask] is the lexer's: `false` inside literals and comments.
 */
internal fun chainFrom(
    src: String,
    first: KotlinString,
    mask: BooleanArray,
): KotlinString? {
    val read = Operands(src, mask)
    val text = StringBuilder()
    val map = ArrayList<Int>()
    val interpolations = ArrayList<Interpolation>()
    val literals = ArrayList<KotlinString>()
    var called = false
    var unread = false
    // Where the last part read ends, and where its text does: a literal's before its closing quotes.
    var end = 0
    var contentEnd = 0

    fun value(
        start: Int,
        to: Int,
        whole: Boolean = true,
    ) {
        interpolations += Interpolation(start, to, InterpolationKind.OPERAND, src.substring(start, to), text.length, !whole)
        repeat(PLACEHOLDER.length) { map += start }
        text.append(PLACEHOLDER)
        end = to
        contentEnd = to
    }

    // The literal and what is joined after it, up to [newlines]: whether a line break ends nothing, as inside brackets.
    fun parts(
        literal: KotlinString,
        newlines: () -> Boolean,
    ) {
        var lit = literal
        while (true) {
            for (ip in lit.interpolations) interpolations += ip.copy(decodedStart = ip.decodedStart + text.length)
            for (c in lit.text.indices) map += lit.map[c]
            text.append(lit.text)
            literals += lit
            end = lit.end
            contentEnd = lit.contentEnd
            // The calls and indexes after a literal, `.trim()`, `[0]`, `!!`, are that literal's alone.
            var i = read.postfixEnd(end, false)
            if (i != end) called = true
            while (true) {
                var plus = read.skip(i, false)
                if (src.getOrNull(plus).let { it == '\n' || it == '\r' } && newlines()) plus = read.skip(i, true)
                if (!read.plusAt(plus)) return
                val k = read.skip(plus + 1, true)
                if (isStringStart(src, k)) {
                    lit = readKotlinStringAt(src, k)?.takeIf { !it.unterminated } ?: return
                    break
                }
                val close = if (src.getOrNull(k) == '(') read.matching(k) else -1
                val inner = read.skip(k + 1, true)
                val opens = close > 0 && isStringStart(src, inner)
                val group = if (opens) readKotlinStringAt(src, inner)?.takeIf { !it.unterminated } else null
                if (group != null) {
                    // Brackets around a chain that starts with a literal join nothing differently.
                    parts(group) { true }
                    if (read.skip(end, true) != close) unread = true
                    end = close + 1
                    contentEnd = end
                    i = read.postfixEnd(end, true)
                    if (i != end) called = true
                    continue
                }
                val term = read.termEnd(k, false)
                if (term < 0) {
                    // An `if` goes on to the end of the expression; so does what could not be read, which is warned of whole.
                    val rest = read.expressionEnd(k, newlines())
                    val known = rest <= k || read.word(k) == "if"
                    if (rest > k) value(k, if (known) rest else minOf(rest, read.certainEnd(k)), known)
                    if (!known) {
                        unread = true
                        end = rest
                        contentEnd = rest
                    }
                    return
                }
                value(k, term)
                i = term
            }
        }
    }
    val leading = read.leading(first.start)
    // What could not be read is marked where it is certainly the value's: where it ends.
    val from = leading?.let { if (it.read || it.end <= it.start) it.start else maxOf(it.start, read.certainStart(it.end)) }
    if (leading != null && leading.end > leading.start) value(from!!, leading.end, leading.read)
    if (leading != null && !leading.read) unread = true
    val inBrackets by lazy(LazyThreadSafetyMode.NONE) { read.inBrackets(first.start) }
    parts(first) { inBrackets }
    if (leading == null && end == first.end) return null
    map += contentEnd
    return KotlinString(
        start = if (leading != null && leading.end > leading.start) from!! else first.start,
        end = end,
        raw = first.raw,
        dollars = 1,
        contentStart = first.contentStart,
        contentEnd = contentEnd,
        text = text.toString(),
        map = map.toIntArray(),
        interpolations = interpolations,
        unterminated = false,
        nested = literals.flatMap { it.nested },
        literals = literals,
        called = called,
        unread = unread,
    )
}

/** What a value handed to `interpolate` is, as far as the text shows: what core's `admits` decides by. */
internal enum class ArgKind { STRING, NUMBER, UNSIGNED, BOOLEAN, NULL, CHAR, TRUSTED, MARKUP }

internal val KOTLIN_NUMBER = regex("""-?(?:0[xX][0-9A-Fa-f_]+|0[bB][01_]+|\d[\d_]*(?:\.\d[\d_]*)?(?:[eE][+-]?\d+)?)[LlFfDdUu]*""")
internal val CHAR_LITERAL = regex("""'(?:[^'\\]|\\.[^']*)'""")
private val IF_START = regex("""^if\s*\(""")

/** Whether [text] is one call to [name] whose argument list closes at its end: `Trusted(x)`, not `Trusted(x).html`. */
internal fun wholeCall(
    text: String,
    name: String,
): Boolean {
    if (!regex("""^$name\s*\(""").containsMatchIn(text) || !text.endsWith(")")) return false
    val mask = lexKotlin(text).mask
    var depth = 0
    for (i in text.indexOf('(') until text.length) {
        if (!mask[i]) continue
        if (text[i] == '(') {
            depth++
        } else if (text[i] == ')') {
            depth--
            if (depth == 0) return i == text.length - 1
        }
    }
    return false
}

/**
 * The kind of a value handed to `interpolate`: a literal, `Trusted(...)`, a call to
 * `interpolate` (its Html), or a name the enclosing declaration gives a type. Null when the text
 * does not show it.
 */
internal fun valueKind(
    src: String,
    text: String,
    literal: Boolean,
    at: Int,
): ArgKind? {
    val t = text.trim()
    if (literal) return ArgKind.STRING
    if (KOTLIN_NUMBER.matchEntire(t) != null) return if (UNSIGNED_SUFFIX.containsMatchIn(t)) ArgKind.UNSIGNED else ArgKind.NUMBER
    if (t == "true" || t == "false") return ArgKind.BOOLEAN
    if (t == "null") return ArgKind.NULL
    if (CHAR_LITERAL.matchEntire(t) != null) return ArgKind.CHAR
    if (IF_START.containsMatchIn(t) && literalOnly(t)) return if ('"' in t) ArgKind.STRING else null
    if (coreCall(src, t, "Trusted") || (KOTLIN_NAME.matchEntire(t) != null && trustedName(src, t, at))) return ArgKind.TRUSTED
    if (coreCall(src, t, "interpolate")) return ArgKind.MARKUP
    // The String of the Html: interpolate(...).text.
    val html = TEXT_OF_VALUE.find(t)?.groupValues?.get(1)
    if (html != null && coreCall(src, html, "interpolate")) return ArgKind.STRING
    callKind(src, t, at, emptySet())?.argKind()?.let { return it }
    if (KOTLIN_NAME.matchEntire(t) == null) return null
    // `val name = "Gorvek"`: a String, though no type is written.
    val declared = declaredKind(src, t, at) ?: return if (declarationOf(src, t, at)?.string == true) ArgKind.STRING else null
    return declared.argKind()
}
