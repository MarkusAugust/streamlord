package io.github.markusaugust.streamlord.analysis

/*
 * Collects signal names declared anywhere in a source file, for completion. Heuristic and
 * generous: better to offer a name twice than to miss it.
 */

public enum class SourceLanguage { KOTLIN, HTML }

private val KOTLIN_PATTERNS =
    listOf(
        Regex(
            """\b(?:signal|set|setExpr|increment|decrement|toggle|not|dataBind|dataIndicator|dataRef|dataComputed|dataMatchMedia)\(\s*"([A-Za-z_][A-Za-z0-9_.]*)"""",
        ),
        Regex("""\bdataSignals\(\s*"([A-Za-z_][A-Za-z0-9_.]*)"\s*,"""),
        Regex("""\$\{'\$'\}([A-Za-z_][A-Za-z0-9_.]*)"""),
        Regex("""\\\$([A-Za-z_][A-Za-z0-9_.]*)"""),
    )

/** Declarations in markup. These also run over Kotlin, where they only ever match inside HTML strings. */
private val KEYED_ATTRIBUTE = Regex("""data-(?:star-)?(?:signals|bind|indicator|ref|computed|match-media):([A-Za-z_][A-Za-z0-9_.-]*)""")
private val VALUE_ATTRIBUTE = Regex("""data-(?:star-)?(?:bind|indicator|ref)(?:__[^=\s]*)?="([A-Za-z_][A-Za-z0-9_.]*)"""")
private val OBJECT_ATTRIBUTE = Regex("""data-(?:star-)?signals(?:__[^=\s]*)?="\{([^"]*)\}"""")
private val OBJECT_KEY = Regex("""(?:^|[{,])\s*([A-Za-z_][A-Za-z0-9_]*)\s*:""")

/** A bare `$name` is a signal in markup; in Kotlin it is a template, so it stays out of the Kotlin list. */
private val BARE_SIGNAL = Regex("""\$([A-Za-z_][A-Za-z0-9_.]*)""")

private val SERIALIZABLE_CLASS = Regex("""@Serializable\s*(?:\([^)]*\))?\s*(?:data\s+)?class\s+\w+\s*\(([^)]*)\)""")
private val PROPERTY = Regex("""\b(?:val|var)\s+([A-Za-z_][A-Za-z0-9_]*)\s*:""")

private val PAIR_CALLS = setOf("dataSignals", "patchSignals", "removeSignals", "respondSignals", "datastarSignals")
private val PAIR = Regex(""""([A-Za-z_][A-Za-z0-9_.]*)"\s+to\b""")
private val NAME = Regex("""^"([A-Za-z_][A-Za-z0-9_.]*)"$""")

public fun collectSignals(
    src: String,
    language: SourceLanguage,
): Set<String> {
    val out = LinkedHashSet<String>()
    if (language == SourceLanguage.KOTLIN) {
        for (site in findCallSites(src, PAIR_CALLS)) {
            val positional = site.positional
            positional.forEachIndexed { idx, a ->
                for (pair in PAIR.findAll(a.text)) out += pair.groupValues[1]
                val name = NAME.find(a.text)?.groupValues?.get(1) ?: return@forEachIndexed
                // removeSignals("a", "b") and dataSignals("name", "expression") take names positionally.
                if (site.name == "removeSignals" ||
                    (site.name == "dataSignals" && idx == 0 && positional.getOrNull(1)?.string != null)
                ) {
                    out += name
                }
            }
        }
        for (re in KOTLIN_PATTERNS) for (m in re.findAll(src)) out += m.groupValues[1]
    }
    // A keyed attribute may carry modifiers after the key: data-signals:foo-bar__ifmissing.
    for (m in KEYED_ATTRIBUTE.findAll(src)) out += toCamel(m.groupValues[1].substringBefore("__"))
    for (m in VALUE_ATTRIBUTE.findAll(src)) out += toCamel(m.groupValues[1])
    for (m in OBJECT_ATTRIBUTE.findAll(src)) for (key in OBJECT_KEY.findAll(m.groupValues[1])) out += key.groupValues[1]
    if (language == SourceLanguage.HTML) {
        for (m in BARE_SIGNAL.findAll(src)) out += toCamel(m.groupValues[1])
    }
    if (language == SourceLanguage.KOTLIN) {
        for (m in SERIALIZABLE_CLASS.findAll(src)) for (p in PROPERTY.findAll(m.groupValues[1])) out += p.groupValues[1]
    }
    // The placeholder the analysis writes for a Kotlin template is never a signal, whatever file it turns up in.
    out.removeAll { PLACEHOLDER in it }
    return out
}
