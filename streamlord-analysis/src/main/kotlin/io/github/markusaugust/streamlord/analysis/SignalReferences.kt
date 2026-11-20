package io.github.markusaugust.streamlord.analysis

import io.github.markusaugust.streamlord.core.client.decodeEntities
import io.github.markusaugust.streamlord.core.client.regex

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
 * signal where JavaScript runs it (see [clientSignalReads]). A
 * `$foo-bar` is left to the `signal-kebab` check. A name opening with a digit, which the client
 * reads and `data-signals:123` declares, is left out too: no check of this module is written
 * for one.
 */
internal fun expressionSignalReferences(text: String): List<SignalReference> =
    clientSignalReads(text)
        .filter { r -> '-' !in r.name && r.name.first().let { it == '_' || it in 'A'..'Z' || it in 'a'..'z' } && PLACEHOLDER !in r.name }
        .map { SignalReference(it.start, it.end, it.name) }

/** The DSL helpers whose first argument names a signal they read: `signal("count")` is `$count`. */
internal val HELPER_READ_CALLS: Set<String> =
    Catalog.default.signalNameHelpers
        .filterValues { "reads" in it }
        .keys

/**
 * A name a fix can write back into a helper: dotted parts, without a hyphen, which the helper
 * refuses (see [signalNameHyphenIssues]), or brackets, whose key a plain name would not replace
 * as written.
 */
private val PLAIN_HELPER_NAME = regex("""[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)*""")

/**
 * A file that uses the DSL: a helper call elsewhere, `redis.increment("visits")` or Hamcrest's
 * `not("error")`, is somebody else's. A line starts after `\n` alone and is indented with spaces
 * and tabs.
 */
internal val USES_HTML_DSL =
    regex(
        """(?:^|(?<=\n))[ \t]*(?:import[ \t]+io\.github\.markusaugust\.streamlord\.html\.|""" +
            """package[ \t]+io\.github\.markusaugust\.streamlord\.html(?![\p{L}\p{Nd}_]))""",
    )

/** An import of a name from outside the DSL: `import org.hamcrest.Matchers.not`, or `.*` of Hamcrest's matchers. Lines as in [USES_HTML_DSL]. */
private val FOREIGN_IMPORT =
    regex("""(?:^|(?<=\n))[ \t]*import[ \t]+(?!io\.github\.markusaugust\.streamlord\.html\.)([A-Za-z_][\w.]*)\.(\w+|\*)""")

/**
 * The helpers of [names] that [src] reads as the DSL's: those no import from elsewhere puts in their
 * place. [htmlDsl] is whether streamlord-html is on the classpath, as [Source.htmlDsl]
 * says; when that is not known, a file that imports nothing of the DSL has none.
 */
internal fun dslHelpers(
    src: String,
    names: Set<String> = HELPER_READ_CALLS,
    htmlDsl: Boolean? = null,
): Set<String> {
    if (!(htmlDsl ?: USES_HTML_DSL.containsMatchIn(src))) return emptySet()
    return unshadowed(src, names)
}

