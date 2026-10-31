package io.github.markusaugust.streamlord.core.domain

import io.github.markusaugust.streamlord.core.client.CODE_TEXT
import io.github.markusaugust.streamlord.core.client.RAW_TEXT
import io.github.markusaugust.streamlord.core.client.asciiLowercase
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
    tracked: Boolean = false,
): Boolean {
    if (tracked) return scanMarkupTracked(html, visitor)
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

private const val NS_HTML = 0
private const val NS_SVG = 1
private const val NS_MATH = 2

/** SVG elements whose content the browser parses as HTML (an HTML integration point). */
private val SVG_HTML_INTEGRATION = setOf("foreignobject", "desc", "title")

/** MathML elements whose content is HTML text (a MathML text integration point). */
private val MATH_TEXT_INTEGRATION = setOf("mi", "mo", "mn", "ms", "mtext")

/** HTML tags that, met inside an `<svg>` or `<math>`, close the foreign content and return to HTML. */
private val EXITS_FOREIGN =
    setOf(
        "b",
        "big",
        "blockquote",
        "body",
        "br",
        "center",
        "code",
        "dd",
        "div",
        "dl",
        "dt",
        "em",
        "embed",
        "h1",
        "h2",
        "h3",
        "h4",
        "h5",
        "h6",
        "head",
        "hr",
        "i",
        "img",
        "li",
        "listing",
        "menu",
        "meta",
        "nobr",
        "ol",
        "p",
        "pre",
        "ruby",
        "s",
        "small",
        "span",
        "strong",
        "strike",
        "sub",
        "sup",
        "table",
        "tt",
        "u",
        "ul",
        "var",
    )

/**
 * Walks [html] the way the browser's tree builder does, following its stack of open elements so
 * that the rule at each point, a CDATA section or a bogus comment, an element's body as text or as
 * markup, is the one in force where the markup actually sits. Where the fixed readings each hold
 * one rule throughout, this one switches as the browser does: a `<![CDATA[` is a section only
 * inside an `<svg>` or `<math>` that is not at an integration point, and once an `</svg>` or an
 * exiting tag returns to HTML the same bytes are a bogus comment again.
 *
 * It is not a parser: it keeps only each open element's namespace and whether its content is HTML,
 * enough to know the context, and matches an end tag to the nearest open element of its name.
 */
private fun scanMarkupTracked(
    html: String,
    visitor: MarkupVisitor,
): Boolean {
    val stackNs = ArrayList<Int>()
    val stackName = ArrayList<String>()
    val stackHtmlContent = ArrayList<Boolean>() // Whether this element's children are HTML (an integration point).

    // The namespace new content is inserted into, and whether a `<![CDATA[` here is a section.
    fun insertionNs(): Int =
        if (stackNs.isEmpty()) {
            NS_HTML
        } else if (stackHtmlContent.last()) {
            NS_HTML
        } else {
            stackNs.last()
        }

    var i = 0
    val n = html.length
    while (i < n) {
        val lt = html.indexOf('<', i)
        if (lt < 0) break
        val foreignNow = insertionNs() != NS_HTML
        if (html.startsWith("<!--", lt)) {
            if (commentOpen(html, lt)) return false
            i = commentEnd(html, lt)
            continue
        }
        if (foreignNow && html.startsWith("<![CDATA[", lt)) {
            val close = html.indexOf("]]>", lt + 9)
            if (close < 0) return false
            i = close + 3
            continue
        }
        val first = html.getOrNull(lt + 1) ?: return false
        if (first == '!' || first == '?' || (first == '/' && !html.getOrNull(lt + 2).isAsciiLetter() && html.getOrNull(lt + 2) != '>')) {
            val close = html.indexOf('>', lt + 2)
            if (close < 0) return false
            i = close + 1
            continue
        }
        if (first == '/' && html.getOrNull(lt + 2).isAsciiLetter()) {
            val end = scanTag(html, lt + 2, Discard)
            visitor.tag(lt, if (end < 0) n else end)
            if (end < 0) return false
            val name = tagName(html, lt + 2)
            val at = stackName.lastIndexOf(name)
            if (at >= 0) {
                while (stackName.size > at) {
                    stackNs.removeAt(stackNs.size - 1)
                    stackName.removeAt(stackName.size - 1)
                    stackHtmlContent.removeAt(stackHtmlContent.size - 1)
                }
            }
            i = end
            continue
        }
        if (!first.isAsciiLetter()) {
            i = lt + 1
            continue
        }

        val name = tagName(html, lt + 1)
        // In foreign content, an HTML tag such as <p> or <img> closes the foreign elements first.
        if (foreignNow && name in EXITS_FOREIGN) {
            while (stackNs.isNotEmpty() && stackNs.last() != NS_HTML && !stackHtmlContent.last()) {
                stackNs.removeAt(stackNs.size - 1)
                stackName.removeAt(stackName.size - 1)
                stackHtmlContent.removeAt(stackHtmlContent.size - 1)
            }
        }
        val html5 = insertionNs() == NS_HTML

        val encoding = EncodingSnoop(html, visitor, name == "annotation-xml")
        val end = scanTag(html, lt + 1, encoding)
        visitor.tag(lt, if (end < 0) n else end)
        if (end < 0) return false
        // A `/` that ends an unquoted value is part of the value, not a self-closing mark.
        val selfClosing = end >= 2 && html[end - 2] == '/' && encoding.unquotedEnd != end - 1
        i = end

        if (html5) {
            // HTML raw-text and code elements swallow their body to their own close tag.
            val raw = RAW_TEXT.firstOrNull { it.equals(name, ignoreCase = true) }
            if (raw != null) {
                val close = if (raw == "plaintext") -1 else rawTextEnd(html, raw, i)
                if (raw in CODE_TEXT) visitor.code(i, if (close < 0) n else close)
                if (close < 0) return false
                i = close
                continue
            }
            if (selfClosing) continue
            val ns =
                if (name == "svg") {
                    NS_SVG
                } else if (name == "math") {
                    NS_MATH
                } else {
                    NS_HTML
                }
            stackNs += ns
            stackName += name
            stackHtmlContent += false
        } else {
            if (selfClosing) continue
            val ns =
                if (name == "svg") {
                    NS_SVG
                } else if (name == "math") {
                    NS_MATH
                } else {
                    stackNs.last()
                }
            val integration =
                (ns == NS_SVG && name in SVG_HTML_INTEGRATION) ||
                    (ns == NS_MATH && name in MATH_TEXT_INTEGRATION) ||
                    (ns == NS_MATH && name == "annotation-xml" && encoding.value?.asciiLowercase() in HTML_ANNOTATION_ENCODINGS)
            stackNs += ns
            stackName += name
            stackHtmlContent += integration
        }
    }
    return true
}

private val HTML_ANNOTATION_ENCODINGS = setOf("text/html", "application/xhtml+xml")

/** The tag name beginning at [from] (after `<` or `</`), lowercased. */
private fun tagName(
    html: String,
    from: Int,
): String {
    var i = from
    while (i < html.length && !html[i].endsName()) i++
    return html.substring(from, i).asciiLowercase()
}

/**
 * Records attributes to [delegate] and, for an `annotation-xml`, keeps its `encoding` value: the
 * first one, since the tokenizer drops a repeated attribute. Also keeps where the last unquoted
 * value ended.
 */
private class EncodingSnoop(
    private val markup: String,
    private val delegate: MarkupVisitor,
    private val wanted: Boolean,
) : MarkupVisitor {
    var value: String? = null
    var unquotedEnd = -1

    override fun attribute(
        tag: String,
        name: String,
        valueStart: Int,
        valueEnd: Int,
        quoted: Boolean,
    ) {
        if (wanted && value == null && name.equals("encoding", ignoreCase = true) && valueStart >= 0) {
            value = markup.substring(valueStart, valueEnd)
        }
        if (!quoted && valueStart >= 0) unquotedEnd = valueEnd
        delegate.attribute(tag, name, valueStart, valueEnd, quoted)
    }

    override fun tag(
        start: Int,
        end: Int,
    ) = delegate.tag(start, end)

    override fun code(
        start: Int,
        end: Int,
    ) = delegate.code(start, end)
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
                // The first character always starts the name, even an `=`, which the tokenizer
                // takes as part of the name here; only after it does an `=` end the name and open a value.
                val nameStart = i++
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
