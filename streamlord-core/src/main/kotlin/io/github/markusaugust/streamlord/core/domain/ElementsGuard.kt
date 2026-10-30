package io.github.markusaugust.streamlord.core.domain

import io.github.markusaugust.streamlord.core.InternalStreamlordApi
import io.github.markusaugust.streamlord.core.catalog.CatalogNames
import io.github.markusaugust.streamlord.core.client.asciiLowercase
import io.github.markusaugust.streamlord.core.client.decodeEntities
import io.github.markusaugust.streamlord.core.client.isHtmlSpace
import io.github.markusaugust.streamlord.core.client.regionMatchesAsciiIgnoreCase

/**
 * The `$` trap, hunted in HTML written as a string.
 *
 * A Kotlin template such as `"""<div data-text="$count">"""` compiles whenever a `count` happens
 * to be in scope, and then ships `data-text=""`, which the browser ignores without a word. This
 * guard walks the tags of the HTML, finds every `data-*` attribute whose value Datastar
 * evaluates as an expression, and runs [ExpressionGuard] over it. The walk is the one in
 * [scanMarkup]: no DOM, and no copy of the markup.
 *
 * It is off by default. Turn it on with `Streamlord(guardElements = true)`, which applies it to
 * every element patch that leaves through that instance, or call [check] yourself, for example
 * in the tests of your markup functions.
 *
 * ```kotlin
 * ElementsGuard.check($$"""<button data-on:click="$count++">Raise</button>""")   // passes
 * ElementsGuard.check("""<button data-on:click="$count++">Raise</button>""")     // "++" arrived: throws
 * ```
 */
public object ElementsGuard {
    /**
     * The Datastar attributes (plugin names without prefix, key or modifiers) whose value is an
     * expression: `valueKind: "expression"` in the catalog. Pro attributes are included because
     * their names are public.
     */
    public val expressionAttributes: Set<String> = CatalogNames.expressionAttributes

    /**
     * The attributes whose value Datastar evaluates as JavaScript: [expressionAttributes], and the
     * four whose value is a signal filter, which the client parses as JSON and, when that fails,
     * runs as code (`jsStrToObject` in text.ts). The value of every other attribute is read as it
     * stands: a signal name, a nonce, a list of attribute names.
     */
    public val evaluatedAttributes: Set<String> = expressionAttributes + CatalogNames.filterAttributes

    /**
     * Every Datastar attribute name, free and Pro, as the catalog lists them. A `data-*` name
     * that is one letter away from one of these, and is not itself one of them, is a typo the
     * browser would ignore without a word: `data-onn:click`, `data-signal:x`, `data-effekt`.
     * Anything further away is taken to be your own attribute and left alone.
     */
    public val attributes: Set<String> = CatalogNames.attributes

    /**
     * The attribute prefixes recognised unless told otherwise, longest first: `data-` for the
     * standard bundle and `data-star-` for the aliased one. A bundle built with another alias
     * passes its own list to [check], or sets `attributePrefixes` on `Streamlord`.
     */
    public val defaultPrefixes: List<String> = listOf(CatalogNames.PREFIX, CatalogNames.ALIASED_PREFIX).sortedByDescending { it.length }

    /**
     * Returns [elements] untouched, or throws for the first broken attribute:
     * [InterpolatedExpressionException] for an expression a Kotlin template ate,
     * [MistypedAttributeException] for a `data-*` name one letter from a Datastar one. Both are
     * [io.github.markusaugust.streamlord.core.StreamlordException]s.
     */
    public fun check(
        elements: String,
        prefixes: List<String> = defaultPrefixes,
    ): String = check(elements, prefixes, foreign = false)

    /**
     * [check], reading the markup as the inside of an `<svg>` or `<math>` too when [foreign] says
     * the patch lands in one, or when the markup opens one itself.
     */
    internal fun check(
        elements: String,
        prefixes: List<String>,
        foreign: Boolean,
    ): String {
        // Longest first, so `data-x-` is tried before `data-` whatever order the caller used.
        val ordered = if (prefixes === defaultPrefixes) prefixes else prefixes.sortedByDescending { it.length }
        val roots = ArrayList<Int>()
        scanMarkup(
            elements,
            object : MarkupVisitor {
                override fun attribute(
                    tag: String,
                    name: String,
                    valueStart: Int,
                    valueEnd: Int,
                    quoted: Boolean,
                ) = checkAttribute(name, if (valueStart < 0) null else elements.substring(valueStart, valueEnd), ordered)

                override fun tag(
                    start: Int,
                    end: Int,
                ) {
                    if (foreignRoot(elements, start) != null) roots += start
                }
            },
        )
        // Inside an svg or a math, a title or a textarea holds elements rather than text. Read that
        // way only there, since in HTML the markup they show is text, and often meant so.
        for (cdata in listOf(false, true)) {
            for (span in if (foreign) listOf(elements) else foreignSpans(elements, roots, cdata)) {
                scanMarkup(
                    span,
                    object : MarkupVisitor {
                        override fun attribute(
                            tag: String,
                            name: String,
                            valueStart: Int,
                            valueEnd: Int,
                            quoted: Boolean,
                        ) = checkAttribute(name, if (valueStart < 0) null else span.substring(valueStart, valueEnd), ordered)
                    },
                    foreign = true,
                    cdata = cdata,
                )
            }
        }
        return elements
    }

