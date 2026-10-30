package io.github.markusaugust.streamlord.core.client

import io.github.markusaugust.streamlord.core.InternalStreamlordApi

/*
 * What the browser's HTML tokenizer decides before Datastar sees anything: which elements hold
 * text rather than markup, and what an attribute value says once its character references are
 * decoded. The scanner under interpolate and ElementsGuard and the analysis's tokenizer read
 * markup with these, so the server and the editors agree on it.
 */

/**
 * HTML elements whose body is text to the browser: `<` inside them opens no tag, up to the
 * element's own end tag. `plaintext` has none and runs to the end.
 */
@InternalStreamlordApi
public val RAW_TEXT: List<String> =
    listOf("script", "style", "textarea", "title", "xmp", "iframe", "noembed", "noframes", "noscript", "plaintext")

/** Of those, the ones whose body is a program. */
internal val CODE_TEXT: List<String> = listOf("script", "style")

/** HTML's own white space, which alone ends a tag or an attribute name: narrower than Kotlin's `isWhitespace`. */
@InternalStreamlordApi
public const val HTML_SPACE: String = "\t\n\u000C\r "

/** Whether this is [HTML_SPACE]. */
@InternalStreamlordApi
public fun Char.isHtmlSpace(): Boolean = this in HTML_SPACE

/** Where `</[name]` begins after [from] as a whole name, `</titlex>` not ending a `title`; -1 when nowhere. */
@InternalStreamlordApi
public fun rawTextEnd(
    html: String,
    name: String,
    from: Int,
): Int {
    var at = from
    while (true) {
        val close = html.indexOfAsciiIgnoreCase("</$name", at)
        if (close < 0) return -1
        val next = html.getOrNull(close + 2 + name.length)
        if (next == null || next == '/' || next == '>' || next.isHtmlSpace()) return close
        at = close + 1
    }
}

/**
 * One past the end of the comment that opens at [lt]: `<!-->` and `<!--->` are whole comments,
 * and `-->` and `--!>` both close one.
 */
@InternalStreamlordApi
public fun commentEnd(
    html: String,
    lt: Int,
): Int {
    if (html.startsWith("<!-->", lt)) return lt + 5
    if (html.startsWith("<!--->", lt)) return lt + 6
    val dash = html.indexOf("-->", lt + 4)
    val bang = html.indexOf("--!>", lt + 4)
    return when {
        dash < 0 && bang < 0 -> html.length
        bang < 0 || (dash in 0 until bang) -> dash + 3
        else -> bang + 4
    }
}

/*
 * A named reference with its semicolon, one of the few legacy names the browser also reads
 * without it (unless `=` or a letter or digit follows, as in a URL's `&copy=1`), or a numeric
 * one, which the browser reads with or without its semicolon.
 */
private val ENTITY =
    Regex("""&(?:([A-Za-z][A-Za-z0-9]*);|(amp|AMP|lt|LT|gt|GT|quot|QUOT|nbsp)(?![=A-Za-z0-9])|#(\d+);?|#[xX]([0-9A-Fa-f]+);?)""")

/** The named references that spell a character an expression may hold: the ASCII punctuation, and the whitespace. */
private val NAMED_ENTITIES =
    mapOf(
        "amp" to "&",
        "AMP" to "&",
        "lt" to "<",
        "LT" to "<",
        "gt" to ">",
        "GT" to ">",
        "quot" to "\"",
        "QUOT" to "\"",
        "apos" to "'",
        "nbsp" to "\u00A0",
        "Tab" to "\t",
        "NewLine" to "\n",
        "excl" to "!",
        "num" to "#",
        "dollar" to "$",
        "percnt" to "%",
        "lpar" to "(",
        "rpar" to ")",
        "ast" to "*",
        "midast" to "*",
        "plus" to "+",
        "comma" to ",",
        "period" to ".",
        "sol" to "/",
        "colon" to ":",
        "semi" to ";",
        "equals" to "=",
        "quest" to "?",
        "commat" to "@",
        "lsqb" to "[",
        "lbrack" to "[",
        "bsol" to "\\",
        "rsqb" to "]",
        "rbrack" to "]",
        "Hat" to "^",
        "lowbar" to "_",
        "UnderBar" to "_",
        "grave" to "`",
        "DiacriticalGrave" to "`",
        "lcub" to "{",
        "lbrace" to "{",
        "verbar" to "|",
        "vert" to "|",
        "VerticalLine" to "|",
        "rcub" to "}",
        "rbrace" to "}",
    )

