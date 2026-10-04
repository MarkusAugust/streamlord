package io.github.markusaugust.streamlord.core.domain

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
     * expression. Mirrors `valueKind: "expression"` in the catalog, and the catalog test holds
     * the two to the same list. Pro attributes are included because their names are public.
     */
    public val expressionAttributes: Set<String> =
        setOf(
            "attr",
            "class",
            "computed",
            "effect",
            "init",
            "on",
            "on-intersect",
            "on-interval",
            "on-signal-patch",
            "show",
            "signals",
            "style",
            "text",
            "animate",
            "custom-validity",
            "match-media",
            "on-raf",
            "on-resize",
            "replace-url",
            "view-transition",
        )

    /**
     * Every Datastar attribute name, free and Pro, as the catalog lists them. A `data-*` name
     * that is one letter away from one of these, and is not itself one of them, is a typo the
     * browser would ignore without a word: `data-onn:click`, `data-signal:x`, `data-txt`.
     * Anything further away is taken to be your own attribute and left alone.
     */
    public val attributes: Set<String> =
        expressionAttributes +
            setOf(
                "bind",
                "ignore",
                "ignore-morph",
                "indicator",
                "json-signals",
                "on-signal-patch-filter",
                "preserve-attr",
                "ref",
                "nonce",
                "persist",
                "query-string",
                "scroll-into-view",
            )

    /**
     * The attribute prefixes recognised unless told otherwise, longest first: `data-` for the
     * standard bundle and `data-star-` for the aliased one. A bundle built with another alias
     * passes its own list to [check], or sets `attributePrefixes` on `Streamlord`.
     */
    public val defaultPrefixes: List<String> = listOf("data-star-", "data-")

    private val ENTITY = Regex("&(?:(amp|lt|gt|quot|apos)|#(\\d+)|#[xX]([0-9a-fA-F]+));")
    private val NAMED_ENTITIES = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'")

    /**
     * kotlinx.html and every template engine escape attribute values, some with numeric entities
     * (`&#x27;`, `&#61;`); the expression is what the browser decodes. One pass, as the browser
     * does it, so `&amp;lt;` is `&lt;` and not `<`.
     */
    private fun decode(value: String): String =
        if ('&' !in value) {
            value
        } else {
            ENTITY.replace(value) { m ->
                val (named, decimal, hex) = m.destructured
                when {
                    named.isNotEmpty() -> NAMED_ENTITIES.getValue(named)
                    decimal.isNotEmpty() -> codePoint(decimal.toIntOrNull()) ?: m.value
                    else -> codePoint(hex.toIntOrNull(16)) ?: m.value
                }
            }
        }

    /** The character a numeric reference names, or `null` for a number that names none, which is left as written. */
    private fun codePoint(value: Int?): String? = value?.takeIf(Character::isValidCodePoint)?.let { Character.toString(it) }

    /**
     * Returns [elements] untouched, or throws for the first broken attribute:
     * [InterpolatedExpressionException] for an expression a Kotlin template ate,
     * [MistypedAttributeException] for a `data-*` name one letter from a Datastar one. Both are
     * [io.github.markusaugust.streamlord.core.StreamlordException]s.
     */
    public fun check(
        elements: String,
        prefixes: List<String> = defaultPrefixes,
    ): String {
        // Longest first, so `data-x-` is tried before `data-` whatever order the caller used.
        val ordered = if (prefixes === defaultPrefixes) prefixes else prefixes.sortedByDescending { it.length }
        scanMarkup(
            elements,
            object : MarkupVisitor {
                override fun attribute(
                    name: String,
                    valueStart: Int,
                    valueEnd: Int,
                    quoted: Boolean,
                ) = checkAttribute(name, if (valueStart < 0) null else elements.substring(valueStart, valueEnd), ordered)
            },
        )
        return elements
    }

    private fun checkAttribute(
        name: String,
        value: String?,
        prefixes: List<String>,
    ) {
        val lower = name.lowercase()
        val prefix = prefixes.firstOrNull { lower.startsWith(it) } ?: return
        val rest = lower.substring(prefix.length)
        val plugin = rest.substringBefore(':').substringBefore("__")
        if (plugin !in attributes) {
            // A key or modifier (data-onn:click, data-signal__ifmissing) makes a custom attribute implausible,
            // so any one-letter neighbour is a typo. A bare name is a different matter: data-test, data-kind,
            // data-animated and data-effects are honest words a letter away from Datastar's. Only a swapped
            // letter in a long name (data-indicater, data-computer) is judged there; plurals and past tenses,
            // which add a letter, are left alone.
            val qualified = rest.length > plugin.length
            attributes
                .firstOrNull { oneEditAway(plugin, it) && (qualified || (it.length >= LONG_NAME && it.length == plugin.length)) }
                ?.let { near ->
                    throw MistypedAttributeException(name, prefix + near + name.substring(prefix.length + plugin.length))
                }
            return
        }
        if (plugin !in expressionAttributes) return
        ExpressionGuard.check(decode(value ?: ""), name)
    }

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
