package io.github.markusaugust.streamlord.analysis

/*
 * The signals a Datastar expression reads, with their offsets, and the check of each against the
 * signals the project defines. The analysis sees one file; which names the project defines is the
 * host's to say, from its index of every file (see [collectSignalDefinitions]).
 */

/**
 * `$name` or `$name.path` in a Datastar expression; offsets are the source's, and [name] is the
 * whole path without the dollar. [site] is the DSL call the expression or markup is handed to, or
 * null for a free-standing HTML string or a markup file.
 */
public data class SignalReference(
    val start: Int,
    val end: Int,
    val name: String,
    val site: CallSite? = null,
)

/**
 * The signals an expression reads, with offsets relative to [text]: what the client rewrites as a
 * signal where JavaScript runs it (see [clientSignalReads]), shared with `signalReferences.ts`. A
 * `$foo-bar` is left to the `signal-kebab` check. A name opening with a digit, which the client
 * reads and `data-signals:123` declares, is left out too: no check of this module is written
 * for one.
 */
internal fun expressionSignalReferences(text: String): List<SignalReference> =
    clientSignalReads(text)
        .filter { r -> '-' !in r.name && r.name.first().let { it == '_' || it in 'A'..'Z' || it in 'a'..'z' } && PLACEHOLDER !in r.name }
        .map { SignalReference(it.start, it.end, it.name) }

/** The DSL helpers whose first argument names a signal they read: `signal("count")` is `$count`. */
internal val HELPER_READ_CALLS: Set<String> = setOf("signal", "increment", "decrement", "not")

/**
 * A name a fix can write back into a helper: dotted parts, without a hyphen, which the helper
 * refuses (see [signalNameHyphenIssues]), or brackets, whose key a plain name would not replace
 * as written.
 */
private val PLAIN_HELPER_NAME = Regex("""[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)*""")

/**
 * A file that uses the DSL: a helper call elsewhere, `redis.increment("visits")` or Hamcrest's
 * `not("error")`, is somebody else's.
 */
internal val USES_HTML_DSL =
    Regex("""(?m)^\s*(?:import\s+io\.github\.markusaugust\.streamlord\.html\.|package\s+io\.github\.markusaugust\.streamlord\.html\b)""")

/** An import of a name from outside the DSL: `import org.hamcrest.Matchers.not`, or `.*` of Hamcrest's matchers. */
private val FOREIGN_IMPORT = Regex("""(?m)^\s*import\s+(?!io\.github\.markusaugust\.streamlord\.html\.)([A-Za-z_][\w.]*)\.(\w+|\*)""")

/** The helpers of [names] that [src] reads as the DSL's: those no import from elsewhere puts in their place. */
internal fun dslHelpers(
    src: String,
    names: Set<String> = HELPER_READ_CALLS,
): Set<String> {
    if (!USES_HTML_DSL.containsMatchIn(src)) return emptySet()
    val out = names.toMutableSet()
    for (m in FOREIGN_IMPORT.findAll(src)) {
        val (from, name) = m.destructured
        if (name == "*") {
            if (from.startsWith("org.hamcrest")) out -= "not"
        } else if (from != "kotlin.collections") {
            // IntelliJ imports kotlin.collections.set for `map[k] = v`: an extension that needs a receiver.
            out -= name
        }
    }
    return out
}

/** The signal the literal first argument of a helper in [HELPER_READ_CALLS] reads, with offsets relative to its text. */
internal fun helperSignalReference(text: String): SignalReference? =
    if (PLAIN_HELPER_NAME.matches(text) && PLACEHOLDER !in text) SignalReference(0, text.length, text) else null

/** The signals the expressions of an HTML fragment or document read, with offsets relative to [html]. */
internal fun markupSignalReferences(
    html: String,
    catalog: Catalog,
    prefix: String,
): List<SignalReference> {
    val out = ArrayList<SignalReference>()
    for (tag in tokenize(html).tags) {
        if (tag.closing) continue
        for (attr in tag.attributes) {
            val spec = catalog.parseAttributeName(attr.name.lowercase(), prefix)?.spec ?: continue
            val value = attr.value ?: continue
            if (spec.valueKind != ValueKind.EXPRESSION || value.isBlank() || hasTemplateSyntax(value)) continue
            val decoded = decodeEntities(value)
            for (ref in expressionSignalReferences(decoded.text)) {
                out += ref.copy(start = attr.valueStart + decoded.toSource(ref.start), end = attr.valueStart + decoded.toSource(ref.end))
            }
        }
    }
    return out
}

/**
 * Does the project define the signal [path] reads? It does when it defines the path itself, a
 * signal the path reaches into (`$user.name.length` with `user` defined), or a signal inside the
 * path (`$form` with `form.email` defined). A spread of a variable into the object form,
 * `{...init}`, may declare any signal, written `*`, and so may a page declared from a class only
 * its codec can name.
 */
public fun isKnownSignal(
    path: String,
    defined: Set<String>,
): Boolean {
    if (path in defined || ANY_SIGNAL in defined) return true
    if (defined.any { it.startsWith(ENCODED_CLASS) && READABLE_CLASS + it.removePrefix(ENCODED_CLASS) !in defined }) return true
    var p = path
    while ('.' in p) {
        p = p.substringBeforeLast('.')
        if (p in defined) return true
    }
    val inside = "$path."
    return defined.any { it.startsWith(inside) }
}

/** The defined signal [name] most likely misspells, or null when none is close. */
public fun nearestSignal(
    name: String,
    defined: Set<String>,
): String? {
    val limit = if (name.length <= 4) 1 else 2
    return defined
        .asSequence()
        .filter { it != name && it != ANY_SIGNAL && ':' !in it }
        .map { it to distance(name.lowercase(), it.lowercase()) }
        .filter { it.second <= limit }
        .minByOrNull { it.second }
        ?.first
}

/** The warning for a signal the project never defines, or null when it does. */
public fun unknownSignalIssue(
    ref: SignalReference,
    defined: Set<String>,
): Issue? {
    if (isKnownSignal(ref.name, defined)) return null
    val head = ref.name.substringBefore('.')
    // `$telefon.length`: the misspelling is the signal, not the property read on it.
    val misspelt = if (head != ref.name && !isKnownSignal(head, defined)) head else ref.name
    val nearest = nearestSignal(misspelt, defined)
    // The fix rewrites the name and leaves the dollar as written, which in Kotlin may be `${'$'}`.
    // A helper writes its name as it stands, and only a plain name goes back into one.
    val near = nearest?.takeIf { ref.site?.name !in HELPER_READ_CALLS || PLAIN_HELPER_NAME.matches(it) }
    val nameStart = ref.end - ref.name.length
    return Issue(
        start = ref.start,
        end = ref.end,
        message =
            "No markup or code in the project defines the signal '${ref.name}'." +
                (near?.let { " Did you mean \$$it?" } ?: ""),
        severity = Severity.WARNING,
        code = "unknown-signal",
        link = Docs.SIGNALS,
        fixes = near?.let { listOf(Fix("Change to \$$it", nameStart, nameStart + misspelt.length, it)) } ?: emptyList(),
    )
}
