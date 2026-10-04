package io.github.markusaugust.streamlord.analysis

/*
 * Reading Kotlin string literals without a Kotlin parser.
 *
 * A literal is decoded to the text the JVM will see at runtime, with a map from every decoded
 * character back to its source offset, so diagnostics land on the right column. Kotlin string
 * templates are the one thing that cannot be decoded: `$name` and `${expr}` are recorded as
 * interpolations and replaced with a placeholder identifier, because that is what they are to
 * the Datastar expression around them: an opaque value.
 */

public enum class InterpolationKind { SIMPLE, BRACED }

public data class Interpolation(
    /** Source offset of the first `$` that opens the template. */
    val start: Int,
    /** Source offset just past the template. */
    val end: Int,
    val kind: InterpolationKind,
    /** The identifier for [InterpolationKind.SIMPLE], the inner text for [InterpolationKind.BRACED]. */
    val text: String,
    /** Offset in the decoded text where the placeholder starts. */
    val decodedStart: Int,
)

public data class KotlinString(
    /** Source offset where the literal starts: the opening quote, or the first `$` of a multi-dollar prefix. */
    val start: Int,
    /** Source offset just past the closing quote(s). */
    val end: Int,
    val raw: Boolean,
    /**
     * How many dollars open a template: 1 in an ordinary literal, 2 or more in a multi-dollar
     * literal (`$$"..."`, Kotlin 2.2+), where shorter runs are plain text and `$count` reaches
     * the browser untouched.
     */
    val dollars: Int,
    val contentStart: Int,
    val contentEnd: Int,
    /** The runtime text, with [PLACEHOLDER] in place of each template. */
    val text: String,
    /** Decoded index -> source offset. Has `text.length + 1` entries. */
    val map: IntArray,
    val interpolations: List<Interpolation>,
    /** `true` when the literal never closes. */
    val unterminated: Boolean,
) {
    /** Map a decoded range back to source offsets. */
    public fun toSource(
        decodedStart: Int,
        decodedEnd: Int,
    ): IntRange {
        val s = map[minOf(decodedStart, map.size - 1)]
        val endIdx = minOf(maxOf(decodedEnd, decodedStart + 1), map.size - 1)
        val e = maxOf(map[endIdx], s + 1)
        return s until e
    }
}

public const val PLACEHOLDER: String = "__kt__"

private val SIMPLE_ESCAPES =
    mapOf('n' to "\n", 't' to "\t", 'b' to "\b", 'r' to "\r", '"' to "\"", '\'' to "'", '\\' to "\\", '$' to "$")

internal fun Char.isIdentStart(): Boolean = this == '_' || this in 'A'..'Z' || this in 'a'..'z'

internal fun Char.isIdentPart(): Boolean = isIdentStart() || this in '0'..'9'

/** A Kotlin identifier may use any letter: `$år` is a template as much as `$year` is. */
private fun Char.isKotlinIdentStart(): Boolean = this == '_' || isLetter()

private fun Char.isKotlinIdentPart(): Boolean = isKotlinIdentStart() || isDigit()

/** The offset just past the character literal that opens at [at]: `'a'`, `'\n'`, `'\u0041'`. */
internal fun charLiteralEnd(
    src: String,
    at: Int,
): Int {
    var j = at + 1
    if (src.getOrNull(j) == '\\') j += if (src.getOrNull(j + 1) == 'u') 6 else 2 else j += 1
    if (src.getOrNull(j) == '\'') j++
    return j
}

/** Does a string literal start at [at]: a quote, or a run of dollars followed by a quote? */
public fun isStringStart(
    src: String,
    at: Int,
): Boolean {
    var i = at
    while (i < src.length && src[i] == '$') i++
    return i < src.length && src[i] == '"'
}

private class Decoder {
    val out = StringBuilder()
    val map = ArrayList<Int>()

    fun push(
        text: String,
        from: Int,
    ) {
        for (c in text) {
            out.append(c)
            map.add(from)
        }
    }

    fun push(
        c: Char,
        from: Int,
    ) {
        out.append(c)
        map.add(from)
    }
}

/**
 * Read the string literal that starts at [at]: at its opening quote, or at the first `$` of a
 * multi-dollar prefix. Returns null if no literal starts there.
 */
