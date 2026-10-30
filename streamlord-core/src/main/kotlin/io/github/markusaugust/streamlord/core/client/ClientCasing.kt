package io.github.markusaugust.streamlord.core.client

import io.github.markusaugust.streamlord.core.InternalStreamlordApi

/**
 * How Datastar 1.0.4's client cases an attribute key, and which key gives a name back. The DSL
 * writes keys with it and the analysis reads them with it; `conformance/client/casing.json` and
 * `attribute-keys.json`, the client's own functions run over fixed inputs, hold it to the client.
 */
@InternalStreamlordApi
public object ClientCasing {
    private val HYPHEN_LOWER = Regex("-[a-z]")
    private val CAPITAL = Regex("[A-Z]")

    /** The cases the client has a function for (`caseFns` in text.ts); any other leaves a key as it is. */
    public val CASES: List<String> = listOf("camel", "snake", "pascal")

    /** `fooBar` -> `foo-bar`, `myURL` -> `my-u-r-l`: one hyphen per capital, so the client's camel gives the name back. */
    public fun kebab(name: String): String {
        val out = name.replace(CAPITAL) { "-" + it.value.lowercase() }
        // Only the hyphen a leading capital introduced is dropped; a CSS custom property keeps its `--`.
        return if (name.firstOrNull() in 'A'..'Z') out.removePrefix("-") else out
    }

    /** `caseFns.camel` in text.ts: a hyphen before a lowercase letter becomes that letter's capital. */
    public fun camel(key: String): String = HYPHEN_LOWER.replace(key) { it.value.substring(1).uppercase() }

    // `kebab` in text.ts, whose `\s` is JavaScript's: Java's and the spaces Unicode adds.
    private val TEXT_KEBAB =
        listOf(
            Regex("([A-Z]+)([A-Z][a-z])"),
            Regex("([a-z0-9])([A-Z])"),
            Regex("([A-Za-z])([0-9]+)"),
            Regex("([0-9]+)([A-Za-z])"),
        )
    private val TEXT_SPACE = Regex("[\\s\\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000\\ufeff_]+")

    /**
     * `camel` in text.ts, which bind.ts reads `__prop` with: its own `kebab`, which also splits
     * letters from digits and turns spaces and `_` into a hyphen, then a capital for the character
     * after each hyphen.
     */
    public fun textCamel(name: String): String =
        TEXT_KEBAB
            .fold(name) { s, re -> re.replace(s, "$1-$2") }
            .replace(TEXT_SPACE, "-")
            .lowercase()
            .let(::capitalAfterHyphen)

    // `/-./g` in text.ts: `.` without the `u` flag is one UTF-16 unit, never a line terminator.
    private fun capitalAfterHyphen(s: String): String {
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val next = s.getOrNull(i + 1)
            if (s[i] == '-' && next != null && next !in "\n\r\u2028\u2029") {
                out.append(next.uppercase())
                i += 2
            } else {
                out.append(s[i++])
            }
        }
        return out.toString()
    }

    /** [key] under the client's function for [case] (`caseFns` in text.ts), or null when it has none. */
    private fun recase(
        key: String,
        case: String,
    ): String? =
        when (case) {
            "camel" -> camel(key)
            "snake" -> key.replace('-', '_')
            "pascal" -> if (key.isEmpty()) key else key.substring(0, 1).uppercase() + camel(key.substring(1))
            else -> null
        }

    /**
     * `modifyCasing` in text.ts: each of [cases] in turn, or [defaultCase] when none is given. A
     * case without a function, or one that gives an empty key, leaves the key as it was.
     */
    public fun modifyCasing(
        key: String,
        cases: Collection<String>?,
        defaultCase: String = "camel",
    ): String = (cases ?: listOf(defaultCase)).fold(key) { out, case -> recase(out, case)?.ifEmpty { null } ?: out }

    /**
     * The name the client reads from [written], an attribute's key with the modifiers after it, as
     * the browser hands it over: lowercased A to Z, cut at the first `__` (`parseAttributeKey` in
     * engine.ts), and recased by the values of the last `__case`, as a set, or [defaultCase].
     */
    public fun readKey(
        written: String,
        defaultCase: String = "camel",
    ): String {
        val parts = written.asciiLowercase().split("__")
        val case = parts.drop(1).map { it.split('.') }.lastOrNull { it.first() == "case" }
        return modifyCasing(parts.first(), case?.drop(1)?.distinct(), defaultCase)
    }

    /** A key to write, and the `__case` to write after it, or null for none. */
    public class KeyChoice(
        public val key: String,
        public val case: String?,
    )

    /**
     * The key, and the `__case`, that make the client read [name] back. [defaultCase] is what the
     * attribute applies without a `__case` (`keyCase` in the catalog), null for one that uses the
     * key as it stands. [explicitCase] is written as asked. A name with capitals is written in
     * kebab-case, since the browser lowercases the key, under the case its first letter asks for.
     *
     * Null when no key gives [name] back: an attribute whose key stays kebab, given a name that
     * mixes capitals with a hyphen before a lowercase letter, such as `fooBar-baz`, since camel and
     * pascal turn that hyphen into a capital as well.
     */
    public fun keyFor(
        name: String,
        explicitCase: String?,
        defaultCase: String?,
    ): KeyChoice? {
        if (!CAPITAL.containsMatchIn(name)) return KeyChoice(name, explicitCase)
        val key = kebab(name)
        if (explicitCase != null) return KeyChoice(key, explicitCase)
        if (defaultCase == null) return KeyChoice(key, null)
        val wanted = wantedCase(name)
        if (defaultCase == "kebab" && recase(key, wanted) != name) return null
        return KeyChoice(key, if (wanted == defaultCase) null else wanted)
    }

    /** The case a name's capitals ask for: pascal when it starts with one, else camel. */
    internal fun wantedCase(name: String): String = if (name.firstOrNull() in 'A'..'Z') "pascal" else "camel"

    /** What the client reads from the kebab-case key of [name] under the case its capitals ask for. */
    public fun readsAs(name: String): String = recase(kebab(name), wantedCase(name))!!
}
