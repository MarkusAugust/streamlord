package io.github.markusaugust.streamlord.core.domain

/**
 * What a walk over HTML reports. Offsets are into the string that was walked, so a caller can
 * ask where something sits as well as what it says.
 */
internal interface MarkupVisitor {
    /**
     * One attribute of one tag.
     *
     * @param valueStart First character of the value, or -1 for an attribute written without one.
     * @param valueEnd One past the last character of the value.
     * @param quoted Whether the value stood in quotes. An unquoted value ends at the first space,
     *   so anything written into one can add attributes of its own.
     */
    fun attribute(
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

/** Elements whose body is text to the browser: `<` inside them opens no tag. */
private val RAW_TEXT = listOf("script", "style", "textarea", "title")

/** Of those, the ones whose body is a program. */
private val CODE_TEXT = setOf("script", "style")

private fun Char?.isNamePart(): Boolean = this != null && (isLetterOrDigit() || this == '-')

/**
 * Walks the tags of [html] and reports them to [visitor].
 *
 * Not a parser: it never builds a tree and never copies the markup. It knows the three things
 * that decide what a span of output can do to the browser, which is where a tag begins and ends,
 * where each attribute value sits and whether it is quoted, and which element bodies are code.
 */
internal fun scanMarkup(
    html: String,
    visitor: MarkupVisitor,
) {
    var i = 0
    val n = html.length
    while (i < n) {
        val lt = html.indexOf('<', i)
        if (lt < 0) break
        if (html.startsWith("<!--", lt)) {
            val close = html.indexOf("-->", lt + 4)
            i = if (close < 0) n else close + 3
            continue
        }
        val first = html.getOrNull(lt + 1)
        if (first == null || !first.isLetter()) {
            i = lt + 1
            continue
        }

        i = scanTag(html, lt + 1, visitor)
        visitor.tag(lt, i)

        RAW_TEXT
            .firstOrNull {
                html.regionMatches(lt + 1, it, 0, it.length, ignoreCase = true) &&
                    !html.getOrNull(lt + 1 + it.length).isNamePart()
            }?.let { tag ->
                val close = html.indexOf("</$tag", i, ignoreCase = true)
                val end = if (close < 0) n else close
                if (tag in CODE_TEXT) visitor.code(i, end)
                i = end
            }
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
    while (i < n && !html[i].isWhitespace() && html[i] != '>' && html[i] != '/') i++
    while (i < n) {
        val c = html[i]
        when {
            c == '>' -> {
                return i + 1
            }

            c.isWhitespace() || c == '/' -> {
                i++
            }

            else -> {
                val nameStart = i
                while (i < n && !html[i].isWhitespace() && html[i] != '>' && html[i] != '=' && html[i] != '/') i++
                val name = html.substring(nameStart, i)
                var k = i
                while (k < n && html[k].isWhitespace()) k++
                if (k < n && html[k] == '=') {
                    k++
                    while (k < n && html[k].isWhitespace()) k++
                    val q = html.getOrNull(k)
                    if (q == '"' || q == '\'') {
                        val close = html.indexOf(q, k + 1)
                        val end = if (close < 0) n else close
                        visitor.attribute(name, k + 1, end, quoted = true)
                        i = if (close < 0) n else close + 1
                    } else {
                        val start = k
                        while (k < n && !html[k].isWhitespace() && html[k] != '>') k++
                        visitor.attribute(name, start, k, quoted = false)
                        i = k
                    }
                } else {
                    visitor.attribute(name, -1, -1, quoted = false)
                }
            }
        }
    }
    return n
}