    private fun checkAttribute(
        name: String,
        value: String?,
        prefixes: List<String>,
    ) {
        val lower = name.asciiLowercase()
        val prefix = prefixes.firstOrNull { lower.startsWith(it) } ?: return
        val rest = lower.substring(prefix.length)
        val plugin = rest.substringBefore(':').substringBefore("__")
        if (plugin !in attributes) {
            // A key or modifier (data-onn:click, data-signal__ifmissing) makes a custom attribute implausible,
            // so any one-letter neighbour is a typo. A bare name is a different matter: data-test, data-kind,
            // data-animated and data-effects are honest words a letter away from Datastar's. Only a swapped
            // letter in a long name (data-indicater, data-computer) is judged there; plurals and past tenses,
            // which add a letter, are left alone.
            nearAttribute(plugin, qualified = rest.length > plugin.length)
                ?.let { near ->
                    throw MistypedAttributeException(name, prefix + near + name.substring(prefix.length + plugin.length))
                }
            return
        }
        if (plugin !in expressionAttributes) return
        // kotlinx.html and every template engine escape attribute values, some with numeric references
        // (`&#x27;`, `&#61;`); the expression is what the browser decodes, in one pass.
        ExpressionGuard.check(decodeEntities(value ?: "").text, name)
    }

    /**
     * The Datastar attribute [plugin], a name after the prefix that is none, is taken for a typo of,
     * or null. [qualified] says whether a key or a modifier follows it, which makes a custom
     * attribute implausible, so any name one letter away counts. A bare name (data-test, data-kind,
     * data-effects) is an honest word more often than not: only a swapped letter in a long name counts.
     * The analysis reports the same names.
     */
    @InternalStreamlordApi
    public fun nearAttribute(
        plugin: String,
        qualified: Boolean,
    ): String? = attributes.firstOrNull { oneEditAway(plugin, it) && (qualified || (it.length >= LONG_NAME && it.length == plugin.length)) }

    /** Below this length a Datastar name has too many honest neighbours (test, kind, once, unit) to judge a bare `data-*` at all. */
    private const val LONG_NAME = 6

    /** One insertion, deletion or substitution apart; no allocation. */
    private fun oneEditAway(
        a: String,
        b: String,
    ): Boolean {
        if (a == b) return false
        val (short, long) = if (a.length <= b.length) a to b else b to a
        if (long.length - short.length > 1) return false
        var i = 0
        var j = 0
        var edits = 0
        while (i < short.length && j < long.length) {
            if (short[i] == long[j]) {
                i++
                j++
                continue
            }
            if (++edits > 1) return false
            if (short.length == long.length) i++
            j++
        }
        return edits + (long.length - j) <= 1
    }
}

/**
 * Raised by [ElementsGuard] for a `data-*` attribute one letter away from a Datastar attribute,
 * such as `data-onn:click`. The browser would ignore it silently; Datastar never sees it.
 *
 * @property attribute The attribute as written.
 * @property suggestion The attribute it is one letter away from, with the same key and modifiers.
 */
public class MistypedAttributeException(
    public val attribute: String,
    public val suggestion: String,
) : io.github.markusaugust.streamlord.core.StreamlordException(
        "Attribute $attribute is not a Datastar attribute, but is one letter away from $suggestion. " +
            "Did you mean that? The browser ignores an unknown data-* attribute without a word.",
    )

/** `svg` or `math` when the tag at [start] opens one of them, otherwise `null`. */
private fun foreignRoot(
    html: String,
    start: Int,
): String? =
    listOf("svg", "math").firstOrNull {
        html.regionMatchesAsciiIgnoreCase(start + 1, it) &&
            html.getOrNull(start + 1 + it.length).let { c -> c == null || c == '/' || c == '>' || c.isHtmlSpace() }
    }

/** A CDATA section, to its `]]>` or the end. */
private val CDATA = Regex("""<!\[CDATA\[[\s\S]*?(?:]]>|$)""")

/**
 * Each svg or math the HTML reading found, from its start tag to its own end tag, nested ones
 * counted, or to the end of the markup when it is never closed. With [cdata], an end tag inside a
 * CDATA section is text.
 */
private fun foreignSpans(
    html: String,
    roots: List<Int>,
    cdata: Boolean,
): List<String> {
    val sections = if (cdata) CDATA.findAll(html).map { it.range }.toList() else emptyList()
    val spans = ArrayList<String>()
    var covered = 0
    for (start in roots) {
        if (start < covered) continue
        val name = foreignRoot(html, start) ?: continue
        val token = Regex("<(/?)$name(?=[\\s/>]|$)", RegexOption.IGNORE_CASE)
        var depth = 0
        var end = html.length
        for (m in token.findAll(html, start)) {
            if (sections.any { m.range.first in it }) continue
            depth += if (m.groupValues[1].isEmpty()) 1 else -1
            if (depth == 0) {
                end = html.indexOf('>', m.range.last).let { if (it < 0) html.length else it + 1 }
                break
            }
        }
        spans += html.substring(start, end)
        covered = end
    }
    return spans
}
