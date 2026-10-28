package io.github.markusaugust.streamlord.analysis

import io.github.markusaugust.streamlord.core.domain.SignalNames

/*
 * A hyphen in a signal name written in a DSL call. The client reads one as a capital in an
 * attribute key and as a hyphen in an expression or a patch, so the SDK refuses such a name at
 * render or send time (`SignalNames.hyphenProblem` in streamlord-core); this says so in the editor,
 * with the name to write instead. `signalNameHyphens.ts` is the same check for VS Code.
 */

/** The helpers whose first argument is the name of a signal the page declares. */
internal val NAMING_HELPERS: Set<String> = setOf("dataSignals", "dataComputed", "dataBind", "dataRef", "dataIndicator", "dataMatchMedia")

/**
 * The helper whose second argument may be `ifMissing` of the object form, `dataSignals("{...}", true)`,
 * as well as the expression of the keyed form. `dataComputed` takes an expression second or nothing.
 */
private const val KEYED_OR_OBJECT = "dataSignals"

/** The helper with an object form of one argument and a keyed form of two. */
private const val COMPUTED = "dataComputed"

/** The expression helpers, whose name is JavaScript: a quoted key in brackets may hold a hyphen. */
internal val EXPRESSION_HELPERS: Set<String> = setOf("signal", "set", "setExpr", "increment", "decrement", "toggle", "not")

/**
 * The expression helpers whose names other APIs share in the same scope: `set` on a map, or on
 * Ktor's headers inside `headers { }`. One counts only inside the arguments of a DSL call.
 */
internal val SHARED_NAME_HELPERS: Set<String> = setOf("set", "toggle")

/** The calls whose pairs name signals: on the page, `dataSignals("count" to 0)`, and from the server. */
private val PAIR_NAMING = setOf("dataSignals", "patchSignals")

/** Every call this check reads. */
internal val SIGNAL_NAMING_CALLS: Set<String> = NAMING_HELPERS + EXPRESSION_HELPERS + PAIR_NAMING + "removeSignals"

private val PAIR_KEY = Regex("""^[\s(]*"""")
private val OBJECT_START = Regex("""^\s*[{\[]""")

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
        SignalNames.withoutReferenceHyphens(s.text)?.let {
            out += hyphenIssue(s, src, it, bracketed(s.text), SignalNames.referenceAsSubtraction(s.text))
        }
        return out
    }

    if (site.name in NAMING_HELPERS) {
        // dataSignals("{...}") and dataSignals("{...}", flag) are the object form: a second argument
        // that is a String makes the first a name, and one that may be either is left alone.
        val keyed =
            first?.string?.takeIf {
                when (site.name) {
                    KEYED_OR_OBJECT -> {
                        !OBJECT_START.containsMatchIn(it.text) &&
                            (site.named("expression") != null || secondArgument(positional.getOrNull(1), lex) == SecondArgument.EXPRESSION)
                    }

                    COMPUTED -> {
                        site.named("expression") != null || positional.size > 1
                    }

                    else -> {
                        true
                    }
                }
            }
        val s = (site.named("name") ?: site.named("signal"))?.string ?: keyed
        if (s != null && '-' in s.text) out += hyphenIssue(s, src, SignalNames.withoutHyphens(s.text), null)
    }

    if (site.name == "removeSignals") {
        for (a in positional) {
            val s = a.string ?: continue
            if ('-' in s.text) out += hyphenIssue(s, src, SignalNames.withoutHyphens(s.text), null)
        }
    }

    if (site.name in PAIR_NAMING) {
        for (a in positional) {
            if (a.string != null) continue
            val quote = PAIR_KEY.find(a.text)?.range?.last ?: continue
            val s = lex.stringAt(a.start + quote + 1)?.takeIf { it.start == a.start + quote } ?: continue
            if (!PAIR_AFTER.matchesAt(src, s.end)) continue
            if ('-' in s.text) out += hyphenIssue(s, src, SignalNames.withoutHyphens(s.text), null)
        }
    }
    return out
}

