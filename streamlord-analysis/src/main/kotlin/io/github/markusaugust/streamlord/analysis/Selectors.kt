package io.github.markusaugust.streamlord.analysis

/*
 * Collects element ids and class names declared in a file, for selector completion.
 * `id = "x"` in kotlinx.html and `id="x"` in HTML both match; classes are split on whitespace.
 */

private val ID = Regex("""\bid\s*=\s*"([A-Za-z_][\w.:-]*)"""")
private val CLASS = Regex("""\b(?:class|classes)\s*=\s*"([^"]+)"""")
private val CLASSES_SET = Regex("""\bclasses\s*=\s*setOf\(([^)]*)\)""")
private val STRING = Regex(""""([^"]+)"""")

public data class Selectors(
    val ids: Set<String>,
    val classes: Set<String>,
) {
    public companion object {
        public val EMPTY: Selectors = Selectors(emptySet(), emptySet())
    }
}

public fun collectSelectors(src: String): Selectors {
    val ids = LinkedHashSet<String>()
    val classes = LinkedHashSet<String>()
    for (m in ID.findAll(src)) if ("\${" !in m.groupValues[1]) ids += m.groupValues[1]
    for (m in CLASS.findAll(src)) {
        for (c in m.groupValues[1].split(Regex("""\s+"""))) if (c.isNotEmpty() && '$' !in c && '{' !in c) classes += c
    }
    for (m in CLASSES_SET.findAll(src)) for (s in STRING.findAll(m.groupValues[1])) classes += s.groupValues[1]
    return Selectors(ids, classes)
}