/** Text with its character references decoded, and for every decoded index the offset it came from. Has `text.length + 1` entries. */
@InternalStreamlordApi
public class DecodedValue(
    public val text: String,
    public val map: IntArray,
) {
    /** The offset in the value that [decoded] in [text] came from. */
    public fun toSource(decoded: Int): Int = map[decoded.coerceIn(0, map.size - 1)]
}

/**
 * The attribute value as the browser hands it to Datastar: `&amp;&amp;` is `&&` by then. The
 * named references that spell ASCII punctuation or whitespace, the legacy ones the browser reads
 * without a semicolon, and the numeric ones are decoded; `&copy;` and the rest stay as written,
 * which to the expression they are.
 */
@InternalStreamlordApi
public fun decodeEntities(value: String): DecodedValue {
    if ('&' !in value) return DecodedValue(value, IntArray(value.length + 1) { it })
    val out = StringBuilder()
    val map = ArrayList<Int>()
    var i = 0
    while (i < value.length) {
        val m = if (value[i] == '&') ENTITY.matchAt(value, i) else null
        val decoded =
            when {
                m == null -> {
                    null
                }

                m.groupValues[1].isNotEmpty() -> {
                    NAMED_ENTITIES[m.groupValues[1]]
                }

                m.groupValues[2].isNotEmpty() -> {
                    NAMED_ENTITIES[m.groupValues[2]]
                }

                // A number too large for an Int is past U+10FFFF, which the browser reads as U+FFFD.
                else -> {
                    codePoint(
                        m.groupValues[3].takeIf { it.isNotEmpty() }?.let { it.toIntOrNull() ?: Int.MAX_VALUE }
                            ?: m.groupValues[4].toIntOrNull(16)
                            ?: Int.MAX_VALUE,
                    )
                }
            }
        if (m == null || decoded == null) {
            out.append(value[i])
            map += i
            i++
            continue
        }
        for (c in decoded) {
            out.append(c)
            map += i
        }
        i += m.value.length
    }
    map += value.length
    return DecodedValue(out.toString(), map.toIntArray())
}

/** The character a numeric reference names, as the browser reads it: NUL, a surrogate and past U+10FFFF are U+FFFD. */
private fun codePoint(cp: Int): String =
    if (cp == 0 || cp > 0x10FFFF || cp in 0xD800..0xDFFF) "\uFFFD" else String(Character.toChars(WINDOWS_1252[cp] ?: cp))

// The numeric references the HTML standard reads as windows-1252 rather than as C1 controls: `&#128;` is the euro sign.
private val WINDOWS_1252 =
    mapOf(
        0x80 to 0x20AC,
        0x82 to 0x201A,
        0x83 to 0x0192,
        0x84 to 0x201E,
        0x85 to 0x2026,
        0x86 to 0x2020,
        0x87 to 0x2021,
        0x88 to 0x02C6,
        0x89 to 0x2030,
        0x8A to 0x0160,
        0x8B to 0x2039,
        0x8C to 0x0152,
        0x8E to 0x017D,
        0x91 to 0x2018,
        0x92 to 0x2019,
        0x93 to 0x201C,
        0x94 to 0x201D,
        0x95 to 0x2022,
        0x96 to 0x2013,
        0x97 to 0x2014,
        0x98 to 0x02DC,
        0x99 to 0x2122,
        0x9A to 0x0161,
        0x9B to 0x203A,
        0x9C to 0x0153,
        0x9E to 0x017E,
        0x9F to 0x0178,
    )