private val PAIR_AFTER = Regex("""\s+to\b""")

/** What the second argument of `dataSignals(first, second)` is, as far as its text shows: the overload Kotlin picks. */
internal enum class SecondArgument { EXPRESSION, IF_MISSING, UNKNOWN }

/** An operator whose result is a Boolean, outside strings. ktlint spaces `<` and `>` when they compare, and not in a type argument. */
private val BOOLEAN_OPERATOR = Regex("""==|!=|&&|\|\||\s[<>]=?\s|\s!?(?:in|is)\s""")

/** A call of a helper that writes an expression, which is a String. */
private val EXPRESSION_CALL = Regex("""^(?:signal|not|set|setExpr|increment|decrement|toggle)\s*\(""")

/**
 * Whether [arg], the second argument of `dataSignals`, is the expression of the keyed form, a
 * String, or `ifMissing` of the object form, a Boolean. A string literal, a concatenation that
 * starts with one, and a call of an expression helper are Strings; `true`, `false`, a negation and
 * a comparison or a logical operator are Booleans. Anything else, a variable or a call, may be
 * either, and only the compiler knows.
 */
internal fun secondArgument(
    arg: Arg?,
    lex: KotlinLex,
): SecondArgument {
    if (arg == null) return SecondArgument.UNKNOWN
    if (arg.string != null) return SecondArgument.EXPRESSION
    val strings = lex.strings.filter { it.start >= arg.start && it.end <= arg.end }
    val code =
        arg.text
            .mapIndexed {
                i,
                c,
                ->
                if (strings.any { arg.start + i >= it.start && arg.start + i < it.end }) ' ' else c
            }.joinToString("")
    val text = arg.text.trim()
    if (text == "true" || text == "false" || code.trim().startsWith('!') || BOOLEAN_OPERATOR.containsMatchIn(" $code ")) {
        return SecondArgument.IF_MISSING
    }
    val leading = strings.firstOrNull { it.start == arg.start + arg.text.indexOfFirst { c -> !c.isWhitespace() } }
    if (leading != null &&
        arg.text
            .substring(leading.end - arg.start)
            .trim()
            .let { it.isEmpty() || it.startsWith('+') }
    ) {
        return SecondArgument.EXPRESSION
    }
    // A call of an expression helper, alone or with more joined to it by `+`.
    val call = EXPRESSION_CALL.find(code.trimStart()) ?: return SecondArgument.UNKNOWN
    val open = code.length - code.trimStart().length + call.range.last
    var depth = 0
    for (i in open until code.length) {
        if (code[i] == '(') depth++
        if (code[i] == ')' && --depth == 0) {
            val after = code.substring(i + 1).trim()
            return if (after.isEmpty() || after.startsWith('+')) SecondArgument.EXPRESSION else SecondArgument.UNKNOWN
        }
    }
    return SecondArgument.UNKNOWN
}

private fun hyphenIssue(
    s: KotlinString,
    src: String,
    fixed: String,
    bracketed: String?,
    subtraction: String? = null,
): Issue {
    val written = src.substring(s.contentStart, s.contentEnd)
    // A name with escapes in its source is reported and left for the author to rewrite, and so is
    // one with a template in it, whose hyphen is in the text around the template. An escaped `$`,
    // as in "items[\$i-1]", is the one escape the fix writes back. A subtraction gets no fix, since
    // it belongs in the expression around the call, which the author writes; its `$` is shown escaped,
    // as a Kotlin string needs it.
    val dollars = written != s.text && written == s.text.replace("$", "\\$")
    val plain = subtraction == null && s.interpolations.isEmpty() && (written == s.text || dollars)
    val text = if (dollars) fixed.replace("$", "\\$") else fixed
    val advice =
        if (subtraction != null) {
            "It looks like a subtraction, which belongs in the expression: ${subtraction.replace("$", "\\$")}"
        } else if (plain) {
            "Write $text" + (
                bracketed?.let {
                    ", or $it for a key with a hyphen in it"
                } ?: ""
            )
        } else {
            "Write it in camelCase"
        }
    return Issue(
        start = s.start,
        end = s.end,
        message =
            "A signal name holds no hyphen: Datastar's client reads one as a capital in an attribute key and as a hyphen " +
                "in an expression or a patch, so Streamlord refuses \"$written\" at runtime. $advice.",
        severity = Severity.ERROR,
        code = "signal-name-hyphen",
        link = Docs.CASING,
        fixes = if (plain) listOf(Fix("Change to $text", s.contentStart, s.contentEnd, text)) else emptyList(),
    )
}

