package io.github.markusaugust.streamlord.core.domain

import io.github.markusaugust.streamlord.core.InternalStreamlordApi
import io.github.markusaugust.streamlord.core.client.CODE_TEXT
import io.github.markusaugust.streamlord.core.client.RAW_TEXT
import io.github.markusaugust.streamlord.core.client.SCRIPTING_RAW_TEXT
import io.github.markusaugust.streamlord.core.client.asciiLowercase
import io.github.markusaugust.streamlord.core.client.commentClose
import io.github.markusaugust.streamlord.core.client.isHtmlSpace
import io.github.markusaugust.streamlord.core.client.rawTextEnd
import io.github.markusaugust.streamlord.core.client.regionMatchesAsciiIgnoreCase

/**
 * What a walk over HTML reports. Offsets are into the string that was walked, so a caller can
 * ask where something sits as well as what it says.
 */
@InternalStreamlordApi
public interface MarkupVisitor {
    /**
     * One attribute of one tag.
     *
     * @param tag The tag's name as written.
     * @param nameStart First character of the name.
     * @param valueStart First character of the value, or -1 for an attribute written without one.
     * @param valueEnd One past the last character of the value.
     * @param quoted Whether the value stood in quotes. An unquoted value ends at the first space,
     *   so anything written into one can add attributes of its own.
     */
    public fun attribute(
        tag: String,
        name: String,
        nameStart: Int,
        valueStart: Int,
        valueEnd: Int,
        quoted: Boolean,
    )

    /** One tag, from its `<` to one past its `>`. */
    public fun tag(
        start: Int,
        end: Int,
    ): Unit = Unit

    /**
     * A start tag, after its attributes, by [walkMarkup]: its name lowercased, whether it ends in
     * `/>`, and whether its body is text to its end tag, as a `<textarea>`'s is in HTML. [end] is the
     * length of the markup where the tag does not close.
     */
    public fun startTag(
        name: String,
        start: Int,
        end: Int,
        selfClosing: Boolean,
        rawText: Boolean,
    ): Unit = Unit

    /** An end tag, by [walkMarkup], its name lowercased. */
    public fun endTag(
        name: String,
        start: Int,
        end: Int,
    ): Unit = Unit

    /** Text between the tags, by [walkMarkup], with the number of elements open around it. */
    public fun text(
        start: Int,
        end: Int,
        depth: Int,
    ): Unit = Unit

    /** The body of a `<script>` or `<style>`, which is code to the browser rather than text. */
    public fun code(
        start: Int,
        end: Int,
    ): Unit = Unit

    /**
     * A span outside the data state that is not a tag: a comment, a bogus comment, a CDATA section,
     * or the body of a raw-text element, code included, to where it ends or to the end of the markup.
     */
    public fun opaque(
        start: Int,
        end: Int,
    ): Unit = Unit

    /** An [opaque] span that begins at its own `<`: a comment, a CDATA section or a bogus comment. */
    public fun declaration(
        start: Int,
        end: Int,
    ): Unit = opaque(start, end)
}

/**
 * Walks [html] as the browser parses a patch, with scripting off, and reports it to [visitor]: the
 * one reading of markup the editors' checks and their caret share. With [templates], the comments
 * and tags of a template language are skipped as the template engine removes them before the browser
 * sees the page, and so are the template constructs inside a tag: `{# #}`, `{{! }}`, `<#-- -->`,
 * `<%-- --%>`, `<#if>`, `<@macro/>`, `<div {{#if x}}hidden{{/if}}>`.
 */
