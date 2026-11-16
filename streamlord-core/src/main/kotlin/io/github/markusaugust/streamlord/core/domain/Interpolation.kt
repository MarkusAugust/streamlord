package io.github.markusaugust.streamlord.core.domain

import io.github.markusaugust.streamlord.core.InternalStreamlordApi
import io.github.markusaugust.streamlord.core.StreamlordException
import io.github.markusaugust.streamlord.core.catalog.CatalogNames
import io.github.markusaugust.streamlord.core.client.asciiLowercase
import io.github.markusaugust.streamlord.core.client.decodeEntities
import io.github.markusaugust.streamlord.core.client.isHtmlSpace
import io.github.markusaugust.streamlord.core.client.regex
import org.intellij.lang.annotations.Language
import kotlin.jvm.JvmInline

/**
 * Markup the caller vouches for, written into the output as it stands.
 *
 * [interpolate] escapes every value it is given and refuses the positions escaping cannot make
 * safe. Wrapping a value here waives both for that value, which is the right thing for a fragment
 * you rendered yourself and the wrong thing for anything that came from a request. The type exists
 * so that waiver is a word in the source someone can search for.
 *
 * It waives nothing for the values beside it. Its text becomes part of the markup, and they are
 * judged against the markup as it will be written: `<%s %s="%s">` with `Trusted("button")` and
 * `Trusted("onclick")` puts the third value in an event handler, which is refused, and so does
 * `<%s>%s` with `Trusted("script")`, whose second value would be a program.
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
 * and [joinToHtml] make one, so holding an [Html] says where it came from.
 *
 * Element text means the data state on both sides. The [Html] has to close everything it opens,
 * read as HTML and as the inside of an `<svg>` or `<math>`: a tag, an attribute value, a comment,
 * a CDATA section, the body of a `<script>`, `<textarea>` or `<title>`, a `<` at its very end, an
 * `<svg>` or a `<math>` and what is in them, a `<template>` and a `<select>`; and it may not end an
 * element it did not open. The placeholder it fills has to stand outside all of those in the outer
 * markup. Otherwise the fragment would move every placeholder after it, `interpolate("<script>")`
 * turning the next value into a program and `interpolate("<svg>")` turning a `<title>` after it
 * into one that holds elements, so it is escaped like a string instead, and shows as the markup it
 * is.
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

/**
 * The markup of each [Html] in turn, with [separator] between them: a list of rows, say, as the
 * one value of a placeholder.
 *
 * ```kotlin
 * interpolate("<ul>%s</ul>", names.map { interpolate("<li>%s</li>", it) }.joinToHtml())
 * ```
 *
 * Nothing is read again or escaped. Each piece already closes what it opens, so the pieces side
 * by side do as well, and the result is markup like any other [Html].
 */
@OptIn(InternalStreamlordApi::class)
public fun Iterable<Html>.joinToHtml(separator: Html = Html("")): Html = Html(joinToString(separator.text) { it.text })

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
     * and `data-indicator`, where `__proto__.x` writes into every object of the page; and Pro's
     * `data-match-media`, whose query the reference writes as a string literal. An attribute the
     * client reads as it stands, `data-nonce` or `data-preserve-attr` among them, is an ordinary one.
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
 * @property index Which value, counting the placeholders from zero.
 * @property attribute The attribute it landed in, when it landed in one.
 * @property loadsCode Whether that attribute is a URL its tag loads and runs, or treats as its own
 *   document, such as a script's `src`: a [Position.URL] where the literal before the placeholder
 *   has to fix the host, which a safe scheme does not.
 */
