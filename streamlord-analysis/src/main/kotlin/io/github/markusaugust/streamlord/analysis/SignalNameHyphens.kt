package io.github.markusaugust.streamlord.analysis

/*
 * A hyphen in a signal name written in a DSL call. The client reads one as a capital in an
 * attribute key and as a hyphen in an expression or a patch, so the SDK refuses such a name at
 * render or send time (`SignalNames.hyphenProblem` in streamlord-core); this says so in the editor,
 * with the name to write instead. `signalNameHyphens.ts` is the same check for VS Code.
 */

/** The helpers whose first argument is the name of a signal the page declares. */
internal val NAMING_HELPERS: Set<String> = setOf("dataSignals", "dataComputed", "dataBind", "dataRef", "dataIndicator", "dataMatchMedia")

/** The helpers that take an object form as well as a name: one string argument alone is the object form. */
private val KEYED_OR_OBJECT = setOf("dataSignals", "dataComputed")

/** The expression helpers, whose name is JavaScript: a hyphen inside brackets is a key. */
internal val EXPRESSION_HELPERS: Set<String> = setOf("signal", "set", "setExpr", "increment", "decrement", "toggle", "not")

/**
 * The expression helpers whose names other APIs share in the same scope: `set` on a map, or on
 * Ktor's headers inside `headers { }`. One counts only inside the arguments of a DSL call.
 */
internal val SHARED_NAME_HELPERS: Set<String> = setOf("set", "setExpr", "toggle")

/** The calls whose pairs name signals: on the page, `dataSignals("count" to 0)`, and from the server. */
private val PAIR_NAMING = setOf("dataSignals", "patchSignals")

/** Every call this check reads. */
internal val SIGNAL_NAMING_CALLS: Set<String> = NAMING_HELPERS + EXPRESSION_HELPERS + PAIR_NAMING + "removeSignals"

private val PAIR_KEY = Regex("""^[\s(]*"""")
private val OBJECT_START = Regex("""^\s*[{\[]""")

/** [name] with each hyphen dropped and the character after it raised: `first-name` is `firstName`. */
internal fun withoutHyphens(name: String): String = Regex("-+.?").replace(name) { it.value.trimStart('-').uppercase() }

/** [withoutHyphens] outside `[...]`, where a quoted key such as `['sub-total']` is text. */
internal fun withoutHyphensOutsideBrackets(name: String): String {
    val out = StringBuilder()
    var depth = 0
    var raise = false
    for (c in name) {
        when {
            c == '[' -> depth++
            c == ']' -> depth = maxOf(0, depth - 1)
        }
        if (depth == 0 && c == '-') {
            raise = true
            continue
        }
        out.append(if (raise) c.toString().uppercase() else c.toString())
        raise = false
    }
    return out.toString()
}

/** The first hyphen outside `[...]`, or -1. */
private fun hyphenOutsideBrackets(name: String): Int {
    var depth = 0
    for ((i, c) in name.withIndex()) {
        when (c) {
            '[' -> depth++
            ']' -> depth = maxOf(0, depth - 1)
            '-' -> if (depth == 0) return i
        }
    }
    return -1
}

/**
 * [name] with its one hyphenated part in brackets, `form.first-name` as `form['first-name']`, or
 * null when that is no simple rewrite: a name with brackets already, or more than one such part.
 */
internal fun bracketed(name: String): String? {
    if ('[' in name || ']' in name) return null
    val parts = name.split('.')
    val i = parts.indexOfFirst { '-' in it }
    if (i < 0 || parts.count { '-' in it } > 1) return null
    return parts.take(i).joinToString(".") + "['" + parts[i] + "']" + parts.drop(i + 1).joinToString("") { ".$it" }
}

/** The issues of [site], a call in [SIGNAL_NAMING_CALLS], for each signal name in it that holds a hyphen. */
internal fun signalNameHyphenIssues(
    site: CallSite,
    src: String,
    lex: KotlinLex,
    helpers: Set<String>,
    insideDslCall: (Int) -> Boolean,
): List<Issue> {
    val out = ArrayList<Issue>()
    val positional = site.positional
    val first = positional.firstOrNull()

    if (site.name in EXPRESSION_HELPERS) {
        // cache.set("user-id", x), Hamcrest's not("x") and a header's set("datastar-request", ...) are somebody else's.
        if (site.name !in helpers || src.lastNonSpaceBefore(site.nameStart) == '.') return out
        if (site.name in SHARED_NAME_HELPERS && !insideDslCall(site.nameStart)) return out
        val s = (first ?: site.named("name"))?.string ?: return out
        if (s.interpolations.isEmpty() &&
            hyphenOutsideBrackets(s.text) >= 0
        ) {
            out += hyphenIssue(s, src, withoutHyphensOutsideBrackets(s.text), bracketed(s.text))
        }
        return out
    }

    if (site.name in NAMING_HELPERS) {
        // dataSignals("{...}") and dataSignals("{...}", flag) are the object form: a second argument
        // that is a string makes the first a name. One held in a variable may be either, and is left alone.
        val keyed =
            first?.string?.takeIf {
                site.name !in KEYED_OR_OBJECT ||
                    (!OBJECT_START.containsMatchIn(it.text) && (site.named("expression") != null || secondIsExpression(positional)))
            }
        val s = (site.named("name") ?: site.named("signal"))?.string ?: keyed
        if (s != null && s.interpolations.isEmpty() && '-' in s.text) out += hyphenIssue(s, src, withoutHyphens(s.text), null)
    }

    if (site.name == "removeSignals") {
        for (a in positional) {
            val s = a.string ?: continue
            if (s.interpolations.isEmpty() && '-' in s.text) out += hyphenIssue(s, src, withoutHyphens(s.text), null)
        }
    }

    if (site.name in PAIR_NAMING) {
        for (a in positional) {
            if (a.string != null) continue
            val quote = PAIR_KEY.find(a.text)?.range?.last ?: continue
            val s = lex.stringAt(a.start + quote + 1)?.takeIf { it.start == a.start + quote } ?: continue
            if (!PAIR_AFTER.matchesAt(src, s.end)) continue
            if (s.interpolations.isEmpty() && '-' in s.text) out += hyphenIssue(s, src, withoutHyphens(s.text), null)
        }
    }
    return out
}

private val PAIR_AFTER = Regex("""\s+to\b""")

private fun secondIsExpression(positional: List<Arg>): Boolean =
    positional.getOrNull(1)?.let { it.string != null || '"' in it.text } == true

private fun hyphenIssue(
    s: KotlinString,
    src: String,
    fixed: String,
    bracketed: String?,
): Issue {
    val name = s.text
    // A name with escapes in its source is reported and left for the author to rewrite. One with a
    // template in it is not read at all: what the client gets depends on the value.
    val plain = src.substring(s.contentStart, s.contentEnd) == name
    return Issue(
        start = s.start,
        end = s.end,
        message =
            "A signal name holds no hyphen: Datastar's client reads one as a capital in an attribute key and as a hyphen " +
                "in an expression or a patch, so Streamlord refuses \"$name\" at runtime. Write $fixed" +
                (bracketed?.let { ", or $it for a key with a hyphen in it" } ?: "") + ".",
        severity = Severity.ERROR,
        code = "signal-name-hyphen",
        link = Docs.CASING,
        fixes = if (plain) listOf(Fix("Change to $fixed", s.contentStart, s.contentEnd, fixed)) else emptyList(),
    )
}
