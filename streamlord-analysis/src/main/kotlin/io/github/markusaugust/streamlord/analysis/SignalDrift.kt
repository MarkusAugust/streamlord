package io.github.markusaugust.streamlord.analysis

/*
 * Signals a handler reads, against signals the pages declare.
 *
 * A signal nobody reads is harmless: it sits in the browser's store and comes back untouched.
 * A signal a handler reads that no page declares is the other way round, and it is always a
 * mistake: a rename that reached the markup and not the data class, a typo in a lookup, a
 * handler kept after its page went. The reader gets a default and nobody is told.
 *
 * Neither half of that is visible in one file, so this works over a set of them.
 */

/** Where a name was read, in the file it was read in. */
public data class SignalRead(
    val name: String,
    val start: Int,
    val end: Int,
)

/** A property of a `@Serializable` class, and where its name sits. */
public data class SignalProperty(
    val name: String,
    val start: Int,
    val end: Int,
)

/**
 * What one source file says about signals.
 *
 * @property declared Signal names the file puts on a page, from markup and from the DSL.
 * @property signalTypes Types the file reads signals into, by their simple name.
 * @property classes The `@Serializable` classes the file declares, by simple name.
 * @property lookups Names read by a `Signals` accessor, such as `signals.string("query")`.
 */
public data class SignalFacts(
    val declared: Set<String>,
    val signalTypes: Set<String>,
    val classes: Map<String, List<SignalProperty>>,
    val lookups: List<SignalRead>,
)

private val SIGNAL_TYPE_PARAMETER = Regex("""\breadSignals\s*<\s*([A-Za-z_][A-Za-z0-9_]*)\s*>""")
private val SIGNAL_TYPE_DEFAULT = Regex("""\breadSignalsOr\s*\(\s*([A-Za-z_][A-Za-z0-9_]*)\s*\(""")

// Any modifier may stand between the annotation and `class`: an explicit-API module writes
// `@Serializable public data class`.
private val SERIALIZABLE_DECLARATION =
    Regex(
        """@Serializable\s*(?:\([^)]*\))?\s*(?:(?:public|internal|private|protected|open|final|abstract|sealed|value|inline|data)\s+)*class\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(""",
    )
private val PROPERTY_NAME = Regex("""\b(?:val|var)\s+([A-Za-z_][A-Za-z0-9_]*)\s*:""")

/** The `Signals` accessors that take a signal name. `path` takes several; its first is the signal. */
private val LOOKUPS = setOf("string", "boolean", "int", "long", "double", "decimal", "obj", "array", "has", "path")

/**
 * Reads [src] for everything the drift check needs.
 *
 * Declarations are collected the way completion collects them, which is deliberately generous:
 * a name offered twice costs nothing, and a name missed here would become a false warning.
 */
public fun collectSignalFacts(
    src: String,
    language: SourceLanguage,
): SignalFacts {
    if (language == SourceLanguage.HTML) {
        return SignalFacts(collectDeclaredSignals(src, language), emptySet(), emptyMap(), emptyList())
    }

    val lex = lexKotlin(src)

    val types = LinkedHashSet<String>()
    for (m in SIGNAL_TYPE_PARAMETER.findAll(src)) types += m.groupValues[1]
    for (m in SIGNAL_TYPE_DEFAULT.findAll(src)) types += m.groupValues[1]

    val lookups = ArrayList<SignalRead>()
    for (site in findCallSites(src, LOOKUPS, lex)) {
        val first = site.positional.firstOrNull()?.string ?: continue
        lookups += SignalRead(first.text, first.contentStart, first.contentEnd)
    }

    return SignalFacts(collectDeclaredSignals(src, language), types, serializableProperties(src), lookups)
}