@InternalStreamlordApi
public fun walkMarkup(
    html: String,
    visitor: MarkupVisitor,
    templates: Boolean = false,
) {
    scanMarkupTracked(html, visitor, NS_HTML, scripting = false, htmlStaysOpen = true, templates = templates)
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
 * caller that has to be right walks it each way and takes the strictest answer. With [scripting],
 * a `noscript` is text, as a page the browser loads reads it, rather than markup, as the client
 * reads a patch. With [tracked], it follows the element stack instead (see scanMarkupTracked),
 * starting inside an element of the [context] namespace, and, without [htmlStaysOpen], letting no
 * HTML element opened straight from that context stay open, and with [templates] as well, it skips
 * the syntax of a template language as [walkMarkup] does.
 *
 * @return Whether the markup ends in the data state, with no tag, attribute value, comment, CDATA
 *   section or raw-text body left open, and so whether markup written after it is read as written.
 *   A [tracked] walk asks more; see scanMarkupTracked.
 */
internal fun scanMarkup(
    html: String,
    visitor: MarkupVisitor,
    foreign: Boolean = false,
    cdata: Boolean = false,
    tracked: Boolean = false,
    scripting: Boolean = false,
    context: Int = NS_HTML,
    htmlStaysOpen: Boolean = true,
    templates: Boolean = false,
): Boolean {
    if (tracked) return scanMarkupTracked(html, visitor, context, scripting, htmlStaysOpen, templates)
    val rawText =
        if (foreign) {
            CODE_TEXT
        } else if (scripting) {
            SCRIPTING_RAW_TEXT
        } else {
            RAW_TEXT
        }
    var i = 0
    val n = html.length
    while (i < n) {
        val lt = html.indexOf('<', i)
        if (lt < 0) break
        if (html.startsWith("<!--", lt)) {
            val close = commentClose(html, lt)
            visitor.declaration(lt, if (close < 0) n else close)
            if (close < 0) return false
            i = close
            continue
        }
        if (cdata && html.startsWith("<![CDATA[", lt)) {
            val close = html.indexOf("]]>", lt + 9)
            visitor.declaration(lt, if (close < 0) n else close + 3)
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
            visitor.declaration(lt, if (close < 0) n else close + 1)
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
        visitor.opaque(i, if (close < 0) n else close)
        if (close < 0) return false
        i = close
    }
    return true
}

internal const val NS_HTML = 0
internal const val NS_SVG = 1
internal const val NS_MATH = 2

/** SVG elements whose content the browser parses as HTML (an HTML integration point). */
private val SVG_HTML_INTEGRATION = setOf("foreignobject", "desc", "title")

/** MathML elements whose content is HTML text (a MathML text integration point). */
private val MATH_TEXT_INTEGRATION = setOf("mi", "mo", "mn", "ms", "mtext")

/** The two MathML elements that stay MathML inside a text integration point. */
private val MATH_IN_TEXT = setOf("mglyph", "malignmark")

/** The attributes that make a `<font>` in foreign content return to HTML, as the tags of [EXITS_FOREIGN] do. */
private val FONT_EXITS = setOf("color", "face", "size")

/** HTML elements the browser never leaves open: it pops each as it inserts it. */
private val VOID =
    setOf(
        "area",
        "base",
        "basefont",
        "bgsound",
        "br",
        "col",
        "embed",
        "frame",
        "hr",
        "image",
        "img",
        "input",
        "keygen",
        "link",
        "meta",
        "param",
        "source",
        "track",
        "wbr",
    )

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
 * [context] is the namespace of the element the markup is parsed inside, as a patch in the svg
 * namespace is parsed inside an `<svg>`: no end tag closes it, and an exiting tag only steps out
 * into HTML until the element it opened is closed again.
 *
 * It is not a parser: it keeps only each open element's namespace and whether its content is HTML,
 * enough to know the context. An end tag closes the nearest open element of its name, unless a
 * special element stands nearer for one the tree builder has no rule of its own for, and a
 * formatting element's leaves the special elements opened inside it open, as the adoption agency does.
 *
 * Of the tree builder's implied end tags it knows those by which a `<p>` closes the `<p>` before it,
 * a `<div>` closes an open `<p>` and an `<li>` the `<li>` before it, but not the table modes'. Inside
 * an svg or a math that matters, since once the HTML element opened from the context is closed, the
 * tags after it are svg or math again. So it is walked both ways: with [htmlStaysOpen], every HTML element stays
 * open to its end tag, and without, none opened straight from a foreign [context] does, which is
 * where the browser's stack is when every such element has been closed by the next.
 *
 * @return Whether the markup ends in the data state, leaves open no element that changes how the
 *   markup after it is read (one of another namespace than [context], an integration point, a
 *   `template` or a `select`), and has no end tag for an element it did not open, which would close
 *   one of the markup around it. Without [htmlStaysOpen], an end tag for an HTML element it never
 *   kept open does not count as such, since the walk with it judges that.
 */
private fun scanMarkupTracked(
    html: String,
    visitor: MarkupVisitor,
    context: Int,
    scripting: Boolean,
    htmlStaysOpen: Boolean,
    templates: Boolean = false,
): Boolean {
    val rawText = if (scripting) SCRIPTING_RAW_TEXT else RAW_TEXT
    val stackNs = ArrayList<Int>()
    val stackName = ArrayList<String>()
    val stackHtmlContent = ArrayList<Boolean>() // Whether this element's children are HTML (an integration point).
    // How many elements of each name are open, so an end tag that matches none costs nothing.
    val open = HashMap<String, Int>()
    // How many special elements are open, so an end tag for none open knows whether one would stop it.
    var specialOpen = 0
    // How many `html`, `head` and `body` start tags are open: the tree builder drops them, but the text after one is inside it.
    var documentOpen = 0
    var stray = false
    // An svg or math `script` or `style` holds elements, but the text in it is still a program,
    // so all of it, from the first one opened to the last one closed, is reported as code.
    var foreignCode = 0
    var foreignCodeStart = -1

    fun push(
        ns: Int,
        name: String,
        htmlContent: Boolean,
        at: Int,
    ) {
        stackNs += ns
        stackName += name
        stackHtmlContent += htmlContent
        open[name] = (open[name] ?: 0) + 1
        if (if (ns == NS_HTML) name in SPECIAL else htmlContent || name == "annotation-xml") specialOpen++
        if (ns != NS_HTML && name in CODE_TEXT && foreignCode++ == 0) foreignCodeStart = at
    }

    fun pop(at: Int) {
        val name = stackName.removeAt(stackName.size - 1)
        val ns = stackNs.removeAt(stackNs.size - 1)
        val htmlContent = stackHtmlContent.removeAt(stackHtmlContent.size - 1)
        open[name] = open.getValue(name) - 1
        if (if (ns == NS_HTML) name in SPECIAL else htmlContent || name == "annotation-xml") specialOpen--
        if (ns != NS_HTML && name in CODE_TEXT && --foreignCode == 0) visitor.code(foreignCodeStart, at)
    }

    // A comment in a foreign script is no part of its program.
    fun skipCode(
        from: Int,
        to: Int,
    ) {
        if (foreignCode == 0) return
        visitor.code(foreignCodeStart, from)
        foreignCodeStart = to
    }

    // The namespace new content is inserted into, and whether a `<![CDATA[` here is a section.
    fun insertionNs(): Int =
        if (stackNs.isEmpty()) {
            context
        } else if (stackHtmlContent.last()) {
            NS_HTML
        } else {
            stackNs.last()
        }

    // Whether the open element at [k] bounds a search in button scope, or is special to the list item loop.
    fun boundsScope(k: Int): Boolean =
        if (stackNs[k] == NS_HTML) stackName[k] in BUTTON_SCOPE else stackHtmlContent[k] || stackName[k] == "annotation-xml"

    fun special(k: Int): Boolean = if (stackNs[k] == NS_HTML) stackName[k] in SPECIAL else boundsScope(k)

    fun popTo(
        k: Int,
        at: Int,
    ) {
        while (stackName.size > k) pop(at)
    }

    // Takes the HTML element at [k] off the stack and leaves the elements above it open.
    fun removeAt(k: Int) {
        val name = stackName.removeAt(k)
        stackNs.removeAt(k)
        stackHtmlContent.removeAt(k)
        open[name] = open.getValue(name) - 1
        if (name in SPECIAL) specialOpen--
    }

    // The end tags the tree builder implies before it inserts the HTML element [name]: a `p` in
    // button scope for the elements that close one, an `li`, `dd` or `dt` for the next of its kind,
    // a heading for a heading, an `option` for an `option` or an `optgroup`.
    fun closeImplied(
        name: String,
        at: Int,
    ) {
        val items =
            when (name) {
                "li" -> LIST_ITEM
                "dd", "dt" -> DEFINITION_ITEMS
                else -> null
            }
        if (items != null) {
            for (k in stackName.indices.reversed()) {
                if (stackNs[k] == NS_HTML && stackName[k] in items) {
                    popTo(k, at)
                    break
                }
                if (special(k) && !(stackNs[k] == NS_HTML && stackName[k] in LIST_LOOP_PASSES)) break
            }
        }
        if (items != null || name in CLOSES_P) {
            for (k in stackName.indices.reversed()) {
                if (stackNs[k] == NS_HTML && stackName[k] == "p") {
                    popTo(k, at)
                    break
                }
                if (boundsScope(k)) break
            }
        }
        val top = stackName.lastOrNull()
        val topHtml = stackNs.lastOrNull() == NS_HTML
        if (name in HEADINGS && topHtml && top in HEADINGS) pop(at)
        if ((name == "option" || name == "optgroup") && topHtml && top == "option") pop(at)
    }

    // The tree builder's rule in body for an end tag it has no rule of its own for: the nearest open
    // HTML element of the name, unless a special one stands nearer, which leaves it closing nothing.
    // The end tags it has a rule for, `</ul>`, `</p>`, `</b>`, close the nearest open element of the name.
    fun htmlEndTagTarget(name: String): Int {
        if ((open[name] ?: 0) == 0) return if (name in OWN_END_TAG_RULE || specialOpen == 0) NOT_OPEN else BLOCKED
        for (k in stackName.indices.reversed()) {
            if (stackNs[k] == NS_HTML && stackName[k] == name) return k
            if (name !in OWN_END_TAG_RULE && special(k)) return BLOCKED
        }
        return NOT_OPEN
    }

    // Where an end tag closes to, or BLOCKED or NOT_OPEN. In foreign content, the nearest open element
    // of the name, until an HTML element is open, whose rule takes over from there.
    fun endTagTarget(name: String): Int {
        if (stackName.isEmpty() || stackNs.last() == NS_HTML) return htmlEndTagTarget(name)
        if ((open[name] ?: 0) == 0) return NOT_OPEN
        for (k in stackName.indices.reversed()) {
            if (stackName[k] == name) return k
            if (k > 0 && stackNs[k - 1] == NS_HTML) return htmlEndTagTarget(name)
        }
        return NOT_OPEN
    }

    val n = html.length

    // Where the text since the last tag, comment or raw-text body began.
    var textStart = 0

    fun text(to: Int) {
        if (to > textStart) visitor.text(textStart, to, stackName.size + documentOpen)
    }

    fun walk(): Boolean {
        var i = 0
        while (i < n) {
            val lt = if (templates) nextTagOrBrace(html, i) else html.indexOf('<', i)
            if (lt < 0) break
            if (html[lt] == '{') {
                val end = templateCommentEnd(html, lt)
                if (end < 0) {
                    i = lt + 1
                    continue
                }
                text(lt)
                visitor.declaration(lt, end)
                i = end
                textStart = i
                continue
            }
            val foreignNow = insertionNs() != NS_HTML
            if (html.startsWith("<!--", lt)) {
                val close = commentClose(html, lt)
                text(lt)
                visitor.declaration(lt, if (close < 0) n else close)
                skipCode(lt, if (close < 0) n else close)
                if (close < 0) return false
                i = close
                textStart = i
                continue
            }
            val templateTag = if (templates) templateTagAt(html, lt) else null
            if (templateTag != null) {
                val (opener, closer) = templateTag
                val close = html.indexOf(closer, lt + opener.length)
                text(lt)
                visitor.declaration(lt, if (close < 0) n else close + closer.length)
                if (close < 0) return false
                i = close + closer.length
                textStart = i
                continue
            }
            if (foreignNow && html.startsWith("<![CDATA[", lt)) {
                val close = html.indexOf("]]>", lt + 9)
                text(lt)
                visitor.declaration(lt, if (close < 0) n else close + 3)
                if (close < 0) return false
                i = close + 3
                textStart = i
                continue
            }
            val first =
                html.getOrNull(lt + 1) ?: run {
                    text(n)
                    return false
                }
            if (first == '!' || first == '?' ||
                (first == '/' && !html.getOrNull(lt + 2).isAsciiLetter() && html.getOrNull(lt + 2) != '>')
            ) {
                val close = html.indexOf('>', lt + 2)
                text(lt)
                visitor.declaration(lt, if (close < 0) n else close + 1)
                skipCode(lt, if (close < 0) n else close + 1)
                if (close < 0) return false
                i = close + 1
                textStart = i
                continue
            }
            if (first == '/' && html.getOrNull(lt + 2).isAsciiLetter()) {
                text(lt)
                val end = scanTag(html, lt + 2, Discard, templates)
                visitor.tag(lt, if (end < 0) n else end)
                val name = tagName(html, lt + 2)
                visitor.endTag(name, lt, if (end < 0) n else end)
                if (end < 0) return false
                // A `</p>` or `</br>` in foreign content closes the foreign elements, as an exiting tag does.
                if (foreignNow && (name == "p" || name == "br")) {
                    while (stackNs.isNotEmpty() && stackNs.last() != NS_HTML && !stackHtmlContent.last()) pop(lt)
                }
                if (name in IGNORED_IN_BODY && documentOpen > 0) documentOpen--
                val at = if (name == "br") -1 else endTagTarget(name)
                if (at >= 0 && name in FORMATTING && (at + 1 until stackName.size).any { special(it) }) {
                    // The adoption agency closes the formatting element and keeps the special one opened inside it open.
                    removeAt(at)
                } else if (at >= 0) {
                    popTo(at, lt)
                } else if (at == NOT_OPEN && htmlStaysOpen) {
                    stray = true
                }
                i = end
                textStart = i
                continue
            }
            if (!first.isAsciiLetter()) {
                i = lt + 1
                continue
            }

            text(lt)
            val name = tagName(html, lt + 1)
            val snoop = EncodingSnoop(html, visitor, name)
            val end = scanTag(html, lt + 1, snoop, templates)
            visitor.tag(lt, if (end < 0) n else end)
            if (end < 0) {
                visitor.startTag(name, lt, n, selfClosing = false, rawText = false)
                return false
            }
            // A `/` that ends an unquoted value is part of the value, not a self-closing mark.
            val selfClosing = end >= 2 && html[end - 2] == '/' && snoop.unquotedEnd != end - 1
            i = end
            textStart = i

            // In foreign content, an HTML tag such as <p> or <img> closes the foreign elements first.
            val exits = foreignNow && (name in EXITS_FOREIGN || snoop.fontExits)
            if (exits) {
                while (stackNs.isNotEmpty() && stackNs.last() != NS_HTML && !stackHtmlContent.last()) pop(lt)
            }
            val mathInText =
                !exits && name in MATH_IN_TEXT && stackNs.isNotEmpty() && stackNs.last() == NS_MATH &&
                    stackName.last() in MATH_TEXT_INTEGRATION
            // Right inside an annotation-xml, an svg is an svg, which the tree builder inserts by its HTML rules.
            val svgInAnnotation =
                !exits && name == "svg" && stackNs.isNotEmpty() && stackNs.last() == NS_MATH && stackName.last() == "annotation-xml"
            val html5 = exits || svgInAnnotation || (insertionNs() == NS_HTML && !mathInText)
            val raw = if (html5) rawText.firstOrNull { it == name } else null
            visitor.startTag(name, lt, end, selfClosing, raw != null)

            if (html5) {
                if (name in IGNORED_IN_BODY) {
                    documentOpen++
                    continue
                }
                if (name in TABLE_PARTS && context != NS_HTML && (open["table"] ?: 0) == 0 && (open["template"] ?: 0) == 0) continue
                if (name != "svg" && name != "math") closeImplied(name, lt)
                // HTML raw-text and code elements swallow their body to their own close tag.
                if (raw != null) {
                    val close = if (raw == "plaintext") -1 else rawTextEnd(html, raw, i)
                    if (raw in CODE_TEXT && foreignCode == 0) visitor.code(i, if (close < 0) n else close)
                    visitor.opaque(i, if (close < 0) n else close)
                    if (close < 0) return false
                    // Open until the end tag the body ends at, which closes it.
                    push(NS_HTML, raw, false, i)
                    i = close
                    textStart = i
                    continue
                }
                // HTML ignores a self-closing mark on its own elements; only an svg or a math takes one.
                if (name in VOID || (selfClosing && (name == "svg" || name == "math"))) continue
                if (!htmlStaysOpen && stackNs.isEmpty() && context != NS_HTML && name != "svg" && name != "math") continue
                val ns =
                    if (name == "svg") {
                        NS_SVG
                    } else if (name == "math") {
                        NS_MATH
                    } else {
                        NS_HTML
                    }
                push(ns, name, false, i)
            } else {
                if (selfClosing) continue
                // In foreign content every tag takes the namespace it stands in, an `<svg>` in a math
                // included; only HTML content starts a new one.
                val ns = if (stackNs.isEmpty()) context else stackNs.last()
                val integration =
                    (ns == NS_SVG && name in SVG_HTML_INTEGRATION) ||
                        (ns == NS_MATH && name in MATH_TEXT_INTEGRATION) ||
                        (ns == NS_MATH && name == "annotation-xml" && snoop.value?.asciiLowercase() in HTML_ANNOTATION_ENCODINGS)
                push(ns, name, integration, i)
            }
        }
        text(n)
        return true
    }

    val ended = walk()
    if (foreignCode > 0) visitor.code(foreignCodeStart, n)
    return ended && !stray && foreignCode == 0 &&
        stackName.indices.all { stackNs[it] == context && !stackHtmlContent[it] && stackName[it] !in CHANGES_MODE }
}

/**
 * The end tags the tree builder in body has a rule of its own for, which closes the nearest open
 * element of the name rather than stopping at a special element, and those of the table and select
 * modes, which the walk reads as in body.
 */
private val OWN_END_TAG_RULE =
    setOf(
        "address",
        "article",
        "aside",
        "blockquote",
        "button",
        "center",
        "details",
        "dialog",
        "dir",
        "div",
        "dl",
        "fieldset",
        "figcaption",
        "figure",
        "footer",
        "header",
        "hgroup",
        "listing",
        "main",
        "menu",
        "nav",
        "ol",
        "pre",
        "search",
        "section",
        "summary",
        "ul",
        "form",
        "p",
        "li",
        "dd",
        "dt",
        "h1",
        "h2",
        "h3",
        "h4",
        "h5",
        "h6",
        "a",
        "b",
        "big",
        "code",
        "em",
        "font",
        "i",
        "nobr",
        "s",
        "small",
        "strike",
        "strong",
        "tt",
        "u",
        "applet",
        "marquee",
        "object",
        "template",
        "body",
        "html",
        "table",
        "caption",
        "colgroup",
        "tbody",
        "tfoot",
        "thead",
        "tr",
        "td",
        "th",
        "select",
        "option",
        "optgroup",
    )

/** The formatting elements, whose end tag the adoption agency handles. */
private val FORMATTING = setOf("a", "b", "big", "code", "em", "font", "i", "nobr", "s", "small", "strike", "strong", "tt", "u")

/** An end tag a special element stops before it reaches an open element of its name. */
private const val BLOCKED = -1

/** An end tag for no element open in the markup, which may close one around it. */
private const val NOT_OPEN = -2

/** HTML start tags the tree builder drops in body: a patch is parsed inside one already. */
private val IGNORED_IN_BODY = setOf("html", "head", "body", "frameset")

/** The parts of a table, which the tree builder drops in body outside a `table` or a `template`. */
private val TABLE_PARTS = setOf("caption", "col", "colgroup", "tbody", "td", "tfoot", "th", "thead", "tr")

private val HEADINGS = setOf("h1", "h2", "h3", "h4", "h5", "h6")

/**
 * HTML start tags that close a `p` open in button scope. A `table` does too in a document without
 * quirks, but a patch's own document has none, so it is left out: the reading keeps the `p` open.
 */
private val CLOSES_P =
    setOf(
        "address",
        "article",
        "aside",
        "blockquote",
        "center",
        "details",
        "dialog",
        "dir",
        "div",
        "dl",
        "fieldset",
        "figcaption",
        "figure",
        "footer",
        "header",
        "hgroup",
        "main",
        "menu",
        "nav",
        "ol",
        "p",
        "search",
        "section",
        "summary",
        "ul",
        "pre",
        "listing",
        "form",
        "plaintext",
        "xmp",
        "hr",
    ) + HEADINGS

private val LIST_ITEM = setOf("li")
private val DEFINITION_ITEMS = setOf("dd", "dt")

/** The special elements the list item loop walks past. */
private val LIST_LOOP_PASSES = setOf("address", "div", "p")

/** The HTML elements that bound a search for an element in button scope. */
private val BUTTON_SCOPE = setOf("applet", "caption", "html", "table", "td", "th", "marquee", "object", "template", "button")

/** The HTML elements of the tree builder's special category. */
private val SPECIAL =
    setOf(
        "address",
        "applet",
        "area",
        "article",
        "aside",
        "base",
        "basefont",
        "bgsound",
        "blockquote",
        "body",
        "br",
        "button",
        "caption",
        "center",
        "col",
        "colgroup",
        "dd",
        "details",
        "dir",
        "div",
        "dl",
        "dt",
        "embed",
        "fieldset",
        "figcaption",
        "figure",
        "footer",
        "form",
        "frame",
        "frameset",
        "h1",
        "h2",
        "h3",
        "h4",
        "h5",
        "h6",
        "head",
        "header",
        "hgroup",
        "hr",
        "html",
        "iframe",
        "img",
        "input",
        "keygen",
        "li",
        "link",
        "listing",
        "main",
        "marquee",
        "menu",
        "meta",
        "nav",
        "noembed",
        "noframes",
        "noscript",
        "object",
        "ol",
        "p",
        "param",
        "plaintext",
        "pre",
        "script",
        "search",
        "section",
        "select",
        "source",
        "style",
        "summary",
        "table",
        "tbody",
        "td",
        "template",
        "textarea",
        "tfoot",
        "th",
        "thead",
        "title",
        "tr",
        "track",
        "ul",
        "wbr",
        "xmp",
    )

/**
 * HTML elements that, left open, change what the tree builder does with the tags after them: a
 * `template` takes its own insertion mode, and a `select` drops most tags.
 */
private val CHANGES_MODE = setOf("template", "select")

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
 * Records attributes to [delegate] and keeps what the tree builder asks of them: for an
 * `annotation-xml`, its `encoding` value (the first one, since the tokenizer drops a repeated
 * attribute), for a `font`, whether it has one of [FONT_EXITS], and where the last unquoted value ended.
 */
private class EncodingSnoop(
    private val markup: String,
    private val delegate: MarkupVisitor,
    private val tagName: String,
) : MarkupVisitor {
    var value: String? = null
    var fontExits = false
    var unquotedEnd = -1

    override fun attribute(
        tag: String,
        name: String,
        nameStart: Int,
        valueStart: Int,
        valueEnd: Int,
        quoted: Boolean,
    ) {
        if (tagName == "annotation-xml" && value == null && name.equals("encoding", ignoreCase = true) && valueStart >= 0) {
            value = markup.substring(valueStart, valueEnd)
        }
        if (tagName == "font" && name.asciiLowercase() in FONT_EXITS) fontExits = true
        if (!quoted && valueStart >= 0) unquotedEnd = valueEnd
        delegate.attribute(tag, name, nameStart, valueStart, valueEnd, quoted)
    }

    override fun tag(
        start: Int,
        end: Int,
    ) = delegate.tag(start, end)

    override fun code(
        start: Int,
        end: Int,
    ) = delegate.code(start, end)

    override fun opaque(
        start: Int,
        end: Int,
    ) = delegate.opaque(start, end)
}

/** A visitor that wants nothing: for the attributes of an end tag, and for a walk asked only whether markup closes. */
internal object Discard : MarkupVisitor {
    override fun attribute(
        tag: String,
        name: String,
        nameStart: Int,
        valueStart: Int,
        valueEnd: Int,
        quoted: Boolean,
    ) = Unit
}

/**
 * Walks the attributes of one tag, starting after `<` or `</`; returns the offset past `>`, or -1
 * when the markup ends first. With [templates], a template construct between the attributes is
 * skipped, and one that starts in a name ends it: `data-x{{/if}}`.
 */
private fun scanTag(
    html: String,
    from: Int,
    visitor: MarkupVisitor,
    templates: Boolean = false,
): Int {
    var i = from
    val n = html.length
    while (i < n && !html[i].isHtmlSpace() && html[i] != '>' && html[i] != '/') i++
    val tag = html.substring(from, i)
    while (i < n) {
        val c = html[i]
        val construct = if (templates) templateConstructEnd(html, i) else i
        when {
            construct > i -> {
                i = construct
            }

            templates && (c == '{' || c == '<') -> {
                i++
            }

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
                while (i < n && !html[i].isHtmlSpace() && html[i] != '>' && html[i] != '=' && html[i] != '/' &&
                    !(templates && (html[i] == '{' || html[i] == '<'))
                ) {
                    i++
                }
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
                        visitor.attribute(tag, name, nameStart, k + 1, end, quoted = true)
                        i = if (close < 0) n else close + 1
                    } else {
                        val start = k
                        while (k < n && !html[k].isHtmlSpace() && html[k] != '>') k++
                        visitor.attribute(tag, name, nameStart, start, k, quoted = false)
                        i = k
                    }
                } else {
                    visitor.attribute(tag, name, nameStart, -1, -1, quoted = false)
                }
            }
        }
    }
    return -1
}

