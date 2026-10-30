package io.github.markusaugust.streamlord.core.domain

import io.github.markusaugust.streamlord.core.client.CODE_TEXT
import io.github.markusaugust.streamlord.core.client.RAW_TEXT
import io.github.markusaugust.streamlord.core.client.commentEnd
import io.github.markusaugust.streamlord.core.client.isHtmlSpace
import io.github.markusaugust.streamlord.core.client.rawTextEnd
import io.github.markusaugust.streamlord.core.client.regionMatchesAsciiIgnoreCase

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
 * `textarea` and the like hold elements rather than text. With [cdata] as well, a `<![CDATA[`
 * there is a section of text that only `]]>` ends, where HTML, and an integration point such as
 * an svg's `title`, reads a bogus comment the first `>` ends. Markup can switch between them, so a
 * caller that has to be right walks it each way and takes the strictest answer.
 *
 * @return Whether the markup ends in the data state, with no tag, attribute value, comment, CDATA
 *   section or raw-text body left open, and so whether markup written after it is read as written.
 */
internal fun scanMarkup(
    html: String,
    visitor: MarkupVisitor,
    foreign: Boolean = false,
    cdata: Boolean = false,
): Boolean {
    val rawText = if (foreign) CODE_TEXT else RAW_TEXT
    var i = 0
    val n = html.length
    while (i < n) {
        val lt = html.indexOf('<', i)
        if (lt < 0) break
        if (html.startsWith("<!--", lt)) {
            if (commentOpen(html, lt)) return false
            i = commentEnd(html, lt)
            continue
        }
        if (cdata && html.startsWith("<![CDATA[", lt)) {
            val close = html.indexOf("]]>", lt + 9)
            if (close < 0) return false
            i = close + 3
            continue
        }
        // A < at the very end is text, until whatever is written next makes it a tag.
        val first = html.getOrNull(lt + 1) ?: return false
        // <!x>, <?x> and </ followed by anything but a letter are bogus comments, which end at
        // the first >; a quote inside one opens nothing.
        if (first == '!' || first == '?' || (first == '/' && !html.getOrNull(lt + 2).isAsciiLetter() && html.getOrNull(lt + 2) != '>')) {
            val close = html.indexOf('>', lt + 2)
            if (close < 0) return false
            i = close + 1
            continue
        }
        // An end tag: the tokenizer reads its attributes as a start tag's, so a quoted value can
        // hold a >, and only then throws them away. The whole of it is the shape of the markup.
        if (first == '/' && html.getOrNull(lt + 2).isAsciiLetter()) {
            val end = scanTag(html, lt + 2, Discard)
            visitor.tag(lt, if (end < 0) n else end)
            if (end < 0) return false
            i = end
            continue
        }
        if (!first.isAsciiLetter()) {
            i = lt + 1
            continue
        }

        val end = scanTag(html, lt + 1, visitor)
        visitor.tag(lt, if (end < 0) n else end)
        if (end < 0) return false
        i = end

        val tag =
            rawText.firstOrNull {
                html.regionMatchesAsciiIgnoreCase(lt + 1, it) && html.getOrNull(lt + 1 + it.length).endsName()
            } ?: continue
        val close = if (tag == "plaintext") -1 else rawTextEnd(html, tag, i)
        if (tag in CODE_TEXT) visitor.code(i, if (close < 0) n else close)
        if (close < 0) return false
        i = close
    }
    return true
}

/** Whether the comment at [lt] runs to the end of [html], which is where commentEnd puts it then. */
private fun commentOpen(
    html: String,
    lt: Int,
): Boolean =
    !html.startsWith("<!-->", lt) &&
        !html.startsWith("<!--->", lt) &&
        html.indexOf("-->", lt + 4) < 0 &&
        html.indexOf("--!>", lt + 4) < 0

/** A visitor that wants nothing: for the attributes of an end tag, and for a walk asked only whether markup closes. */
internal object Discard : MarkupVisitor {
    override fun attribute(
        tag: String,
        name: String,
        valueStart: Int,
        valueEnd: Int,
        quoted: Boolean,
    ) = Unit
}

/** Walks the attributes of one tag, starting after `<` or `</`; returns the offset past `>`, or -1 when the markup ends first. */
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
    return -1
}
