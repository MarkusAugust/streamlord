package io.github.markusaugust.streamlord.core.domain

/**
 * What a walk over HTML reports. Offsets are into the string that was walked, so a caller can
 * ask where something sits as well as what it says.
 */
internal interface MarkupVisitor {
    /**
     * One attribute of one tag.
     *
     * @param tag The tag's name as written.
     * @param valueStart First character of the value, or -1 for an attribute written without one.
     * @param valueEnd One past the last character of the value.
     * @param quoted Whether the value stood in quotes. An unquoted value ends at the first space,
     *   so anything written into one can add attributes of its own.
     */
    fun attribute(
        tag: String,
        name: String,
        valueStart: Int,
        valueEnd: Int,
        quoted: Boolean,
    )

    /** One tag, from its `<` to one past its `>`. */
    fun tag(
        start: Int,
        end: Int,
    ) = Unit

    /** The body of a `<script>` or `<style>`, which is code to the browser rather than text. */
    fun code(
        start: Int,
        end: Int,
    ) = Unit
}

/**
 * HTML elements whose body is text to the browser: `<` inside them opens no tag, up to the
 * element's own end tag. `plaintext` has none and runs to the end.
 */
private val RAW_TEXT = listOf("script", "style", "textarea", "title", "xmp", "iframe", "noembed", "noframes", "noscript", "plaintext")

/** Of those, the ones whose body is a program. */
private val CODE_TEXT = listOf("script", "style")

/** HTML's own white space, which is narrower than Kotlin's `isWhitespace`. */
private fun Char.isHtmlSpace(): Boolean = this == ' ' || this == '\t' || this == '\n' || this == '\u000C' || this == '\r'

/** The characters that end a tag name, and so an end tag's name as well. */
private fun Char?.endsName(): Boolean = this == null || isHtmlSpace() || this == '/' || this == '>'

private fun Char?.isAsciiLetter(): Boolean = this != null && (this in 'a'..'z' || this in 'A'..'Z')

/**
 * Walks the tags of [html] and reports them to [visitor].
 *
 * Not a parser: it never builds a tree and never copies the markup. It knows the three things
 * that decide what a span of output can do to the browser, which is where a tag begins and ends,
 * where each attribute value sits and whether it is quoted, and which element bodies are code.
 * It reads them as the HTML tokenizer does, comments and bogus comments included.
 *
 * With [foreign], it reads the markup as the inside of an `<svg>` or `<math>`, where `title`,
 * `textarea` and the like hold elements rather than text. Markup can switch between the two, so
 * a caller that has to be right walks it both ways and takes the stricter answer.
 */
internal fun scanMarkup(
    html: String,
    visitor: MarkupVisitor,
    foreign: Boolean = false,
) {
    val rawText = if (foreign) CODE_TEXT else RAW_TEXT
    var i = 0
    val n = html.length
    while (i < n) {
        val lt = html.indexOf('<', i)
        if (lt < 0) break
        if (html.startsWith("<!--", lt)) {
            i = commentEnd(html, lt)
            continue
        }
        val first = html.getOrNull(lt + 1)
        // <!x>, <?x> and </ followed by anything but a letter are bogus comments, which end at
        // the first >; a quote inside one opens nothing.
        if (first == '!' || first == '?' || (first == '/' && !html.getOrNull(lt + 2).isAsciiLetter() && html.getOrNull(lt + 2) != '>')) {
            val close = html.indexOf('>', lt + 2)
            i = if (close < 0) n else close + 1
            continue
        }
        if (!first.isAsciiLetter()) {
            i = lt + 1
            continue
        }

        i = scanTag(html, lt + 1, visitor)
        visitor.tag(lt, i)

        rawText
            .firstOrNull {
                html.regionMatches(lt + 1, it, 0, it.length, ignoreCase = true) && html.getOrNull(lt + 1 + it.length).endsName()
            }?.let { tag ->
                val end = if (tag == "plaintext") n else endTag(html, tag, i)
                if (tag in CODE_TEXT) visitor.code(i, end)
                i = end
            }
    }
}

/**
 * One past the end of the comment that opens at [lt]: `<!-->` and `<!--->` are whole comments,
 * and `-->` and `--!>` both close one.
 */
private fun commentEnd(
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

/** Where `</tag` begins after [from], as a whole name: `</titlex>` does not end a `title`. */
private fun endTag(
    html: String,
    tag: String,
    from: Int,
): Int {
    var at = from
    while (true) {
        val close = html.indexOf("</$tag", at, ignoreCase = true)
        if (close < 0) return html.length
        if (html.getOrNull(close + 2 + tag.length).endsName()) return close
        at = close + 2
    }
}

/** Walks the attributes of one tag, starting after `<`; returns the offset past `>`. */
private fun scanTag(
    html: String,
    from: Int,
    visitor: MarkupVisitor,
): Int {
    var i = from
    val n = html.length
    while (i < n && !html[i].isHtmlSpace() && html[i] != '>' && html[i] != '/') i++
    val tag = html.substring(from, i)
    while (i < n) {
        val c = html[i]
        when {
            c == '>' -> {
                return i + 1
            }

            c.isHtmlSpace() || c == '/' -> {
                i++
            }

            else -> {
                val nameStart = i
                while (i < n && !html[i].isHtmlSpace() && html[i] != '>' && html[i] != '=' && html[i] != '/') i++
                val name = html.substring(nameStart, i)
                var k = i
                while (k < n && html[k].isHtmlSpace()) k++
                if (k < n && html[k] == '=') {
                    k++
                    while (k < n && html[k].isHtmlSpace()) k++
                    val q = html.getOrNull(k)
                    if (q == '"' || q == '\'') {
                        val close = html.indexOf(q, k + 1)
                        val end = if (close < 0) n else close
                        visitor.attribute(tag, name, k + 1, end, quoted = true)
                        i = if (close < 0) n else close + 1
                    } else {
                        val start = k
                        while (k < n && !html[k].isHtmlSpace() && html[k] != '>') k++
                        visitor.attribute(tag, name, start, k, quoted = false)
                        i = k
                    }
                } else {
                    visitor.attribute(tag, name, -1, -1, quoted = false)
                }
            }
        }
    }
    return n
}
