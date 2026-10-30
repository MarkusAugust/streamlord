package io.github.markusaugust.streamlord.core.domain

import io.github.markusaugust.streamlord.core.InternalStreamlordApi
import io.github.markusaugust.streamlord.core.StreamlordException
import io.github.markusaugust.streamlord.core.catalog.CatalogNames
import io.github.markusaugust.streamlord.core.client.asciiLowercase
import io.github.markusaugust.streamlord.core.client.decodeEntities
import org.intellij.lang.annotations.Language

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
) {
    /** The markup itself, so a Kotlin template that holds one writes the markup and not `Trusted(html=...)`. */
    override fun toString(): String = html
}

/**
 * Markup [interpolate] built: its own literals as written, every value escaped or refused.
 *
 * It can be handed on as markup, to `patchElements` or as a value in another [interpolate], where
 * it is written as it stands in element text and judged as the text it is anywhere else: inside an
 * attribute, markup is only characters, and they are escaped like any value. Only [interpolate]
 * makes one, so holding an [Html] says where it came from.
 */
@JvmInline
public value class Html
    @InternalStreamlordApi
    constructor(
        public val text: String,
    ) {
        /** The markup itself, so a Kotlin template that holds one writes the markup and not `Html(text=...)`. */
        override fun toString(): String = text
    }

/** Where a value landed, which is what decides whether it can be made safe. */
public enum class Position {
    /** Element text. Escaping is enough. */
    TEXT,

    /** A quoted attribute value that is not Datastar's and not one the browser acts on. Escaping is enough. */
    ATTRIBUTE,

    /**
     * An attribute that holds a URL, such as `href` or `src`. Escaping keeps the value inside the
     * attribute and does nothing about `javascript:`, so the scheme of the whole value is checked.
     */
    URL,

    /** A `style` attribute. Escaped CSS is still CSS, so only a number is let in. */
    STYLE,

    /**
     * A `data-*` attribute whose value Datastar evaluates, an expression or a signal filter
     * ([ElementsGuard.evaluatedAttributes]), or follows as a signal path: `data-bind`, `data-ref`
     * and `data-indicator`, where `__proto__.x` writes into every object of the page. An attribute
     * the client reads as it stands, `data-nonce` or `data-preserve-attr` among them, is an
     * ordinary one.
     */
    DATASTAR,

    /** A tag name, an attribute name, or an unquoted value: the shape of the markup itself. */
    STRUCTURE,

    /**
     * Something the browser runs rather than displays: the body of a `<script>` or `<style>`, an
     * event handler attribute such as `onclick`, or `srcdoc`, which is parsed as a document.
     */
    CODE,
}

