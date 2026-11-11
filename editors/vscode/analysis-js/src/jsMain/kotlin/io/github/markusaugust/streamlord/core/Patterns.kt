package io.github.markusaugust.streamlord.core

/*
 * A pattern written for java.util.regex, respelled so that JavaScript's engine in its `u` mode gives
 * it the same meaning. The two differ on white space, on where the input and a line end, on what
 * the dot leaves out, on letter case written as `(?i)`, and on a few spellings; PatternsTest holds
 * the cases. A construct this file does not know is refused, never passed on to mean something else.
 */

// java.util.regex's line terminators: \n, \r, U+0085, U+2028 and U+2029, with \r\n as one.
private const val INPUT_END = """(?=(?:\r\n|[\r\u0085  ]|(?<!\r)\n)?(?![\s\S]))"""
private const val LINE_END = """(?=\r\n|[\r\u0085  ]|(?<!\r)\n|(?![\s\S]))"""
private const val LINE_START = """(?:(?<![\s\S])|(?<=[\n\u0085  ])|(?<=\r)(?!\n))(?=[\s\S])"""
private const val DOT = """[^\n\r\u0085  ]"""

// What a backslash may stand before in JavaScript's `u` mode and keep its meaning.
private const val SYNTAX = "^$\\.*+?()[]{}|/"
private const val SAME_ESCAPES = "dDwWbBnrtf"

private val cache = HashMap<String, Regex>()

@InternalStreamlordApi
public actual fun regex(
    pattern: String,
    vararg options: RegexOption,
): Regex {
    val key = if (options.isEmpty()) pattern else options.joinToString(",", postfix = ":") { it.name } + pattern
    cache[key]?.let { return it }
    // Patterns built from the names in a file would otherwise be kept for ever.
    if (cache.size >= 4096) cache.clear()
    return JavaScriptPattern(pattern, options.toSet()).regex().also { cache[key] = it }
}

private val indexed = HashMap<Regex, dynamic>()

/** Runs the pattern again at the match with the `d` flag, which is where JavaScript says where a group stands. */
@InternalStreamlordApi
public actual fun Regex.groupRange(
    input: CharSequence,
    match: MatchResult,
    group: Int,
): IntRange? {
    if (indexed.size >= 4096) indexed.clear()
    val again =
        indexed.getOrPut(this) {
            js("RegExp")(pattern, if (RegexOption.IGNORE_CASE in options) "duyi" else "duy")
        }
    again.lastIndex = match.range.first
    val found = again.exec(input.toString()) ?: return null
    val at = found.indices[group] ?: return null
    return (at[0] as Int) until (at[1] as Int)
}

