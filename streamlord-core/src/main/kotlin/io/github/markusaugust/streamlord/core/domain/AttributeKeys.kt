package io.github.markusaugust.streamlord.core.domain

/**
 * What a key of a Datastar attribute may be, for the DSL that writes one and the analysis that
 * reads a call before it runs: how a name with capitals is written in kebab-case, which keys the
 * client would cut in the wrong place, and how an HTML attribute with capitals is spelled. The DSL
 * throws with these reasons and the analysis reports them, so the two say the same thing; the VS
 * Code extension's twin is held to them by the shared corpus.
 */
public object AttributeKeys {
    private val UPPER = Regex("[A-Z]")
    private val JS_NAME = Regex("[A-Za-z_$][A-Za-z0-9_$]*")

    /** `fooBar` -> `foo-bar`, `myURL` -> `my-u-r-l`: one hyphen per capital, so Datastar's camel conversion gives the name back. */
    public fun kebab(name: String): String {
        val out = name.replace(UPPER) { "-" + it.value.lowercase() }
        // Only the hyphen a leading capital introduced is dropped; a CSS custom property keeps its `--`.
        return if (name.firstOrNull()?.isUpperCase() == true) out.removePrefix("-") else out
    }

    /** Why [name] cannot be a key, or null: the client splits an attribute name at the first `__`. */
    public fun modifierStartIn(name: String): String? = if ("__" in name) "contains __, where Datastar starts reading modifiers" else null

    /**
     * Why [key] cannot stand before [modifiers], or null: a key that ends in `_` runs into the `__`
     * of the modifiers after it, so `b_` with `__case.pascal` is `b___case.pascal`, read as the key
     * `b` and a modifier `_case`. The reason names no modifier, so that it reads the same wherever
     * the modifiers are known.
     */
    public fun runsIntoModifiers(
        key: String,
        modifiers: String,
    ): String? =
        if (modifiers.isNotEmpty() && key.endsWith('_')) {
            "ends in _, which runs into the __ of the modifiers after it, so Datastar's client would read the key as ${key.dropLast(1)}"
        } else {
            null
        }

    /**
     * The spellings HTML may have for an attribute [name] written with capitals: lowercased when it
     * has a hyphen already (`aria-Label`), else kebab-case or one word, which only the attribute
     * tells apart (`ariaLabel` is `aria-label`, `tabIndex` is `tabindex`).
     */
    public fun spellings(name: String): List<String> =
        if ('-' in name) listOf(name.lowercase()) else listOf(kebab(name), name.lowercase()).distinct()

    /** The object form with [name] as its key, `{viewBox: ...}`, quoted when it is no JavaScript name. */
    public fun objectForm(name: String): String = if (JS_NAME.matches(name)) "{$name: ...}" else "{'$name': ...}"

    /** Why the attribute [name] cannot be a key, or null: the browser lowercases a capital, and [helper]'s object form keeps it. */
    public fun capitalIn(
        name: String,
        helper: String,
    ): String? =
        if (UPPER.containsMatchIn(name)) {
            "has a capital, which the browser lowercases in an attribute name; write it as HTML has it, " +
                "${spellings(name).joinToString(" or ")}, or, for an attribute that has capitals such as SVG's viewBox, " +
                "the object form $helper(\"${objectForm(name)}\")"
        } else {
            null
        }
}
