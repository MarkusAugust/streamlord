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

/** The signals an expression reads, with offsets relative to [text]. A `$foo-bar` is left to the `signal-kebab` check. */
internal fun expressionSignalReferences(text: String): List<SignalReference> {
    val out = ArrayList<SignalReference>()
    val tokens = tokenizeExpression(text)
    for ((i, t) in tokens.withIndex()) {
        if (t.kind != TokenKind.SIGNAL) continue
        val head = text.substring(t.start, t.end).removePrefix("$")
        val path = tokens.getOrNull(i + 1)?.takeIf { it.kind == TokenKind.SIGNAL_PATH && it.start == t.end }
        // `${'$'}count` reads as an escape token and then a signal token holding the name alone.
        val start = tokens.getOrNull(i - 1)?.takeIf { it.kind == TokenKind.KOTLIN_DOLLAR_ESCAPE && it.end == t.start }?.start ?: t.start
        val end = path?.end ?: t.end
        if (text.getOrNull(end) == '-' && text.getOrNull(end + 1)?.let { it.isLetterOrDigit() || it == '_' } == true) continue
        val name = head + (path?.let { text.substring(it.start, it.end) } ?: "")
        if (PLACEHOLDER in name) continue
        out += SignalReference(start, end, name)
    }
    return out
}

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
 * path (`$form` with `form.email` defined).
 */
public fun isKnownSignal(
    path: String,
    defined: Set<String>,
): Boolean {
    if (path in defined) return true
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
        .filter { it != name }
        .map { it to editDistance(name.lowercase(), it.lowercase()) }
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
    val near = nearestSignal(misspelt, defined)
    // The fix rewrites the name and leaves the dollar as written, which in Kotlin may be `${'$'}`.
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

private fun editDistance(
    a: String,
    b: String,
): Int {
    var prev = IntArray(b.length + 1) { it }
    for (i in 1..a.length) {
        val cur = IntArray(b.length + 1)
        cur[0] = i
        for (j in 1..b.length) {
            cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
        }
        prev = cur
    }
    return prev[b.length]
}
