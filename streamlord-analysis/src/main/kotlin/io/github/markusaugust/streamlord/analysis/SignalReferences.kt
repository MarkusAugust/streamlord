package io.github.markusaugust.streamlord.analysis

import io.github.markusaugust.streamlord.core.client.decodeEntities
import io.github.markusaugust.streamlord.core.client.regex
import kotlin.concurrent.Volatile

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
            val written = templatePlaceholders(value, engine)
            val chars = value.toCharArray()
            for (range in written) {
                for (k in range) chars[k] = ' '
            }
            val decoded = decodeEntities(chars.concatToString())
            for (ref in expressionSignalReferences(decoded.text)) {
                val start = decoded.toSource(ref.start)
                val end = decoded.toSource(ref.end)
                if (finished(value, written, start, end, engine)) continue
                out += ref.copy(start = attr.valueStart + start, end = attr.valueStart + end)
            }
        }
    }
    return out
}

/** A character that carries a name on, read after it: `$a` and then `b`, `1`, `.b`, `[0]` or `-b`. */
private fun carriesOn(c: Char?) = c != null && (c.isLetterOrDigit() || c == '_' || c == '.' || c == '[' || c == '-')

/** A character that a name carries on from, read before it: `x$a` is one name in JavaScript. */
private fun carriesInto(c: Char?) = c != null && (c.isLetterOrDigit() || c == '_' || c == '$')

/**
 * Whether what [written], the constructs of [value], write beside the read from [start] to [end]
 * finishes it: text written right before the dollar or right after the name, as in `{{ p }}$a`,
 * `$a{{ i }}` and `$a.{{ k }}`, or text that stands beyond a run of constructs that write nothing,
 * as in `x{% if c %}$a`. A run that leads to another branch, `{% else %}`, ends what stands beside it.
 */
private fun finished(
    value: String,
    written: List<IntRange>,
    start: Int,
    end: Int,
    engine: TemplateEngine?,
): Boolean {
    var before = start
    var branch = false
    while (true) {
        val r = written.lastOrNull { it.last + 1 == before } ?: break
        if (!writesNothing(value, r, engine)) return true
        branch = branch || opensBranch(value, r)
        before = r.first
    }
    if (before < start && !branch && carriesInto(value.getOrNull(before - 1))) return true
    val gap = value.getOrNull(end)
    if ((gap == '.' || gap == '[' || gap == '-') && written.any { it.first == end + 1 }) return true
    var after = end
    branch = false
    while (true) {
        val r = written.firstOrNull { it.first == after } ?: break
        if (!writesNothing(value, r, engine)) return true
        branch = branch || opensBranch(value, r)
        after = r.last + 1
    }
    return after > end && !branch && carriesOn(value.getOrNull(after))
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
    if (anyKnown(defined)) return true
    return isKnownPath(path, defined)
}

/** Whether [defined] may hold any signal: a spread of a variable, or a page declared from a class the analysis cannot read. */
private fun anyKnown(defined: Set<String>): Boolean =
    ANY_SIGNAL in defined || defined.any { it.startsWith(ENCODED_CLASS) && READABLE_CLASS + it.removePrefix(ENCODED_CLASS) !in defined }

/** [isKnownSignal] for a path, where [defined] is known not to hold any signal. */
private fun isKnownPath(
    path: String,
    defined: Set<String>,
): Boolean {
    fun has(p: String) = p in defined || REFUSED + p in defined
    if (has(path)) return true
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
    // What may hold any signal holds every read, asked once rather than for each.
    if (refs.isEmpty() || anyKnown(known)) return emptyList()
    return refs.mapNotNull { unknownSignalIssue(it, known) }
}

/**
 * [defined] with the properties of each Jackson class a page or a patch sends, as the names they
 * are, and the class marked as one the analysis reads. Of the classes a name may mean, an import
 * decides, and otherwise the file's own package before one star import alone; an import of a
 * nested class, `model.Outer.Form`, means the `Form` of `model`. A `@Serializable` class of the
 * name anywhere is read as before. A class the analysis cannot read, or any Jackson class under a
 * naming strategy, adds nothing: the check is then as it was before it read Jackson classes.
 */
internal fun withClassProperties(defined: Set<String>): Set<String> {
    lastWithProperties?.let { (given, made) -> if (given === defined) return made }
    val made = classProperties(defined)
    lastWithProperties = defined to made
    return made
}

@Volatile
private var lastWithProperties: Pair<Set<String>, Set<String>>? = null

private fun classProperties(defined: Set<String>): Set<String> {
    val sends = ArrayList<String>()
    val kotlinx = HashSet<String>()
    val kotlinxNames = HashSet<String>()
    val jackson = HashMap<String, MutableList<String>>()
    for (d in defined) {
        when {
            d.startsWith(SENT_CLASS) -> {
                sends += d
            }

            d.startsWith(KOTLINX_CLASS) -> {
                d.removePrefix(KOTLINX_CLASS).let {
                    kotlinx += it
                    kotlinxNames += it.substringAfterLast('.')
                }
            }

            d.startsWith(JACKSON_CLASS) -> {
                jackson.getOrPut(d.removePrefix(JACKSON_CLASS)) { ArrayList() }
            }

            d.startsWith(JACKSON_PROPERTY_OF) -> {
                d.removePrefix(JACKSON_PROPERTY_OF).let {
                    jackson.getOrPut(it.substringBeforeLast(':')) { ArrayList() } +=
                        it.substringAfterLast(':')
                }
            }
        }
    }
    if (sends.isEmpty()) return defined
    // A naming strategy renames a Jackson class's signals, so none is read off its properties.
    if (RENAMED_CLASSES in defined) jackson.clear()
    val out = LinkedHashSet(defined)
    for (d in sends) {
        val type = d.removePrefix(SENT_CLASS).substringBefore(':')
        val candidates = d.substringAfter("$SENT_CLASS$type:").split('|')
        val known = { c: String -> c in kotlinx || c in jackson }
        val qualified =
            candidates.firstOrNull(known)?.takeIf { it == candidates.first() || candidates.count(known) == 1 }
                ?: nested(candidates.first(), known)
        when {
            qualified != null && qualified in jackson -> {
                out += READABLE_CLASS + type
                out += jackson.getValue(qualified)
            }

            qualified != null || type in kotlinxNames -> {
                out += READABLE_CLASS + type
            }
        }
    }
    return out
}

/** Whether [written], a class as an import names it, is [declared] nested in a class of the same package: `model.Outer.Form` and `model.Form`. */
internal fun nests(
    written: String,
    declared: String,
): Boolean = written != declared && nested(written) { it == declared } == declared

/** The class an import of a nested class, `model.Outer.Form`, names among those [known] by package and name: `model.Form`. */
private fun nested(
    written: String,
    known: (String) -> Boolean,
): String? {
    val parts = written.split('.')
    if (parts.size < 3) return null
    val name = parts.last()
    // Between the package and the name stand classes, `Outer`, not the packages of `model.sub.Form`.
    var found: String? = null
    for (end in parts.size - 2 downTo 1) {
        if (!parts[end].first().isUpperCase()) break
        val declared = parts.subList(0, end).joinToString(".") + ".$name"
        if (known(declared)) {
            if (found != null) return null
            found = declared
        }
    }
    return found
}

private fun unknownSignalIssue(
    ref: SignalReference,
    defined: Set<String>,
): Issue? {
    if (isKnownPath(ref.name, defined)) return null
    val head = ref.name.substringBefore('.')
    // `$telefon.length`: the misspelling is the signal, not the property read on it.
    val misspelt = if (head != ref.name && !isKnownPath(head, defined)) head else ref.name
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
