package io.github.markusaugust.streamlord.analysis

/*
 * Editor-independent analysis: Kotlin or HTML source in, issues with source offsets out.
 */

public data class AnalyzeOptions(
    val prefix: String = "data-",
    val checkHtmlAttributes: Boolean = true,
)

private val MODE_NAME = Regex("""(?:^|\.)([A-Z]+)\s*$""")
private val CASE_ARG = Regex("""^\s*Case\.[A-Z]+\s*$""")
private val CASE_VALUE = Regex("""Case\.([A-Z]+)""")
private val CASE_IN_LAMBDA = Regex("""\bcase\s*=\s*Case\.([A-Z]+)""")
private val OBJECT_FORM = Regex("""^\s*[{\[]""")
private val HAS_CAPITAL = Regex("""[A-Z]""")
private val DOLLAR_RUNS = Regex("""\$+""")
private val ASSIGN_BEFORE = Regex("""\s*=\s*$""")
private val BRACED_TEMPLATE = Regex("""\$\{""")
private val TRIM_AFTER = Regex("""\s*\.\s*trim(?:Indent|Margin)\s*\(\s*(?:"[^"\\\n]*"\s*)?\)""")

/** The DSL helper that says the same as a whole-string expression, when there is one. */
private val HELPERS: List<Pair<Regex, (String) -> String>> =
    listOf(
        Regex("""^\$([A-Za-z_][A-Za-z0-9_.]*)$""") to { n -> "signal(\"$n\")" },
        Regex("""^\$([A-Za-z_][A-Za-z0-9_.]*)\+\+$""") to { n -> "increment(\"$n\")" },
        Regex("""^\$([A-Za-z_][A-Za-z0-9_.]*)--$""") to { n -> "decrement(\"$n\")" },
        Regex("""^!\$([A-Za-z_][A-Za-z0-9_.]*)$""") to { n -> "not(\"$n\")" },
    )
private val TOGGLE = Regex("""^\$([A-Za-z_][A-Za-z0-9_.]*) = !\$\1$""")

/**
 * The analysis of Kotlin source and HTML documents against one catalog. Stateless apart from a
 * one-entry cache of the last Kotlin file lexed, so that completion or hover right after a
 * diagnostics pass does not lex the file again.
 */