/** Every `@Serializable` class in the file, with the names and offsets of its constructor properties. */
private fun serializableProperties(src: String): Map<String, List<SignalProperty>> {
    val out = LinkedHashMap<String, List<SignalProperty>>()
    for (declaration in SERIALIZABLE_DECLARATION.findAll(src)) {
        val name = declaration.groupValues[1]
        val open = src.indexOf('(', declaration.range.last)
        if (open < 0) continue
        val close = matchingParen(src, open)
        if (close < 0) continue

        val properties = ArrayList<SignalProperty>()
        for (property in PROPERTY_NAME.findAll(src.substring(open, close))) {
            val group = property.groups[1] ?: continue
            properties += SignalProperty(property.groupValues[1], open + group.range.first, open + group.range.last + 1)
        }
        out[name] = properties
    }
    return out
}

/** The offset of the `)` that closes the `(` at [open], or -1. Nesting only; a parenthesis in a string is rare here. */
private fun matchingParen(
    src: String,
    open: Int,
): Int {
    var depth = 0
    for (i in open until src.length) {
        when (src[i]) {
            '(' -> depth++
            ')' -> if (--depth == 0) return i
        }
    }
    return -1
}

/**
 * What the drift check saw, and what it found.
 *
 * [issues] being empty is the good answer only when [read] is not. A check that recognised no
 * reads at all reports exactly the same empty map as a project in perfect order, and the two are
 * worth telling apart: the collectors are patterns over source text, so a signals class written
 * in a shape they do not match contributes nothing and takes the finding with it. That is not a
 * hypothetical. The first version of this check called a 45-file corpus clean because its pattern
 * for a signals class did not allow a modifier before `class`, and every `@Serializable public
 * data class` in it was invisible.
 *
 * So assert on both. `report.read` tells you the check had something to judge.
 *
 * @property issues Findings by file, empty when nothing drifted.
 * @property declared Every signal name the files declare, from markup and from the DSL.
 * @property read Every signal name the files read, through a signals class or a lookup.
 */
public data class SignalDriftReport(
    val issues: Map<String, List<Issue>>,
    val declared: Set<String>,
    val read: Set<String>,
)

/**
 * Signals read in one file that nothing in [facts] declares.
 *
 * [facts] is every file of the project, keyed by whatever the caller calls a file: the union of
 * their declarations is what a read is judged against, because the page that declares a signal is
 * rarely the file that reads it.
 *
 * A `@Serializable` class is only looked at when some file reads signals into it. A project's
 * other serializable classes are its own business, and a request body that happens to be one is
 * not a signal.
 */
public fun signalDrift(facts: Map<String, SignalFacts>): SignalDriftReport {
    val declared = facts.values.flatMapTo(LinkedHashSet()) { it.declared }
    val signalTypes = facts.values.flatMapTo(LinkedHashSet()) { it.signalTypes }
    val read = LinkedHashSet<String>()

    val out = LinkedHashMap<String, List<Issue>>()
    for ((file, one) in facts) {
        val issues = ArrayList<Issue>()

        for ((type, properties) in one.classes) {
            if (type !in signalTypes) continue
            for (property in properties) {
                read += property.name
                if (property.name in declared) continue
                issues +=
                    Issue(
                        start = property.start,
                        end = property.end,
                        message =
                            "No markup declares the signal '${property.name}', so $type.${property.name} will " +
                                "always be its default. Check the name against the page, or drop the property.",
                        severity = Severity.WARNING,
                        code = "signal-never-declared",
                        link = Docs.SIGNALS,
                    )
            }
        }

        for (lookup in one.lookups) {
            read += lookup.name
            if (lookup.name in declared) continue
            issues +=
                Issue(
                    start = lookup.start,
                    end = lookup.end,
                    message =
                        "No markup declares the signal '${lookup.name}', so this read will always be null. " +
                            "Check the name against the page.",
                    severity = Severity.WARNING,
                    code = "signal-never-declared",
                    link = Docs.SIGNALS,
                )
        }

        if (issues.isNotEmpty()) out[file] = issues
    }
    return SignalDriftReport(out, declared, read)
}