/**
 * Raised when a value would land somewhere escaping cannot make safe.
 *
 * @property position Where it landed.
 * @property index Which value, counting the holes from zero.
 * @property attribute The attribute it landed in, when it landed in one.
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
                    Position.DATASTAR -> {
                        "inside $attribute, which Datastar reads as an expression or follows as a signal path"
                    }

                    Position.STRUCTURE -> {
                        "in the markup's own structure: a tag name, an attribute name, or an unquoted value"
                    }

                    Position.CODE -> {
                        if (attribute == null) {
                            "inside a script or style element, where the browser runs it"
                        } else {
                            "inside $attribute, which the browser runs"
                        }
                    }

                    Position.URL -> {
                        "in $attribute, making a URL with a scheme the browser may run, such as javascript:"
                    }

                    Position.STYLE -> {
                        "inside $attribute, where it would be read as CSS"
                    }

                    else -> {
                        "in a position that cannot be escaped"
                    }
                }
            val fix =
                when (position) {
                    Position.DATASTAR -> {
                        "Put the value in a signal and reference it from the expression, write a signal name in the " +
                            "markup yourself, or pass a Number or Boolean, which can carry neither an expression nor a path."
                    }

                    Position.URL -> {
                        "Pass a relative URL or one that starts with http, https, mailto or tel."
                    }

                    Position.STYLE -> {
                        "Pass a Number and write the unit in the markup, or set the style from a class."
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
 * interpolate("""<a href="%s">x</a>""", url)                // throws if url is javascript:...
 * interpolate("""<a onclick="%s">x</a>""", name)            // throws: the browser runs it
 * ```
 *
 * Three kinds of attribute are held to more than escaping, because the browser acts on what
 * they contain. An event handler (`on*`) and `srcdoc` are refused like a script body. `style`
 * takes a number only. A URL attribute (`href`, `src`, `action` and the like, and `values`,
 * `to` and `from` on SVG's animation elements) must come out, values and all, as a relative URL
 * or an `http`, `https`, `mailto` or `tel` one: `href="/heads/%s"` takes anything, and
 * `href="%s%s"` cannot be made into `javascript:` across its two holes.
 *
 * The markup is read the way the browser's tokenizer reads it, and both as HTML and as the
 * inside of an `<svg>` or `<math>`, where a `title` or a `textarea` holds elements rather than
 * text. A value has to be safe both ways, so markup whose namespace cannot be told, a patch with
 * an SVG namespace among it, is judged as either.
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
    @Language("HTML") format: String,
    vararg values: Any?,
): Html = interpolate(format, values.asList(), ElementsGuard.defaultPrefixes)

/**
 * [interpolate] with the values as a list, and the `data-*` prefixes to recognise.
 *
 * Pass [prefixes] when your Datastar bundle is aliased to something other than `data-` or
 * `data-star-`; the attributes of an alias this function does not know are attributes it cannot
 * refuse a value in.
 */
public fun interpolate(
    @Language("HTML") format: String,
    values: List<Any?>,
    prefixes: List<String> = ElementsGuard.defaultPrefixes,
): Html {
    val holes = holes(format)
    require(holes.size == values.size) {
        "The markup has ${holes.size} %s ${if (holes.size == 1) "hole" else "holes"} and ${values.size} " +
            "${if (values.size == 1) "value" else "values"} were given"
    }
    if (holes.isEmpty()) return Html(format.replace("%%", "%"))

    val landscapes = landscapes(format, holes.map { it until it + 2 }, prefixes)

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
                val landing = landingOf(landscapes, hole)
                // Markup interpolate built is markup in element text; anywhere else it is characters.
                if (value is Html && landing.position == Position.TEXT) {
                    out.append(value.text)
                } else {
                    if (!landing.position.admits(value)) throw UnsafeInterpolationException(landing.position, index, landing.attribute)
                    out.escaped(value)
                }
            }
        }
    }
    out.literal(format, cut, format.length)
    for (landscape in landscapes) landscape.checkUrls(format, holes, values)
    return Html(out.toString())
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
        // A URL is judged whole, once every value is in place; see Landscape.checkUrls.
        Position.TEXT, Position.ATTRIBUTE, Position.URL -> true

        Position.DATASTAR -> value is Number || value is Boolean

        Position.STYLE -> value is Number

        Position.STRUCTURE, Position.CODE -> false
    }

private val SCHEME = Regex("^([A-Za-z][A-Za-z0-9+.-]*):")
private val SAFE_SCHEMES = setOf("http", "https", "mailto", "tel")

/**
 * Whether a URL is relative or carries a scheme that only navigates. Read the way a browser
 * reads it: tabs and line breaks are dropped wherever they are and leading control characters
 * and spaces are skipped, which is how `java\tscript:` gets past a naive check.
 */