/** The names of [names] that no import from outside the DSL in [src] puts in their place. */
internal fun unshadowed(
    src: String,
    names: Set<String>,
): Set<String> {
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
    if (PLAIN_HELPER_NAME.matchEntire(text) != null && PLACEHOLDER !in text) SignalReference(0, text.length, text) else null

/**
 * The signals the expressions of an HTML fragment or document read, with offsets relative to [html].
 * Where an engine of [engine]'s kind writes into a value, the reads are those around what it
 * writes; a read it adjoins, `{{ p }}$a`, `$a{{ i }}` or `$a.{{ k }}`, is the engine's to finish.
 */
internal fun markupSignalReferences(
    html: String,
    catalog: Catalog,
    prefix: String,
    templates: Boolean = true,
    engine: TemplateEngine? = null,
): List<SignalReference> {
    val out = ArrayList<SignalReference>()
    for (tag in appliedTags(tokenize(html, templates).tags, prefix)) {
        for (attr in tag.attributes) {
            val spec = catalog.parseAttributeName(attr.name.lowercase(), prefix)?.spec ?: continue
            val value = attr.value ?: continue
            if (spec.valueKind != ValueKind.EXPRESSION || value.isBlank()) continue
            val written = if (hasTemplateSyntax(value)) templatePlaceholders(value, engine) else emptyList()
            val chars = value.toCharArray()
            for (range in written) for (k in range) chars[k] = ' '
            val decoded = decodeEntities(chars.concatToString())
            for (ref in expressionSignalReferences(decoded.text)) {
                val start = decoded.toSource(ref.start)
                val end = decoded.toSource(ref.end)
                if (written.any { finishes(value, it, start, end) }) continue
                out += ref.copy(start = attr.valueStart + start, end = attr.valueStart + end)
            }
        }
    }
    return out
}

/** Template constructs that decide what is written and write nothing themselves: `{% if %}`, `<#if>`, `[#if]`, `@if`, `#if`. */
private val CONTROL = regex("""\{%|</?#|\[#|[@#][a-z]""")

/** A character that would carry a name on: `$a` and then `b`, `1`, `.b` or `[0]` read as one path. */
private fun carriesOn(c: Char?) = c != null && (c.isLetterOrDigit() || c == '_' || c == '.' || c == '[')

/**
 * Whether the construct at [range] of [value] finishes the read from [start] to [end]: text it
 * writes right before the dollar or right after the name, as in `{{ p }}$a`, `$a{{ i }}` and
 * `$a.{{ k }}`, or a construct that writes nothing with a name carried on after it.
 */
private fun finishes(
    value: String,
    range: IntRange,
    start: Int,
    end: Int,
): Boolean {
    val control = CONTROL.matchesAt(value, range.first)
    if (range.last + 1 == start) return !control
    if (range.first < end) return false
    val gap = value.substring(end, range.first)
    if (gap.isNotEmpty() && gap != "." && gap != "[") return false
    return !control || gap.isNotEmpty() || carriesOn(value.getOrNull(range.last + 1))
}

/**
 * Does the project define the signal [path] reads? It does when it defines the path itself, a
 * signal the path reaches into (`$user.name.length` with `user` defined), or a signal inside the
 * path (`$form` with `form.email` defined). A spread of a variable into the object form,
 * `{...init}`, may declare any signal, written `*`, and so may a page declared from a class only
 * its codec can name.
 */
internal fun isKnownSignal(
    path: String,
    defined: Set<String>,
): Boolean {
    fun has(p: String) = p in defined || REFUSED + p in defined
    if (has(path) || ANY_SIGNAL in defined) return true
    if (defined.any { it.startsWith(ENCODED_CLASS) && READABLE_CLASS + it.removePrefix(ENCODED_CLASS) !in defined }) return true
    var p = path
    while ('.' in p) {
        p = p.substringBeforeLast('.')
        if (has(p)) return true
    }
    val inside = "$path."
    return defined.any { it.startsWith(inside) || it.startsWith(REFUSED + inside) }
}

/** The defined signal [name] most likely misspells, or null when none is close. */
internal fun nearestSignal(
    name: String,
    defined: Set<String>,
): String? {
    val limit = if (name.length <= 4) 1 else 2
    return defined
        .asSequence()
        .filter { it != name && it != ANY_SIGNAL && ':' !in it }
        .map { it to distance(name.lowercase(), it.lowercase()) }
        .filter { it.second <= limit }
        // By name where two are as near: the set comes in the order the project happened to be read in.
        .minWithOrNull(compareBy({ it.second }, { it.first }))
        ?.first
}

/** A warning for each of [refs] that reads a signal the project never defines, [defined] being those it does. */
internal fun unknownSignalIssues(
    refs: List<SignalReference>,
    defined: Set<String>,
): List<Issue> {
    val known = withClassProperties(defined)
    return refs.mapNotNull { unknownSignalIssue(it, known) }
}

/** [defined] with the properties of each Jackson class a page is declared from or a patch sends, as the names they are. */
internal fun withClassProperties(defined: Set<String>): Set<String> {
    val sent = HashSet<String>()
    for (d in defined) for (marker in listOf(ENCODED_CLASS, PATCHED_CLASS)) if (d.startsWith(marker)) sent += d.removePrefix(marker)
    if (sent.isEmpty()) return defined
    val out = LinkedHashSet(defined)
    for (d in defined) {
        if (!d.startsWith(JACKSON_PROPERTY_OF)) continue
        val rest = d.removePrefix(JACKSON_PROPERTY_OF)
        if (rest.substringBefore(':') in sent) out += rest.substringAfter(':')
    }
    return out
}

private fun unknownSignalIssue(
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
    val near = nearest?.takeIf { ref.site?.name !in HELPER_READ_CALLS || PLAIN_HELPER_NAME.matchEntire(it) != null }
    val nameStart = ref.end - ref.name.length
    return Issue(
        start = ref.start,
        end = ref.end,
        message =
            "No markup or code in the project defines the signal '${ref.name}'." +
                (near?.let { " Did you mean \$$it?" } ?: ""),
        severity = Severity.WARNING,
        code = IssueCode.UNKNOWN_SIGNAL,
        link = Docs.SIGNALS,
        fixes = near?.let { listOf(Fix("Change to \$$it", nameStart, nameStart + misspelt.length, it)) } ?: emptyList(),
    )
}