public fun readKotlinStringAt(
    src: String,
    at: Int,
): KotlinString? {
    var quote = at
    while (quote < src.length && src[quote] == '$') quote++
    if (quote >= src.length || src[quote] != '"') return null
    val dollars = maxOf(1, quote - at)
    val raw = src.startsWith("\"\"\"", quote)
    val contentStart = if (raw) quote + 3 else quote + 1
    var i = contentStart
    val d = Decoder()
    val interpolations = ArrayList<Interpolation>()

    while (i < src.length) {
        val c = src[i]
        if (raw) {
            if (src.startsWith("\"\"\"", i)) {
                // A raw string ends at the last quote of a run of three or more.
                var j = i
                while (j < src.length && src[j] == '"') j++
                val extra = j - i - 3
                for (k in 0 until extra) d.push('"', i + k)
                // The end of the text is where the closing quotes start, as in an ordinary literal.
                d.map.add(j - 3)
                return KotlinString(
                    start = at,
                    end = j,
                    raw = true,
                    dollars = dollars,
                    contentStart = contentStart,
                    contentEnd = j - 3,
                    text = d.out.toString(),
                    map = d.map.toIntArray(),
                    interpolations = interpolations,
                    unterminated = false,
                )
            }
        } else {
            if (c == '"') {
                d.map.add(i)
                return KotlinString(
                    start = at,
                    end = i + 1,
                    raw = false,
                    dollars = dollars,
                    contentStart = contentStart,
                    contentEnd = i,
                    text = d.out.toString(),
                    map = d.map.toIntArray(),
                    interpolations = interpolations,
                    unterminated = false,
                )
            }
            if (c == '\n') break
            if (c == '\\') {
                val n = src.getOrNull(i + 1)
                if (n == 'u' && i + 6 <= src.length && src.substring(i + 2, i + 6).all { it.isHexDigit() }) {
                    d.push(src.substring(i + 2, i + 6).toInt(16).toChar(), i)
                    i += 6
                    continue
                }
                if (n == null) {
                    i++
                    continue
                }
                d.push(SIMPLE_ESCAPES[n] ?: n.toString(), i)
                i += 2
                continue
            }
        }
        if (c == '$') {
            // A template opens with exactly `dollars` dollars; a shorter run is text. In a longer
            // run the first ones are text and the last `dollars` open the template.
            var run = 0
            while (i + run < src.length && src[i + run] == '$') run++
            if (run < dollars) {
                for (k in 0 until run) d.push('$', i + k)
                i += run
                continue
            }
            for (k in 0 until run - dollars) d.push('$', i + k)
            i += run - dollars
            val n = src.getOrNull(i + dollars)
            if (n == '{') {
                // ${'$'} is the idiom for a literal dollar; anything else is an opaque template.
                if (dollars == 1 && src.startsWith("\${'$'}", i)) {
                    d.push('$', i)
                    i += 6
                    continue
                }
                var depth = 1
                var j = i + dollars + 1
                while (j < src.length && depth > 0) {
                    val cj = src[j]
                    if (cj == '{') {
                        depth++
                    } else if (cj == '}') {
                        depth--
                    } else if (cj == '"') {
                        val inner = readKotlinStringAt(src, j)
                        if (inner != null) {
                            j = inner.end
                            continue
                        }
                    } else if (cj == '\'') {
                        // A brace in a character literal ('}') is not the end of the template.
                        j = charLiteralEnd(src, j)
                        continue
                    }
                    j++
                }
                interpolations +=
                    Interpolation(
                        start = i,
                        end = j,
                        kind = InterpolationKind.BRACED,
                        text = src.substring(i + dollars + 1, maxOf(i + dollars + 1, j - 1)),
                        decodedStart = d.out.length,
                    )
                d.push(PLACEHOLDER, i)
                i = j
                continue
            }
            if (n != null && n.isKotlinIdentStart()) {
                var j = i + dollars
                while (j < src.length && src[j].isKotlinIdentPart()) j++
                interpolations +=
                    Interpolation(
                        start = i,
                        end = j,
                        kind = InterpolationKind.SIMPLE,
                        text = src.substring(i + dollars, j),
                        decodedStart = d.out.length,
                    )
                d.push(PLACEHOLDER, i)
                i = j
                continue
            }
        }
        d.push(c, i)
        i++
    }
    d.map.add(i)
    return KotlinString(
        start = at,
        end = i,
        raw = raw,
        dollars = dollars,
        contentStart = contentStart,
        contentEnd = i,
        text = d.out.toString(),
        map = d.map.toIntArray(),
        interpolations = interpolations,
        unterminated = true,
    )
}

private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
