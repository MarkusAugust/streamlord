package io.github.markusaugust.streamlord.core.domain

/**
 * The `$` trap, hunted in HTML written as a string.
 *
 * A Kotlin template such as `"""<div data-text="$count">"""` compiles whenever a `count` happens
 * to be in scope, and then ships `data-text=""`, which the browser ignores without a word. This
 * guard walks the tags of the HTML, finds every `data-*` attribute whose value Datastar
 * evaluates as an expression, and runs [ExpressionGuard] over it. The scan is small and pure:
 * no DOM, no allocation beyond the substrings it checks.
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
     * The attribute prefixes the guard recognises, longest first. `data-` for the standard bundle
     * and `data-star-` for the aliased one; add your own alias here if you build a bundle with
     * another, and set `DatastarAttributes.prefix` in `streamlord-html` to match.
     */
    @Volatile
    public var prefixes: List<String> = listOf("data-star-", "data-")

    private val ENTITY = Regex("&(amp|lt|gt|quot|apos|#39|#34);")
    private val ENTITIES = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "#39" to "'", "#34" to "\"")

    /**
     * kotlinx.html and every template engine escape attribute values; the expression is what the
     * browser decodes. One pass, as the browser does it, so `&amp;lt;` is `&lt;` and not `<`.
     */
    private fun decode(value: String): String = if ('&' in value) ENTITY.replace(value) { ENTITIES.getValue(it.groupValues[1]) } else value

    /** Returns [elements] untouched, or throws [InterpolatedExpressionException] for the first broken attribute. */
    public fun check(elements: String): String {
        var i = 0
        val n = elements.length
        while (i < n) {
            val lt = elements.indexOf('<', i)
            if (lt < 0) break
            if (elements.startsWith("<!--", lt)) {
                val close = elements.indexOf("-->", lt + 4)
                i = if (close < 0) n else close + 3
                continue
            }
            val first = elements.getOrNull(lt + 1)
            if (first == null || !first.isLetter()) {
                i = lt + 1
                continue
            }
            i = scanTag(elements, lt + 1)
            // Inside <script> and <style> a '<' is text, not a tag: skip to the closing tag.
            RAW_TEXT
                .firstOrNull {
                    elements.regionMatches(lt + 1, it, 0, it.length, ignoreCase = true) &&
                        !elements.getOrNull(lt + 1 + it.length).isNamePart()
                }?.let { tag ->
                    val close = elements.indexOf("</$tag", i, ignoreCase = true)
                    i = if (close < 0) n else close
                }
        }
        return elements
    }

    private val RAW_TEXT = listOf("script", "style")

    private fun Char?.isNamePart(): Boolean = this != null && (isLetterOrDigit() || this == '-')

    /** Walks the attributes of one tag, starting after `<`, checking as it goes; returns the offset past `>`. */
    private fun scanTag(
        html: String,
        from: Int,
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
                            checkAttribute(name, html.substring(k + 1, end))
                            i = if (close < 0) n else close + 1
                        } else {
                            val start = k
                            while (k < n && !html[k].isWhitespace() && html[k] != '>') k++
                            checkAttribute(name, html.substring(start, k))
                            i = k
                        }
                    } else {
                        checkAttribute(name, null)
                    }
                }
            }
        }
        return n
    }

    private fun checkAttribute(
        name: String,
        value: String?,
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