internal fun safeUrl(url: String): Boolean {
    val read = url.filterNot { it == '\t' || it == '\n' || it == '\r' }.trimStart { it <= ' ' }
    val scheme = SCHEME.find(read)?.groupValues?.get(1) ?: return true
    return scheme.asciiLowercase() in SAFE_SCHEMES
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
 * Where one hole in a piece of markup lands, as [interpolate] judges it.
 *
 * The editors judge a Kotlin template in an HTML string with this, so that a hole is refused or
 * let through by the same reading in the editor as at run time.
 *
 * @property position The strictest of the positions the hole takes in the three readings of the
 *   markup (HTML, svg or math, and svg or math with CDATA as text); see [interpolate].
 * @property attribute The attribute it landed in, when it landed in a quoted one.
 * @property valueStart Where that attribute's value starts in the markup, or -1.
 */
@InternalStreamlordApi
public class HoleLanding(
    public val position: Position,
    public val attribute: String?,
    public val valueStart: Int,
)

/**
 * Where each of [holes] lands in [markup]. A hole is any span the caller will fill: `%s` to
 * [interpolate], a Kotlin template to the editors. Each is read as a run of letters, so `<%s>` is
 * a tag whose name is the hole.
 */
@InternalStreamlordApi
public fun holeLandings(
    markup: String,
    holes: List<IntRange>,
    prefixes: List<String> = ElementsGuard.defaultPrefixes,
): List<HoleLanding> {
    val landscapes = landscapes(markup, holes, prefixes)
    return holes.map { landingOf(landscapes, it.first) }
}

/**
 * Whether the start of a URL attribute's value, the literal text before its first hole, already
 * settles the scheme, so that no value written after it can make the URL run: a `/`, `?` or `#`
 * before any `:`, or a whole scheme that only navigates. Read as [safeUrl] reads, after entities
 * are decoded; in `values`, a list, only the entry the hole is in counts.
 */
@InternalStreamlordApi
public fun urlSchemeFixedBy(
    attribute: String,
    prefix: String,
): Boolean {
    val decoded = decodeEntities(prefix).text
    val entry = if (attribute.asciiLowercase() == "values") decoded.substringAfterLast(';') else decoded
    val read = entry.filterNot { it == '\t' || it == '\n' || it == '\r' }.trimStart { it <= ' ' }
    for (c in read) {
        when (c) {
            '/', '?', '#' -> {
                return true
            }

            ':' -> {
                return SCHEME
                    .find(read)
                    ?.groupValues
                    ?.get(1)
                    ?.asciiLowercase() in SAFE_SCHEMES
            }
        }
    }
    return false
}

/** Each position's place in what it lets in: everything, a number or a boolean, a number, nothing. */
private val STRICTNESS =
    listOf(Position.TEXT, Position.ATTRIBUTE, Position.URL, Position.DATASTAR, Position.STYLE, Position.STRUCTURE, Position.CODE)

/** As HTML, as svg or math, and as svg or math whose CDATA sections are text (see scanMarkup). */
private fun landscapes(
    markup: String,
    holes: List<IntRange>,
    prefixes: List<String>,
): List<Landscape> {
    val shadow = shadow(markup, holes)
    // Longest first, so `data-star-` is tried before `data-` whatever order the caller used.
    val ordered = prefixes.sortedByDescending { it.length }
    return listOf(false to false, true to false, true to true).map { (foreign, cdata) ->
        Landscape(ordered, shadow).also { scanMarkup(shadow, it, foreign, cdata) }
    }
}

private fun landingOf(
    landscapes: List<Landscape>,
    hole: Int,
): HoleLanding {
    val strictest = landscapes.maxBy { STRICTNESS.indexOf(it.positionOf(hole)) }
    return HoleLanding(strictest.positionOf(hole), strictest.attributeAt(hole), strictest.valueStartAt(hole))
}

/**
 * [markup] with every hole replaced by letters, which keeps every offset where it was.
 *
 * The walk has to see the markup as the browser will once the values are in place. `<%s>` is not
 * a tag to a scanner that wants a letter after the `<`, and a hole nobody reads as a tag name is
 * a hole nobody refuses: the value would become the tag. With a name in its place the span is a
 * tag, and the hole inside it is structure.
 */
private fun shadow(
    markup: String,
    holes: List<IntRange>,
): String {
    val chars = markup.toCharArray()
    for (hole in holes) {
        for (i in hole) chars[i] = if (i == hole.first) 'd' else 's'
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
private val URL_ATTRIBUTES =
    setOf("href", "src", "action", "formaction", "xlink:href", "poster", "data", "cite", "background", "ping", "manifest")

/** SVG's animation elements, whose `values`, `to` and `from` can set an `href`. */
private val ANIMATION_TAGS = setOf("animate", "set", "animatetransform", "animatemotion")
private val ANIMATION_URL_ATTRIBUTES = setOf("values", "to", "from")

private class Landscape(
    private val prefixes: List<String>,
    private val markup: String,
) : MarkupVisitor {
    private val attributeNames = ArrayList<String>()
    private val attributeTags = ArrayList<String>()
    private val attributeStarts = ArrayList<Int>()
    private val attributeEnds = ArrayList<Int>()
    private val tagStarts = ArrayList<Int>()
    private val tagEnds = ArrayList<Int>()
    private val codeStarts = ArrayList<Int>()
    private val codeEnds = ArrayList<Int>()

    override fun attribute(
        tag: String,
        name: String,
        valueStart: Int,
        valueEnd: Int,
        quoted: Boolean,
    ) {
        // An unquoted value ends at the first space, so a value written into one can add
        // attributes beside it. It is left to the tag spans, which refuse.
        if (!quoted || valueStart < 0) return
        attributeNames += name
        attributeTags += tag.asciiLowercase()
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
            val name = attributeNames[attribute].asciiLowercase()
            return when {
                datastar(name) -> Position.DATASTAR
                name.startsWith("on") || name == "srcdoc" -> Position.CODE
                name == "style" -> Position.STYLE
                isUrl(attribute) -> Position.URL
                else -> Position.ATTRIBUTE
            }
        }
        if (indexOfSpan(hole, tagStarts, tagEnds) >= 0) return Position.STRUCTURE
        if (indexOfSpan(hole, codeStarts, codeEnds) >= 0) return Position.CODE
        return Position.TEXT
    }

    private fun isUrl(attribute: Int): Boolean {
        val name = attributeNames[attribute].asciiLowercase()
        return name in URL_ATTRIBUTES || (name in ANIMATION_URL_ATTRIBUTES && attributeTags[attribute] in ANIMATION_TAGS)
    }

    /**
     * Throws for the first URL attribute with a hole whose value, every value in place, would
     * carry a scheme that does more than navigate. A list of them, as `values` holds, is judged
     * entry by entry.
     */
    fun checkUrls(
        format: String,
        holes: List<Int>,
        values: List<Any?>,
    ) {
        for (attribute in attributeStarts.indices) {
            if (!isUrl(attribute)) continue
            val start = attributeStarts[attribute]
            val end = attributeEnds[attribute]
            val inside = holes.indices.filter { holes[it] in start until end }
            // Trusted waives every check, this one included.
            if (inside.isEmpty() || inside.any { values[it] is Trusted }) continue
            val value = StringBuilder()
            var cut = start
            for (index in inside) {
                value.append(decodeEntities(format.substring(cut, holes[index]).replace("%%", "%")).text)
                value.append(values[index].let { if (it is Trusted) it.html else it?.toString().orEmpty() })
                cut = holes[index] + 2
            }
            value.append(decodeEntities(format.substring(cut, end).replace("%%", "%")).text)
            val entries = if (attributeNames[attribute].asciiLowercase() == "values") value.split(';') else listOf(value.toString())
            if (entries.any { !safeUrl(it) }) throw UnsafeInterpolationException(Position.URL, inside.first(), attributeNames[attribute])
        }
    }

    fun valueStartAt(hole: Int): Int = indexOfSpan(hole, attributeStarts, attributeEnds).let { if (it >= 0) attributeStarts[it] else -1 }

    fun attributeAt(hole: Int): String? = indexOfSpan(hole, attributeStarts, attributeEnds).takeIf { it >= 0 }?.let { attributeNames[it] }

    // Their value is a signal path the client follows without a guard (bind.ts, ref.ts, indicator.ts).
    private val signalPaths = CatalogNames.signalAttributes

    private fun datastar(lower: String): Boolean {
        val prefix = prefixes.firstOrNull { lower.startsWith(it) } ?: return false
        val rest = lower.substring(prefix.length)
        val name = rest.substringBefore(':').substringBefore("__")
        return name in ElementsGuard.evaluatedAttributes || name in signalPaths
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
