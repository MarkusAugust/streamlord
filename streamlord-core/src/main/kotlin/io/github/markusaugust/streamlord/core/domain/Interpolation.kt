package io.github.markusaugust.streamlord.core.domain

import io.github.markusaugust.streamlord.core.StreamlordException

/**
 * Markup the caller vouches for, written into the output as it stands.
 *
 * [interpolate] escapes every value it is given and refuses the positions escaping cannot make
 * safe. Wrapping a value here waives both, which is the right thing for a fragment you rendered
 * yourself and the wrong thing for anything that came from a request. The type exists so that
 * waiver is a word in the source someone can search for.
 *
 * ```kotlin
 * interpolate("""<li>%s</li>""", Trusted(renderBadge(user)))
 * ```
 */
@JvmInline
public value class Trusted(
    public val html: String,
)

/** Where a value landed, which is what decides whether it can be made safe. */
public enum class Position {
    /** Element text. Escaping is enough. */
    TEXT,

    /** A quoted attribute value that is not Datastar's. Escaping is enough. */
    ATTRIBUTE,

    /** A `data-*` attribute Datastar reads. The value would be part of a Datastar expression. */
    DATASTAR,

    /** A tag name, an attribute name, or an unquoted value: the shape of the markup itself. */
    STRUCTURE,

    /** The body of a `<script>` or `<style>`, which the browser runs rather than displays. */
    CODE,
}

/**
 * Raised when a value would land somewhere escaping cannot make safe.
 *
 * @property position Where it landed.
 * @property index Which value, counting the holes from zero.
 * @property attribute The attribute it landed in, for [Position.DATASTAR].
 */
public class UnsafeInterpolationException(
    public val position: Position,
    public val index: Int,
    public val attribute: String? = null,
) : StreamlordException(message(position, index, attribute)) {
    private companion object {
        fun message(
            position: Position,
            index: Int,
            attribute: String?,
        ): String {
            val where =
                when (position) {
                    Position.DATASTAR -> "inside $attribute, which Datastar reads as an expression"
                    Position.STRUCTURE -> "in the markup's own structure: a tag name, an attribute name, or an unquoted value"
                    Position.CODE -> "inside a script or style element, where the browser runs it"
                    else -> "in a position that cannot be escaped"
                }
            val fix =
                when (position) {
                    Position.DATASTAR -> {
                        "Put the value in a signal and reference it from the expression, or pass a Number or Boolean, " +
                            "which cannot carry an expression."
                    }

                    else -> {
                        "Build this part of the markup from literals."
                    }
                }
            return "Value $index would land $where, so escaping it would not make it safe. $fix " +
                "If you wrote the value yourself and mean it to be markup, wrap it in Trusted(...)."
        }
    }
}

/**
 * Writes [values] into [format] at its `%s` holes, escaped, and refuses any hole that landed
 * somewhere escaping cannot make safe.
 *
 * The question is not where a value came from, which Streamlord cannot know by the time it is
 * handed a string. It is where the value lands, which is a fact about [format] alone: Kotlin has
 * already finished with the literal, so every `%s` in it is a hole the caller left on purpose and
 * everything around it is markup the caller wrote. That is enough to say, of each value, whether
 * it becomes text, an attribute, part of a Datastar expression, or the shape of the markup.
 *
 * ```kotlin
 * interpolate("""<li class="row">%s</li>""", name)          // escaped into the text
 * interpolate("""<li title="%s">x</li>""", name)            // escaped into the attribute
 * interpolate("""<li data-text="%s"></li>""", name)         // throws: a Datastar expression
 * interpolate("""<li data-signals="{n: %s}"></li>""", 3)    // a Number cannot carry one, so it passes
 * interpolate("""<li %s="x"></li>""", name)                 // throws: an attribute name
 * ```
 *
 * `%%` writes one `%`. A `%` followed by anything else is literal, so CSS and escaped URLs need
 * no ceremony; a hole is only ever `%s`, and a miscounted one fails here rather than quietly.
 *
 * This is the rule the editors enforce as you type, where they can see the Kotlin source and its
 * `${'$'}{...}`. At render time the source is gone, so the holes have to be left standing. That is
 * the whole of what this function asks of you.
 *
 * @throws UnsafeInterpolationException if a value landed where escaping cannot help.
 * @throws IllegalArgumentException if the number of values does not match the number of holes.
 */
public fun interpolate(
    format: String,
    vararg values: Any?,
): String = interpolate(format, values.asList(), ElementsGuard.defaultPrefixes)

/**
 * [interpolate] with the values as a list, and the `data-*` prefixes to recognise.
 *
 * Pass [prefixes] when your Datastar bundle is aliased to something other than `data-` or
 * `data-star-`; the attributes of an alias this function does not know are attributes it cannot
 * refuse a value in.
 */
public fun interpolate(
    format: String,
    values: List<Any?>,
    prefixes: List<String> = ElementsGuard.defaultPrefixes,
): String {
    val holes = holes(format)
    require(holes.size == values.size) {
        "The markup has ${holes.size} %s ${if (holes.size == 1) "hole" else "holes"} and ${values.size} " +
            "${if (values.size == 1) "value" else "values"} were given"
    }
    if (holes.isEmpty()) return format.replace("%%", "%")

    val landscape = Landscape(prefixes)
    scanMarkup(shadow(format, holes), landscape)

    val out = StringBuilder(format.length + values.size * 16)
    var cut = 0
    for ((index, hole) in holes.withIndex()) {
        out.literal(format, cut, hole)
        cut = hole + 2

        when (val value = values[index]) {
            is Trusted -> {
                out.append(value.html)
            }

            else -> {
                val position = landscape.positionOf(hole)
                if (!position.admits(value)) {
                    throw UnsafeInterpolationException(position, index, landscape.attributeAt(hole))
                }
                out.escaped(value)
            }
        }
    }
    out.literal(format, cut, format.length)
    return out.toString()
}