/** Where the next `<` or `{` at or after [from] is, or -1. */
private fun nextTagOrBrace(
    html: String,
    from: Int,
): Int {
    for (i in from until html.length) if (html[i] == '<' || html[i] == '{') return i
    return -1
}

/**
 * Where a Pebble or Twig comment, or a Mustache or Handlebars one, that starts at [at] ends, or -1
 * where none does: one is skipped only when it closes, because `{#` also opens a block in other
 * template languages.
 */
private fun templateCommentEnd(
    html: String,
    at: Int,
): Int {
    val close =
        when {
            html.startsWith("{#", at) -> "#}"
            html.startsWith("{{!--", at) -> "--}}"
            html.startsWith("{{!", at) -> "}}"
            else -> return -1
        }
    val end = html.indexOf(close, at + 2)
    return if (end < 0) -1 else end + close.length
}

/**
 * The opening and the closing of a template tag at [at]: a FreeMarker comment, which only `-->`
 * ends, a JSP comment, and FreeMarker's directives and macro calls (`<#if>`, `</#if>`, `<@row/>`).
 */
private fun templateTagAt(
    html: String,
    at: Int,
): Pair<String, String>? =
    when {
        html.startsWith("<#--", at) -> "<#--" to "-->"
        html.startsWith("<%--", at) -> "<%--" to "--%>"
        html.startsWith("<#", at) || html.startsWith("<@", at) -> "<#" to ">"
        html.startsWith("</#", at) || html.startsWith("</@", at) -> "</#" to ">"
        else -> null
    }