public class Analyzer(
    public val catalog: Catalog = Catalog.default,
) {
    private val expressions = ExpressionValidator(catalog)
    private val markup = MarkupValidator(catalog)

    /** Keyed helpers that take a key but no expression, so the catalog's expression sites do not list them. */
    private val keyOnlyHelpers = setOf("dataMatchMedia", "dataPersist")

    /**
     * Helpers that write the signal name in the value, which keeps its case, unless a `case` is
     * given: Datastar applies `__case` to a key only, so then the name moves into the key.
     */
    private val valueUnlessCasedHelpers = setOf("dataBind", "dataRef", "dataIndicator")

    private val allSiteNames: Set<String> = catalog.allCallSiteNames + keyOnlyHelpers + valueUnlessCasedHelpers
    private val htmlSiteNames: Set<String> = catalog.htmlCallSites.keys

    /** Names of all DSL functions the analysis recognises in Kotlin source. */
    public val callSiteNames: Set<String> get() = allSiteNames

    private val lexCacheCap = 256 * 1024

    @Volatile
    private var lastLex: Pair<String, KotlinLex>? = null

    /** The lexed form of a Kotlin file, cached for the last file seen. */
    public fun lex(src: String): KotlinLex {
        if (src.length > lexCacheCap) return lexKotlin(src)
        val cached = lastLex
        if (cached != null && cached.first == src) return cached.second
        val lex = lexKotlin(src)
        lastLex = src to lex
        return lex
    }

    public fun analyzeKotlin(
        src: String,
        opts: AnalyzeOptions = AnalyzeOptions(),
    ): List<Issue> = analyzeKotlinSites(src, opts).flatMap { it.issues }

    /** The issues of a Kotlin file grouped by the string or call they belong to, so a host can veto a call it knows is not Streamlord's. */
    public fun analyzeKotlinSites(
        src: String,
        opts: AnalyzeOptions = AnalyzeOptions(),
    ): List<SiteIssues> {
        val out = ArrayList<SiteIssues>()
        val claimed = HashSet<Int>()
        val lex = lex(src)
        for (site in findCallSites(src, allSiteNames, lex)) {
            val issues = ArrayList<Issue>()
            catalog.expressionCallSites[site.name]?.let { issues += checkExpressionSite(site, it, src) }
            catalog.htmlCallSites[site.name]?.let { issues += checkHtmlSite(site, it, opts, src) }
            catalog.scriptCallSites[site.name]?.let { issues += checkScriptSite(site, it) }
            catalog.selectorCallSites[site.name]?.let { issues += checkSelectorSite(site, it) }
            issues += checkKeyCaseSite(site, src)
            for (a in site.args) a.string?.let { claimed += it.start }
            if (issues.isNotEmpty()) out += SiteIssues(site, null, issues)
        }
        for (s in lex.strings) {
            if (s.start in claimed || s.unterminated || !isHtmlString(src, s)) continue
            val issues = checkFreeHtmlString(s, opts, src)
            if (issues.isNotEmpty()) out += SiteIssues(null, s, issues)
        }
        return out
    }

    /**
     * The string literal at an offset when it holds HTML: the argument of an HTML call site, or a
     * free-standing literal that looks like HTML (see [isHtmlString]). Completion and hover use
     * it to give Kotlin the HTML side.
     */
    public fun htmlStringAt(
        src: String,
        offset: Int,
    ): KotlinString? {
        val lex = lex(src)
        val s = lex.stringAt(offset) ?: return null
        if (isHtmlString(src, s)) return s
        val site =
            findCallSites(src, htmlSiteNames, lex).firstOrNull { it.openParen < s.start && s.end <= it.closeParen + 1 } ?: return null
        val spec = catalog.htmlCallSites[site.name] ?: return null
        val arg = markupString(site, spec)
        return if (arg != null && arg.start == s.start) s else null
    }

    /**
     * The markup handed to an HTML call site, or null when the markup is a trailing lambda: in the
     * kotlinx.html form, `patchElements(selector, mode, ...) { li { } }`, the first string is the selector.
     */
    private fun markupString(
        site: CallSite,
        spec: CallSiteSpec,
    ): KotlinString? = if (site.trailingLambda) null else site.selectString(spec)

    /** Analyse an HTML document (a template): attributes and expressions only, no id or completeness rules. */
    public fun analyzeHtml(
        src: String,
        opts: AnalyzeOptions = AnalyzeOptions(),
    ): List<Issue> {
        val issues = ArrayList<Issue>()
        for (tag in tokenize(src).tags) {
            if (tag.closing) continue
            issues += markup.validateAttributes(tag, opts.prefix)
        }
        return issues
    }

    /** The markup rules of one HTML string handed to a patch, with the id rule on or off. */
    public fun validateMarkup(
        html: String,
        opts: MarkupOptions,
    ): List<Issue> = markup.validateMarkup(html, opts)

    public fun validateExpression(text: String): List<Issue> = expressions.validate(text)

    /**
     * A string that holds HTML but is not handed straight to a Streamlord call: a function that
     * returns markup, a `val` with a fragment. Nothing is known about how it will be patched, so
     * only the attributes are checked, as in a template file; ids and completeness are not.
     */
    private fun checkFreeHtmlString(
        s: KotlinString,
        opts: AnalyzeOptions,
        src: String,
    ): List<Issue> {
        val issues = ArrayList(interpolationHints(s, src, opts.prefix))
        if (opts.checkHtmlAttributes) issues += mapIssues(s, src, analyzeHtml(s.text, opts))
        return issues
    }

    /**
     * The whole literal rewritten as a `$$` literal (Kotlin 2.2+): the flagged template becomes a
     * signal by staying as it is, every other template gains a dollar so it stays Kotlin, and the
     * `${'$'}` idiom becomes the plain dollar it always meant.
     */
    private fun multiDollarFix(
        s: KotlinString,
        src: String,
        signal: Interpolation,
    ): Fix {
        val content = src.substring(s.contentStart, s.contentEnd)
        // Enough dollars that no run already in the text opens a template: one more than the longest run.
        val longestRun = DOLLAR_RUNS.findAll(content).maxOfOrNull { it.value.length } ?: 0
        val dollars = maxOf(2, longestRun + 1)
        val prefix = "$".repeat(dollars)

        // A kept template gains the dollars it now needs; the dollars before it stay text, as they were.
        // The flagged one keeps its run, which is now text.
        val keep = s.interpolations.filter { it !== signal }.associate { it.start to dollars - 1 }
        val out = StringBuilder(prefix).append(src.substring(s.start, s.contentStart).trimStart('$'))
        var i = s.contentStart
        while (i < s.contentEnd) {
            val pad = keep[i]
            if (pad != null && pad > 0) out.append("$".repeat(pad))
            if (src.startsWith("\${'$'}", i)) {
                out.append('$')
                i += 6
                continue
            }
            out.append(src[i])
            i++
        }
        out.append(src.substring(s.contentEnd, s.end))
        return Fix("Make it a $prefix literal, where \$${signal.text} is a signal", s.start, s.end, out.toString())
    }

    private fun interpolationIssues(
        s: KotlinString,
        src: String,
    ): List<Issue> {
        // In a multi-dollar literal a single $ is text, so there is no trap: every template there is deliberate.
        if (s.dollars > 1) return emptyList()
        val issues = ArrayList<Issue>()
        for (ip in s.interpolations) {
            if (ip.kind != InterpolationKind.SIMPLE) continue
            val fixes = ArrayList<Fix>()
            fixes += multiDollarFix(s, src, ip)
            fixes += Fix("Escape as \${'$'}${ip.text}", ip.start, ip.start + 1, "\${'$'}")
            if (!s.raw) fixes += Fix("Escape as \\$${ip.text}", ip.start, ip.start, "\\")
            // The literal as the author meant it, with every template read as a signal.
            val meant = src.substring(s.contentStart, s.contentEnd)
            if (!BRACED_TEMPLATE.containsMatchIn(meant)) {
                // A helper is not a string: it replaces the literal together with the trim call after it.
                val end = s.end + (TRIM_AFTER.matchAt(src, s.end)?.value?.length ?: 0)
                for ((re, helper) in HELPERS) {
                    val m = re.find(meant)
                    if (m != null) fixes.add(0, Fix("Use ${helper(m.groupValues[1])}", s.start, end, helper(m.groupValues[1])))
                }
                val toggle = TOGGLE.find(meant)
                if (toggle != null) {
                    val n = toggle.groupValues[1]
                    fixes.add(0, Fix("Use toggle(\"$n\")", s.start, end, "toggle(\"$n\")"))
                }
            }
            issues +=
                Issue(
                    start = ip.start,
                    end = ip.end,
                    message =
                        "Kotlin interpolates \$${ip.text} here; the browser will never see a signal. " +
                            "Write signal(\"${ip.text}\"), make the literal \$\$\"...\" (Kotlin 2.2+), or escape as \${'$'}${ip.text}.",
                    severity = Severity.ERROR,
                    code = "kotlin-interpolation",
                    link = Docs.EXPRESSIONS,
                    fixes = fixes,
                )
        }
        return issues
    }

    private enum class Where { WHOLE, PART }

    /**
     * In HTML a Kotlin template is usually meant (`<li>$name</li>`), so interpolation is a hint,
     * not an error, unless it sits in a `data-*` attribute that takes an expression: there the
     * browser expects a signal and gets whatever Kotlin evaluated.
     */
    private fun interpolationHints(
        s: KotlinString,
        src: String,
        prefix: String,
    ): List<Issue> {
        val tags = tokenize(s.text).tags

        // WHOLE when the template is the entire attribute value, PART when it sits inside a larger expression.
        fun inExpression(decoded: Int): Where? {
            for (t in tags) {
                for (a in t.attributes) {
                    val v = a.value ?: continue
                    if (decoded < a.valueStart || decoded >= a.valueStart + v.length) continue
                    val parsed = catalog.parseAttributeName(a.name.asciiLowercase(), prefix)
                    if (parsed?.spec?.valueKind != ValueKind.EXPRESSION) return null
                    // data-signals:count="$initial" seeds a signal from the server, like the object form does.
                    if (parsed.spec.name == "signals") return Where.PART
                    return if (v.trim() == PLACEHOLDER) Where.WHOLE else Where.PART
                }
            }
            return null
        }
        return interpolationIssues(s, src).map { i ->
            val ip = s.interpolations.firstOrNull { it.start == i.start }
            val name = ip?.text ?: ""
            val where = ip?.let { inExpression(it.decodedStart) }
            val fixes = i.fixes.filter { it.title.startsWith("Make it") || it.title.startsWith("Escape") }
            when (where) {
                // data-show="$isAdmin" renders as data-show="true", which works; meant as the signal $isAdmin, it is the trap.
                Where.WHOLE -> {
                    i.copy(
                        severity = Severity.WARNING,
                        fixes = fixes,
                        message =
                            "Kotlin interpolates \$$name here, as the whole Datastar expression. Meant as a server value, that is fine; " +
                                "meant as the signal \$$name, make the whole literal \$\$\"\"\"...\"\"\" (Kotlin 2.2+), or escape as \${'$'}$name.",
                    )
                }

                // `data-signals="{count: $initialCount}"` seeds a signal from a server value as often as it is the trap.
                Where.PART -> {
                    i.copy(
                        severity = Severity.WARNING,
                        fixes = fixes,
                        message =
                            "Kotlin interpolates \$$name inside a Datastar expression. Meant as a server value, that is fine; " +
                                "meant as the signal \$$name, make the literal \$\$\"\"\"...\"\"\" (Kotlin 2.2+) or escape as \${'$'}$name.",
                    )
                }

                null -> {
                    i.copy(
                        severity = Severity.HINT,
                        fixes = emptyList(),
                        message = "Kotlin interpolates \$$name into the HTML. Make sure it is escaped.",
                    )
                }
            }
        }
    }

    private fun mapIssues(
        s: KotlinString,
        src: String,
        issues: List<Issue>,
    ): List<Issue> =
        issues.map { i ->
            val r = s.toSource(i.start, i.end)
            val fixes =
                i.fixes.map { f ->
                    if (f.start == f.end) {
                        val at = s.toSource(f.start, f.start + 1).first
                        f.copy(start = at, end = at)
                    } else {
                        val fr = s.toSource(f.start, f.end)
                        f.copy(start = fr.first, end = fr.last + 1, text = keepDollarEscape(s, src, f))
                    }
                }
            i.copy(start = r.first, end = r.last + 1, fixes = fixes)
        }

    /**
     * A fix that starts on a dollar the source wrote as an escape (`\$`, `${'$'}`) must write the
     * escape back: a bare `$` before a name would turn the signal into a Kotlin template.
     */
    private fun keepDollarEscape(
        s: KotlinString,
        src: String,
        fix: Fix,
    ): String {
        if (s.text.getOrNull(fix.start) != '$' || !fix.text.startsWith("$")) return fix.text
        val escape = src.substring(s.map[fix.start], s.map[fix.start + 1])
        return if (escape.length > 1) escape + fix.text.substring(1) else fix.text
    }

    private fun checkExpressionSite(
        site: CallSite,
        spec: CallSiteSpec,
        src: String,
    ): List<Issue> {
        if (spec.onlyIfStringArgs && site.args.any { it.named == null && it.string == null }) return emptyList()
        val s = site.selectString(spec) ?: return emptyList()
        if (s.unterminated) return emptyList()
        val issues = ArrayList(interpolationIssues(s, src))
        val text = s.text
        if (PLACEHOLDER in text && s.interpolations.any { it.kind == InterpolationKind.SIMPLE }) return issues
        issues += mapIssues(s, src, expressions.validate(text))
        return issues
    }

    private fun checkHtmlSite(
        site: CallSite,
        spec: CallSiteSpec,
        opts: AnalyzeOptions,
        src: String,
    ): List<Issue> {
        val s = markupString(site, spec)
        if (s == null && !site.trailingLambda) return emptyList()
        if (s != null && s.unterminated) return emptyList()
        // In every signature the selector and the mode follow the elements, so they may be given by
        // position. A trailing lambda holds the elements, so the selector comes first.
        val elements =
            if (s == null) -1 else (spec.arg ?: 0).takeIf { spec.named == null || site.named(spec.named) == null }
        val selectorArg = spec.selectorArg?.let { site.named(it) } ?: elements?.let { site.positional.getOrNull(it + 1) }
        val selector = selectorArg?.text?.takeIf { it != "null" }
        val modeArg = spec.modeArg?.let { site.named(it) } ?: elements?.let { site.positional.getOrNull(it + 2) }
        // `ElementPatchMode.APPEND`, or `APPEND` when the constant is imported. Anything else is a value not known here.
        val mode =
            modeArg?.let { arg ->
                val name =
                    MODE_NAME
                        .find(arg.text)
                        ?.groupValues
                        ?.get(1)
                        ?.lowercase()
                if (name == "default") "outer" else name?.takeIf { it in catalog.patchModes }
            }
        val issues = ArrayList(s?.let { interpolationHints(it, src, opts.prefix) }.orEmpty())
        if (modeArg != null && mode != null && mode != "outer" && mode != "replace" && selector == null) {
            val assign = ASSIGN_BEFORE.find(src.substring(0, modeArg.start))?.value?.length ?: 0
            val at = modeArg.start - (modeArg.named?.length ?: 0) - assign
            issues +=
                Issue(
                    start = site.nameStart,
                    end = site.openParen,
                    message = "Mode ${mode.uppercase()} requires a selector; Streamlord will reject this event at runtime.",
                    severity = Severity.ERROR,
                    code = "mode-needs-selector",
                    link = Docs.SSE,
                    fixes = if (modeArg.named != null) listOf(Fix("Add selector = \"\"", at, at, "selector = \"\", ")) else emptyList(),
                )
        }
        // A mode that cannot be read here (a variable) may be one that needs no ids.
        val requireIds = selector == null && (modeArg == null || mode == "outer")
        if (s != null) {
            issues += mapIssues(s, src, markup.validateMarkup(s.text, MarkupOptions(requireIds, opts.prefix, opts.checkHtmlAttributes)))
        }
        return issues
    }

    /**
     * The DSL writes a camelCase key as the kebab-case key Datastar reads back as that name, with
     * `__case` where Datastar's default is not camel. That is handled, but not hidden: a hint on
     * the call says what goes on the wire, because in HTML and templates the author writes it
     * that way themselves.
     */
    private fun checkKeyCaseSite(
        site: CallSite,
        src: String,
    ): List<Issue> {
        val spec = catalog.attributesByKotlin[site.name] ?: return emptyList()
        if (!spec.keyed || spec.keyCase == null) return emptyList()
        // Only helpers whose first parameter is a key: those whose expression is a later argument
        // (dataOn, dataSignals, dataClass, ...), the two Pro helpers that take a key and no
        // expression, and dataBind, dataRef and dataIndicator once a case moves their name into the
        // key. dataOnClick("expr") passes an expression, never a key.
        val expr = catalog.expressionCallSites[site.name]
        val valueUnlessCased = site.name in valueUnlessCasedHelpers
        if (if (expr != null) expr.arg == 0 else !(site.name in keyOnlyHelpers || valueUnlessCased)) return emptyList()
        val positional = site.positional
        val key = positional.firstOrNull()?.string ?: return emptyList()
        // With an expression helper, one positional string is the object form (dataClass("{...}")); the key form has two.
        if (key.interpolations.isNotEmpty() || (expr != null && positional.size < 2)) return emptyList()
        val name = key.text
        // dataSignals("{fooBar: 1}", ...) is the object form: an expression, not a key.
        if (!HAS_CAPITAL.containsMatchIn(name) || OBJECT_FORM.containsMatchIn(name)) return emptyList()
        // `case = Case.X`, a positional `Case.X` (dataRef("name", Case.CAMEL)) or `case = Case.X` in the trailing lambda.
        val named = site.args.firstOrNull { it.named == "case" || (it.named == null && CASE_ARG.matches(it.text)) }?.text
        val explicit = if (named != null) CASE_VALUE.find(named) else CASE_IN_LAMBDA.find(trailingLambdaText(site, src))
        val explicitCase = explicit?.groupValues?.get(1)?.lowercase()
        if (valueUnlessCased && explicitCase == null) return emptyList()
        val wire = wireKey(name, spec.keyCase, explicitCase)
        val what = if (explicitCase != null) "a $explicitCase-cased name" else keyReading(spec, name, wire)
        return listOf(
            Issue(
                start = key.start,
                end = key.end,
                message =
                    "Written on the wire as data-${spec.name}:$wire, which Datastar reads back as $what: the browser lowercases " +
                        "attribute names, so a key is kebab-case. In HTML or a template you write it that way yourself." +
                        rawKeyNote(spec, "data-", name),
                severity = Severity.HINT,
                code = "key-case-wire",
                link = Docs.attribute(spec.name),
            ),
        )
    }

    /** The text of the trailing lambda `{ ... }` after a call, braces balanced; empty when there is none. */
    private fun trailingLambdaText(
        site: CallSite,
        src: String,
    ): String {
        if (!site.trailingLambda) return ""
        val open = src.indexOf('{', site.closeParen + 1)
        if (open < 0) return ""
        var depth = 0
        for (i in open until src.length) {
            val c = src[i]
            if (c == '{') {
                depth++
            } else if (c == '}' && --depth == 0) {
                return src.substring(open, i + 1)
            }
        }
        return src.substring(open)
    }

    private fun checkScriptSite(
        site: CallSite,
        spec: CallSiteSpec,
    ): List<Issue> {
        val s = site.selectString(spec) ?: return emptyList()
        if (s.unterminated) return emptyList()
        val idx = s.text.indexOfAsciiIgnoreCase("</script", 0)
        if (idx < 0) return emptyList()
        val r = s.toSource(idx, idx + 8)
        return listOf(
            Issue(
                r.first,
                r.last + 1,
                "`</script` inside a script body. Streamlord escapes it to `<\\/script`, which only works inside a JavaScript string or regex.",
                Severity.WARNING,
                "script-close",
            ),
        )
    }

    private fun checkSelectorSite(
        site: CallSite,
        spec: CallSiteSpec,
    ): List<Issue> {
        val s = site.selectString(spec) ?: return emptyList()
        if (s.unterminated) return emptyList()
        if (s.text.isBlank()) return listOf(Issue(s.start, s.end, "Selector must not be blank.", Severity.ERROR, "blank-selector"))
        if (s.text.any { it == '\r' || it == '\n' }) {
            return listOf(Issue(s.start, s.end, "Selector must not contain line breaks.", Severity.ERROR, "selector-newline"))
        }
        return emptyList()
    }
}

/** The issues that belong to one DSL call, or to one free-standing HTML string. */
public data class SiteIssues(
    val site: CallSite?,
    val string: KotlinString?,
    val issues: List<Issue>,
)