/**
 * The calls of [src] whose signal names hold a hyphen, with their issues, including the calls inside
 * a string template, `"${signal("is-running")} ? 'Stop' : 'Go'"`, which the scanner reads as code
 * of their own. [dslCalls] names the calls whose arguments a shared-name helper counts inside.
 */
internal fun signalNameHyphenSites(
    src: String,
    lex: KotlinLex,
    helpers: Set<String>,
    dslCalls: Set<String>,
): List<Pair<CallSite, List<Issue>>> {
    val out = ArrayList<Pair<CallSite, List<Issue>>>()
    collectHyphenSites(src, lex, helpers, dslCalls, enclosed = false, shift = 0, depth = 0, out = out)
    return out.sortedBy { it.first.nameStart }
}

private fun collectHyphenSites(
    text: String,
    lex: KotlinLex,
    helpers: Set<String>,
    dslCalls: Set<String>,
    enclosed: Boolean,
    shift: Int,
    depth: Int,
    out: MutableList<Pair<CallSite, List<Issue>>>,
) {
    val calls = findCallSites(text, dslCalls, lex)
    // Inside the arguments of a DSL call, and not in a lambda there: `buildMap { set("first-name", "") }` is a map's.
    val inside = { at: Int ->
        calls.any { at > it.openParen && at < it.closeParen && !openBraceBetween(text, lex, it.openParen, at) } ||
            (enclosed && !openBraceBetween(text, lex, 0, at))
    }
    for (site in findCallSites(text, SIGNAL_NAMING_CALLS, lex)) {
        val issues = signalNameHyphenIssues(site, text, lex, helpers, inside)
        if (issues.isNotEmpty()) out += site.shifted(shift) to issues.map { it.shifted(shift) }
    }
    if (depth >= 4) return
    for (s in lex.strings) {
        for (t in s.interpolations) {
            if (t.kind != InterpolationKind.BRACED) continue
            val open = text.indexOf('{', t.start)
            if (open < 0 || open + 1 > t.end - 1 || text.substring(open + 1, t.end - 1) != t.text) continue
            collectHyphenSites(t.text, lexKotlin(t.text), helpers, dslCalls, inside(t.start), shift + open + 1, depth + 1, out)
        }
    }
}

/** Whether a `{` of code between [from] and [to] is still open at [to]. */
private fun openBraceBetween(
    text: String,
    lex: KotlinLex,
    from: Int,
    to: Int,
): Boolean {
    var depth = 0
    for (i in from until minOf(to, text.length)) {
        if (!lex.mask[i]) continue
        if (text[i] == '{') {
            depth++
        } else if (text[i] == '}') {
            depth = maxOf(0, depth - 1)
        }
    }
    return depth > 0
}

private fun Issue.shifted(by: Int): Issue =
    if (by == 0) this else copy(start = start + by, end = end + by, fixes = fixes.map { it.copy(start = it.start + by, end = it.end + by) })

/** [this] at its place in the file, for a call read inside a template: its arguments keep their offsets, not their literals. */
private fun CallSite.shifted(by: Int): CallSite =
    if (by == 0) {
        this
    } else {
        copy(
            nameStart = nameStart + by,
            openParen = openParen + by,
            closeParen = closeParen + by,
            args = args.map { it.copy(start = it.start + by, end = it.end + by, string = null) },
        )
    }
