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
    public val expressionAttributes: Set<String> = setOf(
        "attr", "class", "computed", "effect", "init", "on", "on-intersect", "on-interval", "on-signal-patch",
        "show", "signals", "style", "text",
        "animate", "custom-validity", "match-media", "on-raf", "on-resize", "replace-url", "view-transition",
    )

    /**
     * Every Datastar attribute name, free and Pro, as the catalog lists them. A `data-*` name
     * that is one letter away from one of these, and is not itself one of them, is a typo the
     * browser would ignore without a word: `data-onn:click`, `data-signal:x`, `data-txt`.
     * Anything further away is taken to be your own attribute and left alone.
     */
    public val attributes: Set<String> = expressionAttributes + setOf(
        "bind", "ignore", "ignore-morph", "indicator", "json-signals", "on-signal-patch-filter", "preserve-attr", "ref", "nonce",
        "persist", "query-string", "scroll-into-view",
    )

    private val PREFIXES = listOf("data-star-", "data-")

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
        }
        return elements
    }

    /** Walks the attributes of one tag, starting after `<`, checking as it goes; returns the offset past `>`. */
    private fun scanTag(html: String, from: Int): Int {
        var i = from
        val n = html.length
        while (i < n && !html[i].isWhitespace() && html[i] != '>' && html[i] != '/') i++
        while (i < n) {
            val c = html[i]
            when {
                c == '>' -> return i + 1
                c.isWhitespace() || c == '/' -> i++
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

    private fun checkAttribute(name: String, value: String?) {
        val lower = name.lowercase()
        val prefix = PREFIXES.firstOrNull { lower.startsWith(it) } ?: return
        val plugin = lower.substring(prefix.length).substringBefore(':').substringBefore("__")
        if (plugin !in attributes) {
            attributes.firstOrNull { distance(plugin, it) == 1 }?.let { near ->
                throw MistypedAttributeException(name, prefix + near + name.substring(prefix.length + plugin.length))
            }
            return
        }
        if (plugin !in expressionAttributes) return
        ExpressionGuard.check(value ?: "", name)
    }

    /** Levenshtein distance, capped at 2 since only 1 matters. */
    private fun distance(a: String, b: String): Int {
        if (kotlin.math.abs(a.length - b.length) > 1) return 2
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            val t = prev
            prev = cur
            cur = t
        }
        return prev[b.length]
    }
}

/**
 * Raised by [ElementsGuard] for a `data-*` attribute one letter away from a Datastar attribute,
 * such as `data-onn:click`. The browser would ignore it silently; Datastar never sees it.
 *
 * @property attribute The attribute as written.
 * @property suggestion The attribute it is one letter away from, with the same key and modifiers.
 */
public class MistypedAttributeException(public val attribute: String, public val suggestion: String) :
    io.github.markusaugust.streamlord.core.StreamlordException(
        "Attribute $attribute is not a Datastar attribute, but is one letter away from $suggestion. " +
            "Did you mean that? The browser ignores an unknown data-* attribute without a word.",
    )