public class UnsafeInterpolationException(
    public val position: Position,
    public val index: Int,
    public val attribute: String? = null,
    public val loadsCode: Boolean = false,
) : StreamlordException(message(position, index, attribute, loadsCode)) {
    private companion object {
        fun message(
            position: Position,
            index: Int,
            attribute: String?,
            loadsCode: Boolean,
        ): String {
            if (loadsCode) {
                return "Value $index would land in $attribute, a URL its tag loads and runs, where the markup before it " +
                    "does not fix the host, so any value could point the load at another origin, https or not. Write " +
                    "the host into the markup before the placeholder, as in \"/js/%s\" or \"https://cdn.example.com/%s\", " +
                    "or, if you chose the URL yourself, wrap it in Trusted(...)."
            }
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
                    Position.DATASTAR if attribute?.substringBefore(':')?.endsWith(MATCH_MEDIA) == true -> {
                        "Write the query into the markup, where a constant of yours may stand as a Kotlin template, " +
                            "or set it with the DSL's dataMatchMedia."
                    }

                    Position.DATASTAR -> {
                        // A string written into data-signals is refused by this same rule, so no signal is advised.
                        "Put the value in an attribute of its own, such as data-label, and read it in the expression with " +
                            "el.dataset.label, or pass a Number or Boolean, which can carry neither an expression nor a path."
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
 * Writes [values] into [format] at its `%s` placeholders, escaped, and refuses any value that
 * landed somewhere escaping cannot make safe.
 *
 * The question is not where a value came from, which Streamlord cannot know by the time it is
 * handed a string. It is where the value lands, which is a fact about [format] alone: Kotlin has
 * already finished with the literal, so every `%s` in it is a placeholder the caller left on purpose and
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
 * `href="%s%s"` cannot be made into `javascript:` across its two placeholders. A URL the browser loads
 * and runs, a script's `src`, a `<link>`'s `href` and the like, must have its host fixed by the
 * markup before the placeholder, as in `src="/js/%s"`. The URL a `<meta>` refresh reads from its
 * `content` is held to the same schemes.
 *
 * A [Trusted] value is not judged, and is read as the markup it is: the other values are judged
 * where its text puts them, a tag or attribute name among it, and in a URL it counts as the
 * markup around it does and vouches for nothing else there.
 *
 * The markup is read the way the browser's tokenizer reads it: as HTML, as the inside of an
 * `<svg>` or `<math>`, where a `title` or a `textarea` holds elements rather than text, by the
 * browser's element stack from HTML and from inside an svg or a math, and, where it holds a
 * `noscript`, with scripting off, as the client parses a patch, and on, as a page loads. A value
 * has to be safe every way, so markup whose namespace cannot be told, a patch with an SVG
 * namespace among it, is judged as either.
 *
 * `%%` writes one `%`. A `%` followed by anything else is literal, so CSS and escaped URLs need
 * no ceremony; a placeholder is only ever `%s`, and a miscounted one fails here rather than quietly.
 *
 * This is the rule the editors enforce as you type, where they can see the Kotlin source and its
 * `${'$'}{...}`. At render time the source is gone, so the placeholders have to be left standing. That is
 * the whole of what this function asks of you.
 *
 * The values are always the values: a `List` or an array handed over as one of them is one value,
 * written in brackets as Kotlin writes a list, `[a, b]`, escaped like any text. An array is
 * written that way too, where a Kotlin template writes its class and hash. To pass the elements of
 * a collection as the values, spread them: `interpolate(format, *values.toTypedArray())`. From
 * Java, `values` is varargs, so a `String[]` passed alone is spread: it is the values.
 *
 * @throws UnsafeInterpolationException if a value landed where escaping cannot help.
 * @throws IllegalArgumentException if the number of values does not match the number of placeholders.
 */
public fun interpolate(
    @Language("HTML") format: String,
    vararg values: Any?,
): Html = interpolated(format, values.asList(), ElementsGuard.defaultPrefixes)

/**
 * [interpolate] with the `data-*` prefixes to recognise, which come first.
 *
 * Pass [prefixes] when your Datastar bundle is aliased to something other than `data-` or
 * `data-star-`; the attributes of an alias this function does not know are attributes it cannot
 * refuse a value in.
 */
public fun interpolate(
    prefixes: List<String>,
    @Language("HTML") format: String,
    vararg values: Any?,
): Html = interpolated(format, values.asList(), prefixes)

/**
 * [interpolate] with each value written where it stands: `+` marks a value in the markup the block
 * gives, and it is escaped or refused by where it landed, as a `%s` placeholder's value is.
 *
 * ```kotlin
 * fun hit(title: String, url: String): Html =
 *     interpolate { """<li><a href="${+url}">${+title}</a></li>""" }
 * ```
 *
 * The mark leaves a note in the string and keeps the value, so the markup is read with no value in
 * it yet: a trim call on the string runs before the values are in, and a `%` in it is one. A mark
 * may stand in a loop or be written twice. An [Html] and a [Trusted] are marked like any value and
 * written as [interpolate] writes them.
 *
 * A value written without the mark is filled in by Kotlin before this function sees the string: it
 * is markup as written, neither escaped nor refused, which is right for a constant and for nothing
 * that comes from outside. A number marks nothing either, since `+` on a number is Kotlin's own; it
 * is written as the number it is.
 *
 * @throws UnsafeInterpolationException if a marked value landed where escaping cannot help.
 */
public inline fun interpolate(block: InterpolationScope.() -> String): Html = interpolate(ElementsGuard.defaultPrefixes, block)

/** The block form of [interpolate] with the `data-*` prefixes to recognise, for an aliased bundle. */
public inline fun interpolate(
    prefixes: List<String>,
    block: InterpolationScope.() -> String,
): Html = InterpolationScope().run { html(block(), prefixes) }

/** Where the block form of [interpolate] is written: it keeps the values its `+` marks. */
public class InterpolationScope
    @PublishedApi
    internal constructor() {
        private val marked = ArrayList<Any?>()

        /** Marks this value where it is written: [interpolate] escapes it or refuses it by where it lands. */
        public operator fun Any?.unaryPlus(): Placed {
            marked += this
            return Placed("$MARK_OPENS${marked.size - 1}$MARK_CLOSES")
        }

        /** [markup] with each mark's value where the mark stands, as the placeholder form writes it. */
        @PublishedApi
        internal fun html(
            markup: String,
            prefixes: List<String>,
        ): Html {
            val format = StringBuilder(markup.length + 16)
            val values = ArrayList<Any?>()
            var i = 0
            while (i < markup.length) {
                val c = markup[i++]
                if (c == '%') format.append('%')
                if (c != MARK_OPENS) {
                    format.append(c)
                    continue
                }
                // A mark is read no further than a mark is long, so a stray opening costs nothing.
                var close = i
                while (close < markup.length && close - i < MARK_DIGITS && markup[close].isAsciiDigit()) close++
                val index = if (markup.getOrNull(close) == MARK_CLOSES) markup.substring(i, close).toIntOrNull() else null
                if (index == null || index >= marked.size) {
                    format.append(c)
                    continue
                }
                format.append("%s")
                values += marked[index]
                i = close + 1
            }
            return interpolated(format.toString(), values, prefixes)
        }
    }

/** What a `+` in the block form of [interpolate] writes: a note of which value stands there, and not the value. */
@JvmInline
public value class Placed internal constructor(
    private val mark: String,
) {
    override fun toString(): String = mark
}

// Two characters Unicode sets aside for a program's own use and no text holds, around the value's number.
private const val MARK_OPENS = '﷐'
private const val MARK_CLOSES = '﷑'
private const val MARK_DIGITS = 9

private fun Char.isAsciiDigit(): Boolean = this in '0'..'9'

private fun interpolated(
    format: String,
    values: List<Any?>,
    prefixes: List<String>,
): Html {
    val placeholders = placeholders(format)
    require(placeholders.size == values.size) {
        // Until 0.22.0 a List handed over alone was the values; it is one value now, as any other.
        val spread =
            if (values.size == 1 && values[0].let { it is Iterable<*> || it is Array<*> }) {
                ". That value is a collection, which is one value: to pass its elements, spread them, interpolate(format, *values.toTypedArray())"
            } else {
                ""
            }
        "The markup has ${placeholders.size} %s ${if (placeholders.size == 1) "placeholder" else "placeholders"} and ${values.size} " +
            "${if (values.size == 1) "value was" else "values were"} given$spread"
    }
    if (placeholders.isEmpty()) return Html(format.replace("%%", "%"))

    // A Trusted value is markup: it is read in place, and the other values are judged where its text puts them.
    val known = HashMap<Int, String>()
    for ((index, value) in values.withIndex()) if (value is Trusted) known[index] = value.html
    val written = Written(format, placeholders.map { it until it + 2 }, known, percents = true)
    val text = written.text
    val landscapes = landscapes(text, written.open, prefixes)

    val out = StringBuilder(text.length + values.size * 16)
    var cut = 0
    for ((index, value) in values.withIndex()) {
        if (value is Trusted) continue
        val placeholder = written.starts[index]
        out.append(text, cut, placeholder)
        cut = placeholder + 2
        val landing = landingOf(landscapes, placeholder)
        // Markup interpolate built is markup where it starts and ends in the data state in
        // every reading, so that it cannot move the placeholders after it; anywhere else it is characters.
        if (value is Html && landing.markupInPlace && landscapes.all { it.closes(value.text, placeholder) }) {
            out.append(value.text)
        } else {
            if (!landing.position.admits(value)) throw UnsafeInterpolationException(landing.position, index, landing.attribute)
            out.escaped(value)
        }
    }
    out.append(text, cut, text.length)
    for (landscape in landscapes) landscape.checkUrls(text, written.starts.asList(), values)
    return Html(out.toString())
}

/**
 * Markup as it will be written, as far as that is known before the values are escaped: each placeholder
 * whose text is known, a [Trusted] value's, replaced by that text, and every other placeholder left
 * standing to be shadowed. With [percents], as a format is, `%%` in the literal is one `%`; a
 * known text is written as it stands, `%%` and all.
 *
 * @property starts Where each placeholder, known or not, begins in [text], by its index.
 * @property open The placeholders whose text is not known, as spans of [text].
 */
private class Written(
    markup: String,
    placeholders: List<IntRange>,
    known: Map<Int, String>,
    percents: Boolean,
) {
    val text: String
    val starts = IntArray(placeholders.size)
    val open = ArrayList<IntRange>(placeholders.size - known.size)

    // Runs of [text] in order: where each starts, where in the markup it came from, and whether it is a known text.
    private val runStarts = ArrayList<Int>()
    private val runSources = ArrayList<Int>()
    private val runKnown = ArrayList<Boolean>()

    init {
        val out = StringBuilder(markup.length + known.values.sumOf { it.length })

        fun run(
            source: Int,
            isKnown: Boolean,
        ) {
            runStarts += out.length
            runSources += source
            runKnown += isKnown
        }

        fun literal(
            from: Int,
            to: Int,
        ) {
            run(from, false)
            var i = from
            while (i < to) {
                if (percents && markup[i] == '%' && i + 1 < to && markup[i + 1] == '%') {
                    out.append('%')
                    i += 2
                    run(i, false)
                } else {
                    out.append(markup[i])
                    i++
                }
            }
        }

        var cut = 0
        for (index in placeholders.indices.sortedBy { placeholders[it].first }) {
            val placeholder = placeholders[index]
            literal(cut, placeholder.first)
            starts[index] = out.length
            val text = known[index]
            if (text != null) {
                run(placeholder.first, true)
                out.append(text)
            } else {
                run(placeholder.first, false)
                open += out.length until out.length + (placeholder.last - placeholder.first + 1)
                out.append(markup, placeholder.first, placeholder.last + 1)
            }
            cut = placeholder.last + 1
        }
        literal(cut, markup.length)
        text = out.toString()
    }

    /** Where [at] in [text] came from in the markup; within a known text, where its placeholder begins. */
    fun sourceOf(at: Int): Int {
        val run = lastAtOrBefore(runStarts, at)
        return if (runKnown[run]) runSources[run] else runSources[run] + at - runStarts[run]
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

/**
 * Pro's media query, `data-match-media:dark="'(prefers-color-scheme: dark)'"`: the reference writes
 * it as a string literal, and Pro's source is not here to say it is never run, so a value is
 * refused there as in an expression.
 */
private const val MATCH_MEDIA = "match-media"

private val SCHEME = regex("^([A-Za-z][A-Za-z0-9+.-]*):")
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

/** A value as interpolate writes it: an array as a list is written, `[a, b]`, where a template writes its class and hash. */
private fun textOf(value: Any?): String =
    when (value) {
        null -> ""
        is Array<*> -> value.contentDeepToString()
        is IntArray -> value.contentToString()
        is LongArray -> value.contentToString()
        is ShortArray -> value.contentToString()
        is ByteArray -> value.contentToString()
        is DoubleArray -> value.contentToString()
        is FloatArray -> value.contentToString()
        is BooleanArray -> value.contentToString()
        is CharArray -> value.contentToString()
        else -> value.toString()
    }

private fun StringBuilder.escaped(value: Any?) {
    val text = textOf(value)
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
 * Where one placeholder in a piece of markup lands, as [interpolate] judges it.
 *
 * The editors judge a Kotlin template in an HTML string with this, so that a placeholder is refused or
 * let through by the same reading in the editor as at run time.
 *
 * @property position The strictest of the positions the placeholder takes in the readings of the markup;
 *   see [interpolate].
 * @property attribute The attribute it landed in, when it landed in a quoted one.
 * @property valueStart Where that attribute's value starts in the markup, or -1.
 * @property loadsCode Whether that attribute is a URL the browser loads and runs, or treats as its
 *   own document, on its tag: `src` of a `<script>`, `<iframe>` or `<embed>`, `href` of a `<base>`,
 *   a `<link>` or an SVG `<script>`, `data` of an `<object>`. A placeholder there is judged by
 *   [urlHostFixedBy] rather than [urlSchemeFixedBy].
 * @property tagStart Where the tag it landed in starts in the markup, at its `<`, when it landed
 *   in a tag, on an attribute's name or in its value; or -1. A tag over several lines starts on the
 *   first, and raw text, a comment or a quoted value holds no tag.
 * @property nameStart Where the attribute name it landed on starts in the markup, when it landed on
 *   one; or -1. The name is the browser's: an `=` first in it is part of it, a value after an `=`
 *   and white space is no name.
 * @property markupInPlace Whether an [Html] that closes what it opens is written there as it
 *   stands: in element text that is text in every reading, and not the body of a `textarea` or a
 *   `title`, where interpolate escapes it as it does anywhere else.
 */
@InternalStreamlordApi
public class ValueLanding(
    public val position: Position,
    public val attribute: String?,
    public val valueStart: Int,
    public val loadsCode: Boolean,
    public val tagStart: Int = -1,
    public val nameStart: Int = -1,
    public val markupInPlace: Boolean = false,
)

/**
 * Where each of [placeholders] lands in [markup]. A placeholder is any span the caller will fill: `%s` to
 * [interpolate], a Kotlin template to the editors. Each is read as a run of letters, so `<%s>` is
 * a tag whose name is the placeholder.
 *
 * [known] holds the text of the placeholders, by index, whose content is known to be markup, as a
 * [Trusted] value's is: the markup is read with that text in place of the placeholder, as [interpolate]
 * reads it, so `<%s>` with `script` known puts the next placeholder in a script. A known placeholder is not
 * judged, and its landing is [Position.TEXT] with no attribute. A [ValueLanding.valueStart] is an
 * offset into [markup] still; one that falls inside a known text is where that placeholder begins.
 */
@InternalStreamlordApi
public fun valueLandings(
    markup: String,
    placeholders: List<IntRange>,
    prefixes: List<String> = ElementsGuard.defaultPrefixes,
    known: Map<Int, String> = emptyMap(),
): List<ValueLanding> {
    val written = Written(markup, placeholders, known, percents = false)
    val landscapes = landscapes(written.text, written.open, prefixes)
    return placeholders.indices.map { index ->
        if (index in known) {
            ValueLanding(Position.TEXT, null, -1, false)
        } else {
            landingOf(landscapes, written.starts[index]).inSource(written)
        }
    }
}

/**
 * Where [placeholder] lands in [markup] as the browser parses it as a patch: the one reading that follows
 * the browser's element stack from HTML, with scripting off. Where [valueLandings] takes the
 * strictest of every reading, so that nothing is let through that one of them would refuse, this
 * is the reading the markup has, which an editor shows at the caret: in a `<textarea>` the text is
 * text. It is [walkMarkup]'s, which the editors' checks read by as well, and with [templates] it
 * skips the comments and tags of a template language as that does. A [ValueLanding.valueStart] is an
 * offset into [markup].
 */
@InternalStreamlordApi
public fun landingAsParsed(
    markup: String,
    placeholder: IntRange,
    prefixes: List<String> = ElementsGuard.defaultPrefixes,
    templates: Boolean = false,
): ValueLanding {
    val written = Written(markup, listOf(placeholder), emptyMap(), percents = false)
    val reading = Reading(tracked = true, context = NS_HTML, templates = templates)
    val parsed = landscape(shadow(written.text, written.open), written.open, prefixes, reading)
    return landingOf(listOf(parsed), written.starts[0]).inSource(written)
}

/** The landing with its offsets into the markup as written. */
private fun ValueLanding.inSource(written: Written): ValueLanding =
    ValueLanding(
        position,
        attribute,
        if (valueStart < 0) valueStart else written.sourceOf(valueStart),
        loadsCode,
        if (tagStart < 0) tagStart else written.sourceOf(tagStart),
        if (nameStart < 0) nameStart else written.sourceOf(nameStart),
        markupInPlace,
    )

/**
 * Whether the start of a URL attribute's value, the literal text before its first placeholder, already
 * settles the scheme, so that no value written after it can make the URL run: a `/`, `?` or `#`
 * before any `:`, or a whole scheme that only navigates. Read as [safeUrl] reads, after entities
 * are decoded; in `values`, a list, only the entry the placeholder is in counts.
 */
@InternalStreamlordApi
public fun urlSchemeFixedBy(
    attribute: String,
    prefix: String,
): Boolean = settledScheme(attribute, prefix)?.let { it.isEmpty() || it in SAFE_SCHEMES } == true

/**
 * The scheme the start of a URL attribute's value already writes in full when it is one that does
 * more than navigate, `javascript` of `javascript:`, lowercased: interpolate then refuses every
 * value written after it. Null where the start settles no scheme or a safe one; read as
 * [urlSchemeFixedBy] reads.
 */
@InternalStreamlordApi
public fun refusedUrlScheme(
    attribute: String,
    prefix: String,
): String? = settledScheme(attribute, prefix)?.takeIf { it.isNotEmpty() && it !in SAFE_SCHEMES }

/** The scheme [prefix] writes in full, lowercased, empty where a `/`, `?` or `#` comes first; null where it settles none. */
private fun settledScheme(
    attribute: String,
    prefix: String,
): String? {
    val decoded = decodeEntities(prefix).text
    val entry = if (attribute.asciiLowercase() == "values") decoded.substringAfterLast(';') else decoded
    val read = entry.filterNot { it == '\t' || it == '\n' || it == '\r' }.trimStart { it <= ' ' }
    for (c in read) {
        when (c) {
            '/', '?', '#' -> {
                return ""
            }

            ':' -> {
                return SCHEME
                    .find(read)
                    ?.groupValues
                    ?.get(1)
                    ?.asciiLowercase()
            }
        }
    }
    return null
}

/**
 * Whether the start of a code-loading URL attribute's value, the literal text before its first
 * placeholder, already fixes the host, so that no value written after it can point the load at another
 * origin: a path-absolute `/x` (a single `/` and then a character that is not `/` or `\`, so a
 * value cannot make it `//host`), a same-document `#` or `?`, or a whole origin `scheme://host/`
 * with a safe scheme. A bare `/`, or a host the literal does not terminate, is not enough. Read as
 * [urlSchemeFixedBy] reads, after entities are decoded; in `values`, only the entry the placeholder is in.
 */
@InternalStreamlordApi
public fun urlHostFixedBy(
    attribute: String,
    prefix: String,
): Boolean {
    val decoded = decodeEntities(prefix).text
    val entry = if (attribute.asciiLowercase() == "values") decoded.substringAfterLast(';') else decoded
    val read = entry.filterNot { it == '\t' || it == '\n' || it == '\r' }.trimStart { it <= ' ' }
    val first = read.firstOrNull() ?: return false
    if (first == '#' || first == '?') return true
    if (first == '/') return read.getOrNull(1).let { it != null && it != '/' && it != '\\' }
    // scheme://host/ : a safe scheme, `//`, authority characters, then a terminator in the literal.
    val scheme = SCHEME.find(read) ?: return false
    if (scheme.groupValues[1].asciiLowercase() !in SAFE_SCHEMES) return false
    var i = scheme.value.length
    if (!read.startsWith("//", i)) return false
    i += 2
    while (i < read.length && read[i] != '/' && read[i] != '?' && read[i] != '#') i++
    return i < read.length
}

/** The index of the last of [sorted] that is at most [at], or -1. */
private fun lastAtOrBefore(
    sorted: List<Int>,
    at: Int,
): Int {
    var low = 0
    var high = sorted.size - 1
    while (low <= high) {
        val mid = (low + high) ushr 1
        if (sorted[mid] <= at) low = mid + 1 else high = mid - 1
    }
    return high
}

/** Each position's place in what it lets in: everything, a number or a boolean, a number, nothing. */
private val STRICTNESS =
    listOf(Position.TEXT, Position.ATTRIBUTE, Position.URL, Position.DATASTAR, Position.STYLE, Position.STRUCTURE, Position.CODE)

/** One way of reading markup, as [scanMarkup] takes it. */
private class Reading(
    val foreign: Boolean = false,
    val cdata: Boolean = false,
    val tracked: Boolean = false,
    val scripting: Boolean = false,
    val context: Int = NS_HTML,
    val htmlStaysOpen: Boolean = true,
    val templates: Boolean = false,
    val insideMarkup: Boolean = false,
    val closesAll: Boolean = false,
) {
    fun scan(
        html: String,
        visitor: MarkupVisitor,
    ): Boolean = scanMarkup(html, visitor, foreign, cdata, tracked, scripting, context, htmlStaysOpen, templates, insideMarkup, closesAll)

    /**
     * This tracked reading, of a value written into markup where the content is of [namespace]: it has
     * to close all it opens, and with [leaves], a tag that returns from that svg or math to HTML closes it.
     */
    fun of(
        namespace: Int,
        leaves: Boolean,
        closesAll: Boolean = true,
    ): Reading =
        Reading(
            tracked = true,
            scripting = scripting,
            context = namespace,
            htmlStaysOpen = htmlStaysOpen,
            insideMarkup = leaves,
            closesAll = closesAll,
        )

    fun withScripting(): Reading = Reading(foreign, cdata, tracked, true, context, htmlStaysOpen, templates, insideMarkup, closesAll)
}

/**
 * The readings of the markup. Three fixed ones: as HTML, as svg or math, as svg or math whose CDATA
 * sections are text. And the ones that follow the browser's element stack and switch rule by
 * position (see scanMarkup), starting in HTML as a patch in a `<template>` does, inside an `<svg>`
 * as a patch in the svg namespace does, and inside a `<math>`. A placeholder takes the strictest position
 * of them all. The fixed readings each hold one rule throughout and so each miss where the markup
 * changes context partway; the tracked readings catch those, while the fixed ones stay a
 * conservative floor. From inside an svg and a math, the stack is followed twice: once with every
 * HTML element open to its end tag, and once with none opened from the svg or math left open, as
 * when the browser closes a `<p>` at the next `<p>` or `<div>` and is back in the svg.
 *
 * Markup with a `noscript` is read twice over: with scripting off, as the client parses a patch,
 * where it holds elements, and with scripting on, as a page loads, where it is text to its end tag.
 */
private fun landscapes(
    markup: String,
    placeholders: List<IntRange>,
    prefixes: List<String>,
): List<Landscape> {
    val shadow = shadow(markup, placeholders)
    val scripting = if (mentionsNoscript(shadow)) listOf(false, true) else listOf(false)
    val readings =
        scripting.map { Reading(scripting = it) } +
            listOf(Reading(foreign = true), Reading(foreign = true, cdata = true)) +
            listOf(NS_HTML, NS_SVG, NS_MATH).flatMap { context ->
                scripting.map { Reading(tracked = true, scripting = it, context = context) }
            } +
            listOf(NS_SVG, NS_MATH).flatMap { context ->
                scripting.map { Reading(tracked = true, scripting = it, context = context, htmlStaysOpen = false) }
            }
    val placeholderEnds = placeholders.associate { it.first to it.last + 1 }
    return readings.map { landscape(shadow, placeholders, prefixes, it, placeholderEnds) }
}

/** The [shadow] of a markup with [placeholders] in it, read the one way [reading] gives. */
private fun landscape(
    shadow: String,
    placeholders: List<IntRange>,
    prefixes: List<String>,
    reading: Reading,
    placeholderEnds: Map<Int, Int> = placeholders.associate { it.first to it.last + 1 },
): Landscape {
    // Longest first, so `data-star-` is tried before `data-` whatever order the caller used.
    val ordered = prefixes.sortedByDescending { it.length }
    return Landscape(ordered, reading, shadow, placeholderEnds).also { reading.scan(shadow, it) }
}

/** Whether a `noscript` may stand in [html], which only then reads differently with scripting on. */
private fun mentionsNoscript(html: String): Boolean = html.contains("noscript", ignoreCase = true)

private fun landingOf(
    landscapes: List<Landscape>,
    placeholder: Int,
): ValueLanding {
    val strictest = landscapes.maxBy { STRICTNESS.indexOf(it.positionOf(placeholder)) }
    val position = strictest.positionOf(placeholder)
    return ValueLanding(
        position,
        strictest.attributeAt(placeholder),
        strictest.valueStartAt(placeholder),
        strictest.loadsCodeAt(placeholder),
        strictest.tagStartAt(placeholder),
        strictest.nameStartAt(placeholder),
        position == Position.TEXT && landscapes.all { it.inData(placeholder) },
    )
}

/**
 * [markup] with every placeholder replaced by letters, which keeps every offset where it was.
 *
 * The walk has to see the markup as the browser will once the values are in place. `<%s>` is not
 * a tag to a scanner that wants a letter after the `<`, and a placeholder nobody reads as a tag name is
 * a placeholder nobody refuses: the value would become the tag. With a name in its place the span is a
 * tag, and the placeholder inside it is structure.
 */
private fun shadow(
    markup: String,
    placeholders: List<IntRange>,
): String {
    val chars = markup.toCharArray()
    for (placeholder in placeholders) {
        for (i in placeholder) chars[i] = if (i == placeholder.first) 'd' else 's'
    }
    return chars.concatToString()
}

/** The offset of each `%s`, in order. `%%` is one escaped per cent and never a placeholder. */
private fun placeholders(format: String): List<Int> {
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
 * The shape of the markup around the placeholders: which spans are quoted attribute values, which are
 * inside a tag, and which are the body of a script or a style.
 */
private val URL_ATTRIBUTES =
    setOf("href", "src", "action", "formaction", "xlink:href", "poster", "data", "cite", "background", "ping", "manifest")

/** What the markup after a placeholder in a comment may begin with for dashes or a `!` in the value to end the comment there. */
private val COMMENT_CLOSERS = listOf(">", "!>", "->", "-!>")

/** SVG's animation elements, whose `values`, `to` and `from` can set an `href`. */
private val ANIMATION_TAGS = setOf("animate", "set", "animatetransform", "animatemotion")
private val ANIMATION_URL_ATTRIBUTES = setOf("values", "to", "from")

/**
 * URL attributes that load what the browser then runs or treats as its own document, by tag: a
 * value here must come out on the author's own host, not merely with a safe scheme. `srcdoc` and
 * an `on*` handler are [Position.CODE] already, not URLs.
 */
private val CODE_URL_ATTRIBUTES =
    mapOf(
        "script" to setOf("src", "href", "xlink:href"),
        "base" to setOf("href"),
        "iframe" to setOf("src"),
        "embed" to setOf("src"),
        "object" to setOf("data"),
        "link" to setOf("href"),
    )

/**
 * The URLs a refresh could read from a `<meta>`'s `content`, none when it does not start with a
 * time: what follows the time and its separator, with and without a leading `url=` and a quote. A
 * candidate too many only refuses more.
 */
private fun refreshUrls(content: String): List<String> {
    var i = 0
    while (i < content.length && content[i].isHtmlSpace()) i++
    if (i >= content.length || (content[i] !in '0'..'9' && content[i] != '.')) return emptyList()
    while (i < content.length && (content[i] in '0'..'9' || content[i] == '.')) i++
    while (i < content.length && (content[i].isHtmlSpace() || content[i] == ';' || content[i] == ',')) i++
    val rest = content.substring(i)
    val key = REFRESH_URL_KEY.find(rest)
    val url = if (key == null) rest else rest.substring(key.range.last + 1)
    return listOf(rest, url).flatMap { listOf(it, it.trimStart('"', '\'')) }
}

private val REFRESH_URL_KEY = regex("^[Uu][Rr][Ll][\\t\\n\\u000C\\r ]*=[\\t\\n\\u000C\\r ]*")

/** The span of [text] from [from] to [to] as it will be written, with the values of the placeholders [inside] it escaped. */
private fun written(
    text: String,
    from: Int,
    to: Int,
    inside: List<Int>,
    placeholders: List<Int>,
    values: List<Any?>,
): String {
    val out = StringBuilder()
    var cut = from
    for (index in inside) {
        out.append(text, cut, placeholders[index])
        out.escaped(values[index])
        cut = placeholders[index] + 2
    }
    out.append(text, cut, to)
    return out.toString()
}

private class Landscape(
    private val prefixes: List<String>,
    private val reading: Reading,
    // The shadowed markup this landscape reads, and where each placeholder in it ends, by where it starts.
    private val markup: String,
    private val placeholderEnds: Map<Int, Int>,
) : MarkupVisitor {
    private val declarationStarts = ArrayList<Int>()
    private val declarationEnds = ArrayList<Int>()
    private val attributeNames = ArrayList<String>()
    private val attributeTags = ArrayList<String>()
    private val attributeStarts = ArrayList<Int>()
    private val attributeEnds = ArrayList<Int>()
    private val tagStarts = ArrayList<Int>()
    private val tagEnds = ArrayList<Int>()
    private val nameStarts = ArrayList<Int>()
    private val nameEnds = ArrayList<Int>()
    private val codeStarts = ArrayList<Int>()
    private val codeEnds = ArrayList<Int>()
    private val opaqueStarts = ArrayList<Int>()
    private val opaqueEnds = ArrayList<Int>()
    private val textStarts = ArrayList<Int>()
    private val textEnds = ArrayList<Int>()
    private val textPlaces = ArrayList<TextPlace>()

    override fun attribute(
        tag: String,
        name: String,
        nameStart: Int,
        valueStart: Int,
        valueEnd: Int,
        quoted: Boolean,
    ) {
        nameStarts += nameStart
        nameEnds += nameStart + name.length
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

    override fun opaque(
        start: Int,
        end: Int,
    ) {
        opaqueStarts += start
        opaqueEnds += end
    }

    override fun text(
        start: Int,
        end: Int,
        place: TextPlace,
    ) {
        textStarts += start
        textEnds += end
        textPlaces += place
    }

    override fun declaration(
        start: Int,
        end: Int,
    ) {
        declarationStarts += start
        declarationEnds += end
        opaque(start, end)
    }

    /**
     * Whether a value at [placeholder] in the declaration that begins at [start] could end it or change
     * what it is, though escaping takes its `<` and `>`: `--` or `[CDATA[` after `<!` turns a bogus
     * comment into a comment or a section, and dashes, a `!` or a `]` before the `>` the markup
     * writes after the placeholder close a comment or a section early.
     */
    private fun reshapes(
        start: Int,
        placeholder: Int,
    ): Boolean {
        val end = placeholderEnds[placeholder] ?: return false
        val after = markup.substring(end, minOf(markup.length, end + 3))
        if (markup.startsWith("<!--", start)) return COMMENT_CLOSERS.any { after.startsWith(it) }
        if (!markup.startsWith("<!", start)) return false
        val between = markup.substring(start + 2, placeholder)
        return (between.length < 2 && "--".startsWith(between)) ||
            (between.length < 7 && "[CDATA[".startsWith(between)) ||
            (markup.startsWith("<![CDATA[", start) && (after.startsWith(">") || after.startsWith("]>")))
    }

    fun positionOf(placeholder: Int): Position {
        val attribute = indexOfSpan(placeholder, attributeStarts, attributeEnds)
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
        if (indexOfSpan(placeholder, tagStarts, tagEnds) >= 0) return Position.STRUCTURE
        if (indexOfSpan(placeholder, codeStarts, codeEnds) >= 0) return Position.CODE
        val declaration = indexOfSpan(placeholder, declarationStarts, declarationEnds)
        if (declaration >= 0 && reshapes(declarationStarts[declaration], placeholder)) return Position.STRUCTURE
        return Position.TEXT
    }

    private fun isUrl(attribute: Int): Boolean {
        val name = attributeNames[attribute].asciiLowercase()
        return name in URL_ATTRIBUTES ||
            (name in ANIMATION_URL_ATTRIBUTES && attributeTags[attribute] in ANIMATION_TAGS) ||
            codeUrl(attribute)
    }

    private fun codeUrl(attribute: Int): Boolean =
        attributeNames[attribute].asciiLowercase() in CODE_URL_ATTRIBUTES[attributeTags[attribute]].orEmpty()

    /** Whether this is the `content` of a `<meta>`, which, as an `http-equiv="refresh"` reads it, can hold a URL. */
    private fun refreshContent(attribute: Int): Boolean =
        attributeTags[attribute] == "meta" && attributeNames[attribute].asciiLowercase() == "content"

    /**
     * Throws for the first URL attribute with a placeholder whose value, every value in place, would
     * carry a scheme that does more than navigate. A list of them, as `values` holds, is judged
     * entry by entry, and the `content` of a `<meta>` by the URL a refresh would read from it.
     *
     * [text] is the markup as [Written] has it, every [Trusted] value's text in place: it counts
     * toward the URL as the literal does and vouches for nothing beside it, so `href="%s%s"` with
     * `Trusted("")` first still refuses `javascript:` in the second. Only a URL with nothing but
     * Trusted values in it goes unjudged.
     */
    fun checkUrls(
        text: String,
        placeholders: List<Int>,
        values: List<Any?>,
    ) {
        for (attribute in attributeStarts.indices) {
            val refresh = refreshContent(attribute)
            if (!refresh && !isUrl(attribute)) continue
            val start = attributeStarts[attribute]
            val end = attributeEnds[attribute]
            val first = lastAtOrBefore(placeholders, start - 1) + 1
            val inside = (first until placeholders.size).takeWhile { placeholders[it] < end }.filter { values[it] !is Trusted }
            val untrusted = inside.firstOrNull() ?: continue
            val name = attributeNames[attribute]
            // A code-loading URL has to have its host fixed before the first value nobody vouched
            // for; a safe scheme is not enough, since a safe scheme can still point at another origin.
            if (!refresh && codeUrl(attribute)) {
                if (!urlHostFixedBy(name, text.substring(start, placeholders[untrusted]))) {
                    throw UnsafeInterpolationException(Position.URL, untrusted, name, loadsCode = true)
                }
                continue
            }
            // The attribute as it will be written, decoded once, as the browser does: a reference
            // the literal begins can end in the value, `&%s` and `#106;` making a `j`.
            val value = decodeEntities(written(text, start, end, inside, placeholders, values)).text
            val entries =
                when {
                    refresh -> refreshUrls(value)
                    name.asciiLowercase() == "values" -> value.split(';')
                    else -> listOf(value)
                }
            if (entries.any { !safeUrl(it) }) throw UnsafeInterpolationException(Position.URL, untrusted, name)
        }
    }

    /**
     * Whether [at] is in the data state: text, and not a tag, a comment, a CDATA section or the
     * body of a `textarea` or a `title`, which only their own closing sequence ends and which markup
     * written there could end. The walk has already found each of those spans, so this is a lookup.
     */
    fun inData(at: Int): Boolean = indexOfSpan(at, tagStarts, tagEnds) < 0 && indexOfSpan(at, opaqueStarts, opaqueEnds) < 0

    /**
     * Whether [html], read in this landscape, leaves nothing open for the markup after it to fall
     * into, with scripting on as well when it holds a `noscript`. A reading that follows the element
     * stack reads it where it lands, at [placeholder], rather than where the reading starts, and there it
     * leaves no element open at all: the format's end tags do not all reach one, as an object bounds
     * them, an integration point stops an svg's, and an `<a>` is opened again after its parent closes.
     */
    fun closes(
        html: String,
        placeholder: Int,
    ): Boolean {
        fun closesIn(r: Reading) =
            r.scan(html, Discard) && (r.scripting || !mentionsNoscript(html) || r.withScripting().scan(html, Discard))
        // A reading that follows the element stack reads the value where it lands, not where the
        // reading starts: read from inside an svg, the `<a>` of `<div>%s</div>` and an `<a><span>` is
        // HTML. The fixed readings have no stack to know it by.
        val at = indexOfSpan(placeholder, textStarts, textEnds)
        if (!reading.tracked || at < 0) return closesIn(reading)
        val place = textPlaces[at]
        // Right in an integration point the value is HTML, whatever the reading started in.
        if (place.integrationPoint) return closesIn(reading.of(NS_HTML, leaves = false)) && fits(html, place)
        // Read from an svg or a math, a format's `<form>` or `<math>` is an element of that namespace,
        // so that reading only checks the value closes, and leaves the rest to the reading from HTML.
        // Straight in the patch's own svg or math it may leave nothing open, where an `<image>` or an
        // `<input>` is an element left open; the reading that closes HTML elements by itself leaves that.
        if (reading.context != NS_HTML) {
            val top = place.namespace < 0
            // Inside an element of the format whose name is HTML's, `<td>` or `<nav>`, the svg or math
            // patch this reading supposes is not one an author writes, and the reading from HTML judges it.
            if (!top && place.namespace != NS_HTML && place.parent !in FOREIGN_NAMES) return true
            return closesIn(
                reading.of(
                    if (top) reading.context else place.namespace,
                    leaves = false,
                    closesAll =
                        top && reading.htmlStaysOpen,
                ),
            )
        }
        val namespace = if (place.namespace < 0) NS_HTML else place.namespace
        return closesIn(reading.of(namespace, leaves = namespace != NS_HTML)) && (namespace != NS_HTML || fits(html, place))
    }

    fun valueStartAt(at: Int): Int = indexOfSpan(at, attributeStarts, attributeEnds).let { if (it >= 0) attributeStarts[it] else -1 }

    fun attributeAt(at: Int): String? = indexOfSpan(at, attributeStarts, attributeEnds).takeIf { it >= 0 }?.let { attributeNames[it] }

    fun loadsCodeAt(placeholder: Int): Boolean = indexOfSpan(placeholder, attributeStarts, attributeEnds).let { it >= 0 && codeUrl(it) }

    fun tagStartAt(placeholder: Int): Int = indexOfSpan(placeholder, tagStarts, tagEnds).let { if (it >= 0) tagStarts[it] else -1 }

    fun nameStartAt(placeholder: Int): Int = indexOfSpan(placeholder, nameStarts, nameEnds).let { if (it >= 0) nameStarts[it] else -1 }

    // Their value is a signal path the client follows without a guard (bind.ts, ref.ts, indicator.ts).
    private val signalPaths = CatalogNames.signalAttributes

    private fun datastar(lower: String): Boolean {
        val prefix = prefixes.firstOrNull { lower.startsWith(it) } ?: return false
        val rest = lower.substring(prefix.length)
        val name = rest.substringBefore(':').substringBefore("__")
        return name in ElementsGuard.evaluatedAttributes || name in signalPaths || name == MATCH_MEDIA
    }

    /** The span [at] is in, or -1. Spans are found in order and never overlap, so the last to start at or before it is the one. */
    private fun indexOfSpan(
        at: Int,
        starts: List<Int>,
        ends: List<Int>,
    ): Int {
        val i = lastAtOrBefore(starts, at)
        return if (i >= 0 && at < ends[i]) i else -1
    }
}

/**
 * Whether [html], written into HTML at [place], keeps the shape the walk reads it in, where the
 * walk does not follow the tree builder: its table parts stand in its own table or in the element
 * that holds them, and a `<table>` does not stand in a table mode, which it closes; no end tag of it
 * reaches past an element that bounds the search, past a block a formatting element holds, or past
 * a formatting element of another, which the browser opens again after it; no start tag of it closes
 * the format's `p` while it has an element open; and it has no form where the format has one, whose
 * form pointer the browser keeps.
 */
private fun fits(
    html: String,
    place: TextPlace,
): Boolean = ValueShape(place).also { scanMarkup(html, it, tracked = true) }.fits

/** The table parts, each with the elements that hold it. */
private val TABLE_PARTS_IN =
    mapOf(
        "td" to setOf("tr"),
        "th" to setOf("tr"),
        "tr" to setOf("table", "tbody", "thead", "tfoot"),
        "col" to setOf("colgroup"),
        "caption" to setOf("table"),
        "colgroup" to setOf("table"),
        "tbody" to setOf("table"),
        "thead" to setOf("table"),
        "tfoot" to setOf("table"),
    )

/**
 * The table parts that do no harm straight at a patch's top level or in a `<template>`, where the
 * browser drops them or reads them as a table's: not a cell, which takes the format's rows with it,
 * and not a bare `<col>`, which turns all that follows into a column group that drops it.
 */
private val TOP_LEVEL_PARTS = setOf("tr", "thead", "tbody", "tfoot", "caption", "colgroup")

/** The elements whose content the tree builder reads in a table mode, where a `<table>` closes the open one. */
private val TABLE_MODES = setOf("table", "tbody", "thead", "tfoot", "tr", "colgroup")

/** The elements that bound the search of an end tag in scope, so it cannot close what is open outside them. */
private val SCOPE_BOUNDS = setOf("applet", "caption", "html", "marquee", "object", "table", "td", "th", "template")

/** The elements that bound the search of a table's end tags, in table scope. */
private val TABLE_SCOPE_BOUNDS = setOf("html", "table", "template")

private val FORMATTING = setOf("a", "b", "big", "code", "em", "font", "i", "nobr", "s", "small", "strike", "strong", "tt", "u")

/** Reads the start and end tags of a value for [fits], keeping the elements it opens as they open and close. */
private class ValueShape(
    private val place: TextPlace,
) : MarkupVisitor {
    var fits = true
    private val open = ArrayList<String>()

    // Whether a `p` of the format is still open around the value, for a start tag of it to close.
    private var outerParagraph = place.paragraphInScope

    // The element a table part or a form of the value goes into: its own innermost table element, or where it lands.
    private fun tableContext(): String? = open.lastOrNull { it == "table" || it in TABLE_PARTS_IN || it == "template" } ?: place.parent

    override fun attribute(
        tag: String,
        name: String,
        nameStart: Int,
        valueStart: Int,
        valueEnd: Int,
        quoted: Boolean,
    ) = Unit

    override fun startTag(
        name: String,
        start: Int,
        end: Int,
        selfClosing: Boolean,
        rawText: Boolean,
    ) {
        val holders = TABLE_PARTS_IN[name]
        val context = tableContext()
        val topLevel = (context == null || context == "template") && name in TOP_LEVEL_PARTS
        if (holders != null && "table" !in open && context !in holders && !topLevel) fits = false
        // In a table mode a `<table>` closes the open one first.
        if (name == "table" && context in TABLE_MODES) fits = false
        if (name == "form" && place.formOpen) fits = false
        // A start tag that closes the format's `p` closes all the value has open inside it as well, and
        // the value's end tags for those then reach the format.
        // Inside the value's own svg or math, a `<section>` is an element of that namespace and closes no `p`.
        if (outerParagraph && (name in CLOSES_P || name == "li" || name == "dd" || name == "dt") &&
            open.none { it == "p" || it in SCOPE_BOUNDS || it == "button" || it == "svg" || it == "math" }
        ) {
            if (open.isNotEmpty()) fits = false
            outerParagraph = false
        }
        if (name !in VOID) open += name
    }

    override fun endTag(
        name: String,
        start: Int,
        end: Int,
    ) {
        if (name == "form" && place.formOpen) fits = false
        val at = open.lastIndexOf(name)
        if (at < 0) return
        val inside = open.subList(at + 1, open.size)
        if (name in FORMATTING && inside.any { it in SPECIAL }) fits = false
        // A formatting element another end tag closes is opened again around the text after it.
        if (inside.any { it in FORMATTING }) fits = false
        // An object, a table or a cell opened inside bounds the end tag's search, which then closes
        // nothing; a table's own end tags search only past a table or a template, and close its cells.
        val bounds = if (name == "table" || name in TABLE_PARTS_IN) TABLE_SCOPE_BOUNDS else SCOPE_BOUNDS
        if (inside.any { it in bounds }) fits = false
        open.subList(at, open.size).clear()
    }
}

/** The names of svg and MathML elements, inside which a reading from an svg or a math judges a value. */
private val FOREIGN_NAMES =
    (
        "a animate animatemotion animatetransform circle clippath defs desc ellipse feblend fecolormatrix " +
            "fecomponenttransfer fecomposite feconvolvematrix fediffuselighting fedisplacementmap fedistantlight " +
            "fedropshadow feflood fefunca fefuncb fefuncg fefuncr fegaussianblur feimage femerge femergenode " +
            "femorphology feoffset fepointlight fespecularlighting fespotlight fetile feturbulence filter foreignobject " +
            "g image line lineargradient marker mask metadata mpath path pattern polygon polyline radialgradient rect " +
            "script set stop style svg switch symbol text textpath title tspan use view " +
            "math maction annotation annotation-xml menclose merror mfenced mfrac mi mmultiscripts mn mo mover mpadded " +
            "mphantom mprescripts mroot mrow ms mspace msqrt mstyle msub msubsup msup mtable mtd mtext mtr munder " +
            "munderover semantics none mglyph malignmark"
    ).split(" ").toSet()