/**
 * A span of the markup itself, with `%%` collapsed to one `%`.
 *
 * Only the literal spans pass through here. A value that happens to contain `%%` is data, and
 * data is never read back as markup.
 */
private fun StringBuilder.literal(
    format: String,
    from: Int,
    to: Int,
) {
    var i = from
    while (i < to) {
        val ch = format[i]
        if (ch == '%' && i + 1 < to && format[i + 1] == '%') {
            append('%')
            i += 2
        } else {
            append(ch)
            i++
        }
    }
}

/**
 * Whether a plain value can be made safe here.
 *
 * A number or a boolean is admitted into a Datastar attribute because its text is digits, a dot,
 * a sign, or `true`/`false`: there is no string that renders that way and also closes a quote or
 * calls a function, so the common honest case, `data-signals="{count: %s}"`, needs no waiver.
 */
private fun Position.admits(value: Any?): Boolean =
    when (this) {
        Position.TEXT, Position.ATTRIBUTE -> true
        Position.DATASTAR -> value is Number || value is Boolean
        Position.STRUCTURE, Position.CODE -> false
    }

private fun StringBuilder.escaped(value: Any?) {
    val text = value?.toString() ?: ""
    for (ch in text) {
        when (ch) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&#39;")
            else -> append(ch)
        }
    }
}

/**
 * [format] with every hole replaced by two letters, which keeps every offset where it was.
 *
 * The walk has to see the markup as the browser will once the values are in place. `<%s>` is not
 * a tag to a scanner that wants a letter after the `<`, and a hole nobody reads as a tag name is
 * a hole nobody refuses: the value would become the tag. With a name in its place the span is a
 * tag, and the hole inside it is structure.
 */
private fun shadow(
    format: String,
    holes: List<Int>,
): String {
    val chars = format.toCharArray()
    for (hole in holes) {
        chars[hole] = 'd'
        chars[hole + 1] = 's'
    }
    return String(chars)
}

/** The offset of each `%s`, in order. `%%` is one escaped per cent and never a hole. */
private fun holes(format: String): List<Int> {
    val found = ArrayList<Int>()
    var i = 0
    while (i < format.length - 1) {
        if (format[i] == '%') {
            when (format[i + 1]) {
                's' -> {
                    found += i
                    i += 2
                    continue
                }

                '%' -> {
                    i += 2
                    continue
                }
            }
        }
        i++
    }
    return found
}

/**
 * The shape of the markup around the holes: which spans are quoted attribute values, which are
 * inside a tag, and which are the body of a script or a style.
 */
private class Landscape(
    private val prefixes: List<String>,
) : MarkupVisitor {
    private val attributeNames = ArrayList<String>()
    private val attributeStarts = ArrayList<Int>()
    private val attributeEnds = ArrayList<Int>()
    private val tagStarts = ArrayList<Int>()
    private val tagEnds = ArrayList<Int>()
    private val codeStarts = ArrayList<Int>()
    private val codeEnds = ArrayList<Int>()

    override fun attribute(
        name: String,
        valueStart: Int,
        valueEnd: Int,
        quoted: Boolean,
    ) {
        // An unquoted value ends at the first space, so a value written into one can add
        // attributes beside it. It is left to the tag spans, which refuse.
        if (!quoted || valueStart < 0) return
        attributeNames += name
        attributeStarts += valueStart
        attributeEnds += valueEnd
    }

    override fun tag(
        start: Int,
        end: Int,
    ) {
        tagStarts += start
        tagEnds += end
    }

    override fun code(
        start: Int,
        end: Int,
    ) {
        codeStarts += start
        codeEnds += end
    }

    fun positionOf(hole: Int): Position {
        val attribute = indexOfSpan(hole, attributeStarts, attributeEnds)
        if (attribute >= 0) {
            return if (datastar(attributeNames[attribute])) Position.DATASTAR else Position.ATTRIBUTE
        }
        if (indexOfSpan(hole, tagStarts, tagEnds) >= 0) return Position.STRUCTURE
        if (indexOfSpan(hole, codeStarts, codeEnds) >= 0) return Position.CODE
        return Position.TEXT
    }

    fun attributeAt(hole: Int): String? = indexOfSpan(hole, attributeStarts, attributeEnds).takeIf { it >= 0 }?.let { attributeNames[it] }

    private fun datastar(name: String): Boolean {
        val lower = name.lowercase()
        val prefix = prefixes.firstOrNull { lower.startsWith(it) } ?: return false
        val rest = lower.substring(prefix.length)
        return rest.substringBefore(':').substringBefore("__") in ElementsGuard.attributes
    }

    private fun indexOfSpan(
        at: Int,
        starts: List<Int>,
        ends: List<Int>,
    ): Int {
        for (i in starts.indices) {
            if (at >= starts[i] && at < ends[i]) return i
        }
        return -1
    }
}