private val TEMPLATE_CALL = Regex("""^[@#][A-Za-z]+\s*\(""")

/**
 * Where a template construct that starts at [at] inside a tag ends, or [at] when none starts
 * there: `<% %>`, `{{ }}`, `{% %}`, `{# #}`, a FreeMarker directive, or a JTE or Velocity
 * directive with its parenthesised condition. Its `>` does not end the tag.
 */
@InternalStreamlordApi
public fun templateConstructEnd(
    html: String,
    at: Int,
): Int {
    fun after(
        close: String,
        from: Int,
    ): Int = html.indexOf(close, from).let { if (it < 0) at else it + close.length }
    return when {
        html.startsWith("<%", at) -> {
            after("%>", at + 2)
        }

        html.startsWith("{{", at) -> {
            after("}}", at + 2)
        }

        html.startsWith("{%", at) -> {
            after("%}", at + 2)
        }

        html.startsWith("{#", at) -> {
            after("#}", at + 2)
        }

        html.startsWith("<#", at) || html.startsWith("</#", at) || html.startsWith("<@", at) -> {
            after(">", at + 2)
        }

        else -> {
            val call = TEMPLATE_CALL.find(html.substring(at, minOf(html.length, at + 32))) ?: return at
            var depth = 0
            for (i in at + call.value.length - 1 until html.length) {
                if (html[i] == '(') {
                    depth++
                } else if (html[i] == ')' && --depth == 0) {
                    return i + 1
                }
            }
            at
        }
    }
}