internal class JavaScriptPattern(
    private val source: String,
    options: Set<RegexOption>,
) {
    private val out = StringBuilder(source.length + 16)
    private var at = 0
    private var dotAll = false
    private var lines = RegexOption.MULTILINE in options

    // As an option Kotlin asks the JVM to fold every letter, which is what JavaScript's `i` does.
    private val everyCase = RegexOption.IGNORE_CASE in options

    // `(?i)` in the pattern folds ASCII letters alone, so each is written out as both.
    private var asciiCase = false

    fun regex(): Regex = if (everyCase) Regex(translated(), RegexOption.IGNORE_CASE) else Regex(translated())

    fun translated(): String {
        leadingFlags()
        while (at < source.length) atom()
        return out.toString()
    }

    private fun refuse(what: String): Nothing =
        throw IllegalArgumentException("$what at $at of the pattern $source has no JavaScript spelling regex() knows")

    private fun leadingFlags() {
        if (!source.startsWith("(?")) return
        val end = source.indexOf(')')
        val flags = source.substring(2, maxOf(end, 2))
        if (flags.isEmpty() || flags.any { it !in "ims" }) return
        asciiCase = 'i' in flags && !everyCase
        lines = lines || 'm' in flags
        dotAll = dotAll || 's' in flags
        at = end + 1
    }

    private fun atom() {
        val c = source[at]
        when (c) {
            '\\' -> escape(inClass = false)
            '[' -> characterClass()
            ']' -> emit("\\]")
            '}' -> emit("\\}")
            '{' -> repetition()
            '.' -> emit(if (dotAll) """[\s\S]""" else DOT)
            '$' -> emit(if (lines) LINE_END else INPUT_END)
            '^' -> emit(if (lines) LINE_START else "^")
            '(' -> group()
            '*', '+', '?' -> quantifier(1)
            else -> if (asciiCase && c.isAsciiLetter()) emit("[${c.lowercaseChar()}${c.uppercaseChar()}]") else emit(c.toString())
        }
    }

    private fun emit(
        text: String,
        consumed: Int = 1,
    ) {
        out.append(text)
        at += consumed
    }

    private fun Char.isAsciiLetter(): Boolean = this in 'a'..'z' || this in 'A'..'Z'

    /** A quantifier of [length] characters, with the `?` that makes it lazy; `+` after it would make it possessive. */
    private fun quantifier(length: Int) {
        emit(source.substring(at, at + length), length)
        if (source.getOrNull(at) == '?') emit("?")
        if (source.getOrNull(at) == '+') refuse("A possessive quantifier")
    }

    private fun repetition() {
        val close = source.indexOf('}', at)
        val body = if (close < 0) "" else source.substring(at + 1, close)
        val counts = body.split(',')
        val counted = counts.size <= 2 && counts[0].isNotEmpty() && counts.all { n -> n.all { it in '0'..'9' } }
        if (counted) quantifier(close - at + 1) else emit("\\{")
    }

    private fun group() {
        if (source.getOrNull(at + 1) != '?') return emit("(")
        val known = listOf("(?:", "(?=", "(?!", "(?<=", "(?<!").firstOrNull { source.startsWith(it, at) }
        when {
            known != null -> emit(known, known.length)
            source.startsWith("(?<", at) && source.getOrNull(at + 3)?.isAsciiLetter() == true -> emit("(?<", 3)
            else -> refuse("The group")
        }
    }

    private fun escape(inClass: Boolean) {
        val c = source.getOrNull(at + 1) ?: refuse("A backslash that ends the pattern")
        when {
            c == 's' -> emit(if (inClass) """\t-\r """ else """[\t-\r ]""", 2)

            c == 'S' && !inClass -> emit("""[^\t-\r ]""", 2)

            c == 'z' && !inClass -> emit("""(?![\s\S])""", 2)

            c == 'Z' && !inClass -> emit(INPUT_END, 2)

            c == 'A' && !inClass -> emit("^", 2)

            c == 'x' && source.getOrNull(at + 2) == '{' -> emit("\\u", 2)

            c == 'x' || c == 'u' -> numbered(if (c == 'x') 4 else 6, inClass)

            c == 'p' || c == 'P' -> category()

            c == 'Q' -> quoted()

            c in '1'..'9' && !inClass -> if (asciiCase) refuse("A back reference under (?i)") else emit("\\$c", 2)

            c in SAME_ESCAPES && !(inClass && (c == 'b' || c == 'B')) -> emit("\\$c", 2)

            c in SYNTAX -> emit("\\$c", 2)

            c == '-' -> emit(if (inClass) "\\-" else "-", 2)

            c.isLetterOrDigit() -> refuse("The escape \\$c")

            // Any other character escaped is itself, which `u` mode wants written bare.
            else -> emit(c.toString(), 2)
        }
    }

    /** `\x41` or `\u0041`, [length] characters of the pattern: a letter named by its number is folded as one written out. */
    private fun numbered(
        length: Int,
        inClass: Boolean,
    ) {
        val written = source.substring(at, minOf(source.length, at + length))
        val named = written.drop(2).toIntOrNull(16)?.toChar() ?: refuse("The escape $written")
        when {
            !asciiCase || !named.isAsciiLetter() -> emit(written, length)
            inClass -> emit("${named.lowercaseChar()}${named.uppercaseChar()}", length)
            else -> emit("[${named.lowercaseChar()}${named.uppercaseChar()}]", length)
        }
    }

    /** `\p{L}`: a general category, which both engines name alike. */
    private fun category() {
        val close = source.indexOf('}', at)
        val name = if (source.getOrNull(at + 2) == '{' && close > 0) source.substring(at + 3, close) else ""
        val general = name.length in 1..2 && name[0] in 'A'..'Z' && name.drop(1).all { it in 'a'..'z' }
        if (!general) refuse("The class \\p")
        if ((asciiCase || everyCase) && name in setOf("Lu", "Ll", "Lt")) refuse("A letter case class where case is ignored")
        emit(source.substring(at, close + 1), close + 1 - at)
    }

    /** `\Q...\E`, which is how the JVM writes `Regex.escape`. */
    private fun quoted() {
        val end = source.indexOf("\\E", at + 2).let { if (it < 0) source.length else it }
        for (c in source.substring(at + 2, end)) out.append(if (c in SYNTAX || c == '-') "\\$c" else c.toString())
        at = minOf(source.length, end + 2)
    }

    private fun characterClass() {
        val end = classEnd()
        val body = source.substring(at, end)
        // Everything, whichever engine reads it; \S alone inside a class has no spelling.
        if ("\\S" in body.replace("\\\\", "")) {
            if ("\\s" !in body) refuse("\\S in a character class")
            return emit(if (body.startsWith("[^")) """[^\s\S]""" else """[\s\S]""", end + 1 - at)
        }
        emit("[")
        if (source[at] == '^') emit("^")
        // A bracket that closes straight away is a member to the JVM.
        if (source[at] == ']') emit("\\]")
        while (at < end) member(end)
        emit("]")
    }

    private fun classEnd(): Int {
        var i = at + 1
        if (source.getOrNull(i) == '^') i++
        if (source.getOrNull(i) == ']') i++
        while (i < source.length && source[i] != ']') {
            if (source[i] == '[') refuse("A class inside a class")
            if (source.startsWith("&&", i)) refuse("A class intersection")
            // \Q...\E inside a class would hide a bracket from this scan.
            if (source[i] == '\\') i++
            i++
        }
        if (i >= source.length) refuse("A class that never closes")
        return i
    }

    private fun member(end: Int) {
        val c = source[at]
        if (c == '\\') return escape(inClass = true)
        if (!asciiCase) return emit(c.toString())
        val ranged = source[at + 1] == '-' && at + 2 < end
        val to = if (ranged) source[at + 2] else c
        if (to == '\\') refuse("A range to an escape under (?i)")
        val letters = c.isAsciiLetter() && to.isAsciiLetter() && c.isLowerCase() == to.isLowerCase()
        val lettersBetween = (c..to).any { it.isAsciiLetter() }
        when {
            letters && ranged -> emit("${c.lowercaseChar()}-${to.lowercaseChar()}${c.uppercaseChar()}-${to.uppercaseChar()}", 3)
            letters -> emit("${c.lowercaseChar()}${c.uppercaseChar()}")
            lettersBetween -> refuse("A range across letters under (?i)")
            else -> emit(source.substring(at, at + if (ranged) 3 else 1), if (ranged) 3 else 1)
        }
    }
}
