package io.github.markusaugust.streamlord.core.domain

/**
 * The name of an HTML attribute written with capitals, which the browser lowercases: what HTML
 * may spell it as, and the object form that keeps its capitals. The DSL refuses such a name in the
 * key of `data-attr` and `data-animate` with these, and the analysis offers them as its fixes. The
 * VS Code extension's twin is held to the same cases by the shared corpus.
 */
public object AttributeNames {
    private val UPPER = Regex("[A-Z]")
    private val JS_NAME = Regex("[A-Za-z_$][A-Za-z0-9_$]*")

    /**
     * The spellings HTML may have for [name]: lowercased when it has a hyphen already
     * (`aria-Label`), else kebab-case or one word, which only the attribute tells apart
     * (`ariaLabel` is `aria-label`, `tabIndex` is `tabindex`).
     */
    public fun spellings(name: String): List<String> {
        if ('-' in name) return listOf(name.lowercase())
        val kebab = name.replace(UPPER) { "-" + it.value.lowercase() }.removePrefix("-")
        return listOf(kebab, name.lowercase()).distinct()
    }

    /** The object form with [name] as its key, `{viewBox: ...}`, quoted when it is no JavaScript name. */
    public fun objectForm(name: String): String = if (JS_NAME.matches(name)) "{$name: ...}" else "{'$name': ...}"
}
