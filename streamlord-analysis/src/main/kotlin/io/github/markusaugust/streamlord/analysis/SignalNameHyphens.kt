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

/** The calls whose pairs name signals: on the page, `dataSignals("count" to 0)`, and from the server. */
private val PAIR_NAMING = setOf("dataSignals", "patchSignals", "respondSignals", "datastarSignals")

/** Every call this check reads. */
internal val SIGNAL_NAMING_CALLS: Set<String> = NAMING_HELPERS + EXPRESSION_HELPERS + PAIR_NAMING + "removeSignals"

private val PAIR_KEY = Regex("""^[\s(]*"""")
private val OBJECT_START = Regex("""^\s*[{\[]""")
private val BOOLEAN = Regex("""^\s*(?:true|false)\s*$""")

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
        out.append(if (raise) c.uppercaseChar() else c)
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

/** [name] with the part holding the hyphen at [at] put in brackets: `form.first-name` is `form['first-name']`. */
private fun bracketed(
    name: String,
    at: Int,
): String {
    val from = name.lastIndexOf('.', at) + 1
    val to = name.indexOfAny(charArrayOf('.', '['), at).let { if (it < 0) name.length else it }
    return name.substring(0, maxOf(0, from - 1)) + "['" + name.substring(from, to) + "']" + name.substring(to)
}

/** The issues of [site], a call in [SIGNAL_NAMING_CALLS], for each signal name in it that holds a hyphen. */
internal fun signalNameHyphenIssues(
    site: CallSite,
    src: String,
    lex: KotlinLex,
    helpers: Set<String>,
): List<Issue> {
    val out = ArrayList<Issue>()
    val positional = site.positional
    val first = positional.firstOrNull()

    if (site.name in EXPRESSION_HELPERS) {
        // set("user-id", x) on a cache or Hamcrest's not("x") is somebody else's.
        if (site.name !in helpers || src.lastNonSpaceBefore(site.nameStart) == '.') return out
        val s = (first ?: site.named("name"))?.string ?: return out
        val at = hyphenOutsideBrackets(s.text)
        if (at >= 0) out += hyphenIssue(s, src, withoutHyphensOutsideBrackets(s.text), bracketed(s.text, at))
        return out
    }

    if (site.name in NAMING_HELPERS) {
        // dataSignals("{...}") and dataSignals("{...}", true) are the object form: a second argument
        // that is not ifMissing makes the first a name.
        val keyed =
            first?.string?.takeIf {
                site.name !in KEYED_OR_OBJECT ||
                    (!OBJECT_START.containsMatchIn(it.text) && (site.named("expression") != null || secondIsExpression(positional)))
            }
        val s = (site.named("name") ?: site.named("signal"))?.string ?: keyed
        if (s != null && '-' in s.text) out += hyphenIssue(s, src, withoutHyphens(s.text), null)
    }

    if (site.name == "removeSignals") {
        for (a in positional) {
            val s = a.string ?: continue
            if ('-' in s.text) out += hyphenIssue(s, src, withoutHyphens(s.text), null)
        }
    }

    if (site.name in PAIR_NAMING) {
        for (a in positional) {
            if (a.string != null) continue
            val quote = PAIR_KEY.find(a.text)?.range?.last ?: continue
            val s = lex.stringAt(a.start + quote + 1)?.takeIf { it.start == a.start + quote } ?: continue
            if (!PAIR_AFTER.matchesAt(src, s.end)) continue
            if ('-' in s.text) out += hyphenIssue(s, src, withoutHyphens(s.text), null)
        }
    }
    return out
}

private val PAIR_AFTER = Regex("""\s+to\b""")

private fun secondIsExpression(positional: List<Arg>): Boolean = positional.getOrNull(1)?.let { !BOOLEAN.matches(it.text) } == true

private fun hyphenIssue(
    s: KotlinString,
    src: String,
    fixed: String,
    bracketed: String?,
): Issue {
    val name = s.text
    // A name with escapes in its source is reported and left for the author to rewrite.
    val plain = s.interpolations.isEmpty() && src.substring(s.contentStart, s.contentEnd) == name
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
