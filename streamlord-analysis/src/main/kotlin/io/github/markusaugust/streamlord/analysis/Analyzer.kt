package io.github.markusaugust.streamlord.analysis

import io.github.markusaugust.streamlord.core.catalog.CatalogNames
import io.github.markusaugust.streamlord.core.client.ClientCasing
import io.github.markusaugust.streamlord.core.client.asciiLowercase
import io.github.markusaugust.streamlord.core.client.decodeEntities
import io.github.markusaugust.streamlord.core.client.indexOfAsciiIgnoreCase
import io.github.markusaugust.streamlord.core.domain.AttributeKeys
import io.github.markusaugust.streamlord.core.domain.HoleLanding
import io.github.markusaugust.streamlord.core.domain.Position
import io.github.markusaugust.streamlord.core.domain.holeLandings
import io.github.markusaugust.streamlord.core.domain.urlSchemeFixedBy

/*
 * Editor-independent analysis: Kotlin or HTML source in, issues with source offsets out.
 */

public data class AnalyzeOptions(
    val prefix: String = CatalogNames.PREFIX,
    val checkHtmlAttributes: Boolean = true,
    /** The `const val` names the workspace declares: a value the author wrote, silent wherever it lands. */
    val constants: Set<String> = emptySet(),
    /** What the editor knows of the value in a hole, from types the text cannot show (IntelliJ). */
    val holeFact: ((start: Int, end: Int) -> HoleFact?)? = null,
)

/** What the editor knows of a value: its type, or that it is a constant or markup. */
public enum class HoleFact { STRING, NUMBER, BOOLEAN, CONSTANT, MARKUP }

private val MODE_NAME = Regex("""(?:^|\.)([A-Z]+)\s*$""")
private val CASE_ARG = Regex("""^\s*Case\.[A-Z]+\s*$""")
private val CASE_VALUE = Regex("""Case\.([A-Z]+)""")
private val CASE_IN_LAMBDA = Regex("""\bcase\s*=\s*Case\.([A-Z]+)""")
private val OBJECT_FORM = Regex("""^\s*[{\[]""")
private val HAS_CAPITAL = Regex("""[A-Z]""")

// A property set in a lambda of modifiers, `{ once = true }`, and its value; `==` is a comparison and
// `val once = 1` a variable of the lambda's own.
private val LAMBDA_ASSIGNMENT = Regex("""(?<!\b(?:val|var)\s)\b([A-Za-z]+)\s*=(?!=)\s*([^;\n}]*)""")
private val KOTLIN_STRING = Regex(""""(?:[^"\\]|\\.)*"""")

// The parameter a DSL helper takes its key by, when it is passed by name.
private val KEY_PARAMETERS = listOf("name", "event", "key", "property", "attribute", "signal")
private val DOLLAR_RUNS = Regex("""\$+""")
private val ASSIGN_BEFORE = Regex("""\s*=\s*$""")
private val BRACED_TEMPLATE = Regex("""\$\{""")
private val TRIM_AFTER = Regex("""\s*\.\s*trim(?:Indent|Margin)\s*\(\s*(?:"[^"\\\n]*"\s*)?\)""")
private val CHAIN_HTML_START = Regex("""^\s*<(?:[A-Za-z]|!--)""")
private val CLOSING_TAG_BEFORE = Regex("""</\s*\z""")
private val UNQUOTED_VALUE_BEFORE = Regex("""=[ \t\n\f\r]*[^\s"'=<>`]*\z""")

/** Where escaping is all a value needs, so that `interpolate(...)` takes it. */
private val ESCAPED_POSITIONS = setOf(Position.TEXT, Position.ATTRIBUTE, Position.URL)

/** kotlinx.html's own way to write any attribute: `attributes["data-on:click"] = "..."`. */
private val ATTRIBUTES_INDEX = Regex("""\battributes\s*\[\s*"(data-[^"\\]*)"\s*]\s*=\s*""")

/** What may follow the value of an assignment: the end of the statement, nothing more of an expression. */
private val STATEMENT_END = Regex("""[ \t]*(?:[;}\n\r]|$)""")

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

    /** Helpers that write the signal name in the value (the catalog's `valueKind` signal), which its call sites do not list. */
    private val valueNameHelpers =
        catalog.attributes
            .filter { it.valueKind == ValueKind.SIGNAL }
            .flatMap { it.kotlin }
            .toSet()

    /** Keyed helpers that take a key but no expression, so the catalog's expression sites do not list them. */
    private val keyOnlyHelpers =
        catalog.attributes
            .filter { it.keyed }
            .flatMap { it.kotlin }
            .toSet() - catalog.expressionCallSites.keys - valueNameHelpers

    // The helpers whose key is an HTML attribute's name, in which the DSL refuses a capital.
    private val attributeNameHelpers =
        catalog.attributes
            .filter { it.keyIsAttributeName }
            .flatMap { it.kotlin }
            .toSet()

    private val allSiteNames: Set<String> = catalog.allCallSiteNames + keyOnlyHelpers + valueNameHelpers
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
        val hyphenated = hyphenatedSignals(src, SourceLanguage.KOTLIN)
        for (site in findCallSites(src, allSiteNames, lex)) {
            val issues = ArrayList<Issue>()
            catalog.expressionCallSites[site.name]?.let { issues += checkExpressionSite(site, it, src, hyphenated, opts) }
            val html = catalog.htmlCallSites[site.name]
            html?.let { issues += checkHtmlSite(site, it, opts, src, hyphenated) }
            catalog.scriptCallSites[site.name]?.let { issues += checkScriptSite(site, it) }
            catalog.selectorCallSites[site.name]?.let { issues += checkSelectorSite(site, it) }
            issues += checkKeyCaseSite(site, src)
            for (a in site.args) a.string?.let { claimed += it.start }
            // The literals of a `+` chain handed to the site are judged there, as one string.
            val chain = html?.let { markupString(site, it, src) }
            if (chain != null && site.args.none { it.string === chain }) {
                for (x in lex.strings) if (x.start >= chain.start && x.end <= chain.end) claimed += x.start
            }
            if (issues.isNotEmpty()) out += SiteIssues(site, null, issues)
        }
        for ((site, issues) in signalNameHyphenSites(src, lex, dslHelpers(src, EXPRESSION_HELPERS), allSiteNames + "statements")) {
            out += SiteIssues(site, null, issues)
        }
        for (m in ATTRIBUTES_INDEX.findAll(src)) {
            if (!lex.mask[m.range.first]) continue
            val value = attributesIndexValue(src, m, lex)
            value?.let { claimed += it.start }
            val issues = checkAttributesIndex(m, value, opts, src, hyphenated)
            if (issues.isNotEmpty()) out += SiteIssues(null, value, issues)
        }
        // A literal joined to values with `+` is read as one string with a hole for each value.
        val chained = HashSet<Int>()
        val topLevel = lex.strings.toHashSet()
        for (s in withNested(lex.strings)) {
            if (s.start in claimed || s.start in chained || s.unterminated) continue
            // A literal inside a template is read on its own: its neighbours there are the template's code.
            val chain = if (s in topLevel) chainFrom(src, s, lex.mask) else null
            val whole = chain ?: s
            if (chain != null) for (at in chain.start until chain.end) if (src[at] == '"' || src[at] == '$') chained += at
            if (!isHtmlString(src, s) && !(chain != null && CHAIN_HTML_START.containsMatchIn(chain.text))) continue
            val issues = checkFreeHtmlString(whole, opts, src, hyphenated)
            if (issues.isNotEmpty()) out += SiteIssues(null, whole, issues)
        }
        return out
    }

    /** The string literal assigned in an `attributes["..."] =` match, when it is the whole value: not the first half of a concatenation. */
    private fun attributesIndexValue(
        src: String,
        m: MatchResult,
        lex: KotlinLex,
    ): KotlinString? {
        val s = lex.stringAt(m.range.last + 2)?.takeIf { it.start == m.range.last + 1 && !it.unterminated } ?: return null
        return s.takeIf { STATEMENT_END.matchesAt(src, it.end) }
    }

    /**
     * The signals with a hyphen [src] declares as the client stores them, so `$my-value` is not a slip
     * there: `data-signals:my-value__case.kebab`, `data-bind="my-value"`, `{'my-value': 1}`, or
     * `data-signals:a-1`, which camel casing leaves as it is.
     */
    private fun hyphenatedSignals(
        src: String,
        language: SourceLanguage,
    ): Set<String> = collectSignalDefinitions(src, language).filterTo(HashSet()) { '-' in it }

    /**
     * `attributes["data-on:click"] = "$count++"` judged as the attribute it writes: the name as in
     * markup, the value as the expression a DSL call takes, with Kotlin's interpolation trap. A
     * value that is not a literal is taken for a template, so only the name is judged.
     */
    private fun checkAttributesIndex(
        m: MatchResult,
        value: KotlinString?,
        opts: AnalyzeOptions,
        src: String,
        hyphenated: Set<String>,
    ): List<Issue> {
        if (!opts.checkHtmlAttributes) return emptyList()
        val name = m.groupValues[1]
        val nameStart = m.range.first + m.value.indexOf('"') + 1
        // The attribute in a space of its own, `name="value"`: the name at 0, the value after the `="`.
        val valueStart = name.length + 2
        val text = value?.text ?: PLACEHOLDER
        val tag = Tag("div", 0, valueStart + text.length + 1, false, false, listOf(Attribute(name, 0, text, valueStart, true)))
        val issues = ArrayList<Issue>()
        val kind = catalog.parseAttributeName(name.asciiLowercase(), opts.prefix)?.spec?.valueKind
        if (value != null &&
            (kind == ValueKind.EXPRESSION || kind == ValueKind.SIGNAL)
        ) {
            issues += expressionHoles(value, src, opts, name)
        }
        // With a template in the value the interpolation issue speaks for it, as at a DSL call.
        val judgeValue = value != null && !(PLACEHOLDER in value.text && value.interpolations.any { it.kind == InterpolationKind.SIMPLE })

        // A range in the value maps through the literal as a whole, so an error at its end keeps a width.
        fun range(
            start: Int,
            end: Int,
        ): IntRange =
            if (end <=
                name.length
            ) {
                nameStart + start until nameStart + end
            } else {
                value!!.toSource(start - valueStart, end - valueStart)
            }
        for (i in markup.validateAttributes(tag, opts.prefix, null, hyphenated, kotlin = true)) {
            val inName = i.end <= name.length
            if (!inName && !judgeValue) continue
            val r = range(i.start, i.end)
            val fixes =
                i.fixes.filter { (it.end <= name.length) == inName }.map { f ->
                    if (inName) {
                        f.copy(start = nameStart + f.start, end = nameStart + f.end)
                    } else {
                        // In the literal's own coordinates, so a `\$` the source wrote is written back.
                        val inValue = f.copy(start = f.start - valueStart, end = f.end - valueStart)
                        val at = value!!.toSource(inValue.start, maxOf(inValue.end, inValue.start + 1))
                        val fixEnd = if (f.start == f.end) at.first else at.last + 1
                        f.copy(start = at.first, end = fixEnd, text = keepDollarEscape(value, src, inValue))
                    }
                }
            issues += i.copy(start = r.first, end = r.last + 1, fixes = fixes)
        }
        return issues
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
     * With [src], a `+` chain is the markup too.
     */
    private fun markupString(
        site: CallSite,
        spec: CallSiteSpec,
        src: String? = null,
    ): KotlinString? {
        if (site.trailingLambda) return null
        val s = site.selectString(spec)
        if (s != null || src == null) return s
        // `patchElements("<p>" + name + "</p>")`: the argument is one string with a hole for the value.
        val positional = site.positional
        val arg =
            spec.named?.let { site.named(it) }
                ?: (if (spec.arg == null) positional.lastOrNull() else positional.getOrNull(spec.arg))
                ?: return null
        val lex = lex(src)
        val first = lex.strings.firstOrNull { it.start == arg.start } ?: return null
        return chainFrom(src, first, lex.mask)?.takeIf { it.end == arg.end }
    }

    /** Analyse an HTML document (a template): attributes and expressions only, no id or completeness rules. */
    public fun analyzeHtml(
        src: String,
        opts: AnalyzeOptions = AnalyzeOptions(),
    ): List<Issue> = analyzeHtml(src, opts, hyphenatedSignals(src, SourceLanguage.HTML))

    private fun analyzeHtml(
        src: String,
        opts: AnalyzeOptions,
        hyphenated: Set<String>,
    ): List<Issue> {
        val issues = ArrayList<Issue>()
        for (tag in tokenize(src).tags) {
            if (tag.closing) continue
            issues += markup.validateAttributes(tag, opts.prefix, src.substring(tag.start, tag.end), hyphenated)
        }
        return issues
    }

    /**
     * Every signal the Datastar expressions of a Kotlin file read: in the expression handed to a DSL
     * call, in the name handed to a helper such as `signal("count")`, and in the attributes of HTML
     * strings, handed to a call or free-standing. A Kotlin template (`$count` in a plain literal)
     * is not a signal and is left out; the interpolation check speaks for it.
     */
    public fun signalReferencesInKotlin(
        src: String,
        opts: AnalyzeOptions = AnalyzeOptions(),
    ): List<SignalReference> {
        val out = ArrayList<SignalReference>()
        val claimed = HashSet<Int>()
        val lex = lex(src)

        fun add(
            s: KotlinString,
            refs: List<SignalReference>,
            site: CallSite?,
        ) {
            for (ref in refs) {
                val range = s.toSource(ref.start, ref.end)
                out += ref.copy(start = range.first, end = range.last + 1, site = site)
            }
        }
        for (site in findCallSites(src, allSiteNames, lex)) {
            for (a in site.args) a.string?.let { claimed += it.start }
            catalog.expressionCallSites[site.name]?.let { spec ->
                if (spec.onlyIfStringArgs && site.args.any { it.named == null && it.string == null }) return@let
                val s = site.selectString(spec)?.takeUnless { it.unterminated } ?: return@let
                add(s, expressionSignalReferences(s.text), site)
            }
            catalog.htmlCallSites[site.name]?.let { spec ->
                val s = markupString(site, spec)?.takeUnless { it.unterminated } ?: return@let
                add(s, markupSignalReferences(s.text, catalog, opts.prefix), site)
            }
        }
        val helpers = dslHelpers(src).takeIf { it.isNotEmpty() }?.let { findCallSites(src, it, lex) } ?: emptyList()
        for (site in helpers) {
            // A call on a receiver, `filter.not("x")`, is not the top-level helper.
            if (src.lastNonSpaceBefore(site.nameStart) == '.') continue
            val arg = site.positional.firstOrNull() ?: site.named("name")
            val s = arg?.string?.takeUnless { it.unterminated } ?: continue
            add(s, listOfNotNull(helperSignalReference(s.text)), site)
        }
        for (m in ATTRIBUTES_INDEX.findAll(src)) {
            if (!lex.mask[m.range.first]) continue
            val s = attributesIndexValue(src, m, lex) ?: continue
            claimed += s.start
            if (catalog.parseAttributeName(m.groupValues[1].asciiLowercase(), opts.prefix)?.spec?.valueKind == ValueKind.EXPRESSION) {
                add(s, expressionSignalReferences(s.text), null)
            }
        }
        for (s in lex.strings) {
            if (s.start in claimed || s.unterminated || !isHtmlString(src, s)) continue
            add(s, markupSignalReferences(s.text, catalog, opts.prefix), null)
        }
        return out
    }

    /** Every signal the Datastar expressions of an HTML document or template read. */
    public fun signalReferencesInHtml(
        src: String,
        opts: AnalyzeOptions = AnalyzeOptions(),
    ): List<SignalReference> = markupSignalReferences(src, catalog, opts.prefix)

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
        hyphenated: Set<String>,
    ): List<Issue> {
        val view = markupView(src, s)
        val issues = ArrayList(judgeHoles(s, src, opts, view))
        if (opts.checkHtmlAttributes) issues += mapIssues(s, src, analyzeHtml(view, opts, hyphenated))
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

    /**
     * Each hole of an HTML string, judged by where it lands as `interpolate` judges it at run time
     * (core's [holeLandings]): escaping saves it, or nothing does. What the analysis can see is the
     * author's own (a constant, a literal, markup built here) is left alone; what it cannot see
     * into is never an error, since a build fails on errors and the text cannot tell `count: Int`
     * from a String.
     */
    private fun judgeHoles(
        s: KotlinString,
        src: String,
        opts: AnalyzeOptions,
        view: String,
    ): List<Issue> {
        if (s.interpolations.isEmpty()) return emptyList()
        val traps = interpolationIssues(s, src).associateBy { it.start }
        val landings =
            holeLandings(
                view,
                s.interpolations.map { it.decodedStart until it.decodedStart + PLACEHOLDER.length },
                // Both bundles' prefixes, as interpolate reads them: a value is judged by what either would run.
                listOf(opts.prefix, CatalogNames.PREFIX, CatalogNames.ALIASED_PREFIX).distinct(),
            )
        val facts = s.interpolations.map { holeFact(s, src, opts, it) }
        // interpolate(...) can take the literal only when every hole is a value it escapes and keeps.
        val fixable =
            s.dollars == 1 &&
                !trimmed(src, s) &&
                s.interpolations.none { it.kind == InterpolationKind.OPERAND } &&
                landings.indices.all { k -> landings[k].position in ESCAPED_POSITIONS && facts[k] != HoleFact.MARKUP }
        val issues = ArrayList<Issue>()
        for ((k, ip) in s.interpolations.withIndex()) {
            val landing = landings[k]
            val fact = facts[k]
            val shown = holeDisplay(ip, s.dollars)
            // Markup is the author's own in element text; in an attribute or code it is characters that can break out.
            val own = fact == HoleFact.CONSTANT || (fact == HoleFact.MARKUP && landing.position == Position.TEXT)
            val known = own || fact == HoleFact.NUMBER || fact == HoleFact.BOOLEAN

            fun issue(
                severity: Severity,
                code: String,
                message: String,
                fix: Boolean = false,
            ): Issue {
                val fixes = if (fix && fixable) listOf(interpolateFix(src, s)) else emptyList()
                return Issue(ip.start, ip.end, message, severity, code, Docs.EXPRESSIONS, fixes)
            }

            fun unescaped(): Issue {
                val severity = if (fact == HoleFact.STRING) Severity.WARNING else Severity.HINT
                return issue(severity, "unescaped-interpolation", unescapedMessage(shown), fix = true)
            }
            when (landing.position) {
                Position.TEXT, Position.ATTRIBUTE -> {
                    if (!known) issues += unescaped()
                }

                Position.URL -> {
                    if (known) continue
                    val attribute = landing.attribute ?: "the URL"
                    val before = decodeEntities(view.substring(landing.valueStart, ip.decodedStart)).text
                    issues +=
                        if (urlSchemeFixedBy(attribute, before)) {
                            unescaped()
                        } else {
                            issue(
                                Severity.WARNING,
                                "unsafe-interpolation",
                                "Kotlin writes $shown at the start of $attribute, so the value decides the scheme, " +
                                    "and a javascript: URL runs. Escaping does not help: start the value with /, # or https://, " +
                                    "or build the string with interpolate(...), which refuses a scheme that runs.",
                                fix = true,
                            )
                        }
                }

                Position.STYLE -> {
                    if (own || fact == HoleFact.NUMBER) continue
                    issues +=
                        issue(
                            Severity.WARNING,
                            "unsafe-interpolation",
                            "Kotlin writes $shown into ${landing.attribute ?: "style"}, where it is read as CSS, " +
                                "so escaping does not help. Choose between classes, or write a number.",
                        )
                }

                Position.DATASTAR -> {
                    val trap = traps[ip.start]?.takeIf { ip.kind == InterpolationKind.SIMPLE }
                    val judged = trap?.let { datastarTrap(it, ip, landing, view, opts.prefix) }
                    if (judged != null) {
                        issues += judged
                    } else if (!known) {
                        val attribute = landing.attribute ?: "a Datastar attribute"
                        issues += issue(Severity.WARNING, "unsafe-interpolation", datastarMessage(shown, attribute))
                    }
                }

                Position.CODE -> {
                    if (own) continue
                    issues +=
                        issue(
                            Severity.WARNING,
                            "unsafe-interpolation",
                            "Kotlin writes $shown into ${landing.attribute ?: "a script or style element"}, which the browser runs, " +
                                "so escaping does not help. Put the value in a data-* attribute or a signal and read it from there.",
                        )
                }

                Position.STRUCTURE -> {
                    if (known) continue
                    val before = view.substring(0, ip.decodedStart)
                    // `</$tag>` names the element `<$tag` opened; the opening one is judged.
                    if (CLOSING_TAG_BEFORE.containsMatchIn(before) && s.interpolations.take(k).any { it.text == ip.text }) continue
                    issues +=
                        if (UNQUOTED_VALUE_BEFORE.containsMatchIn(before)) {
                            issue(
                                Severity.WARNING,
                                "unsafe-interpolation",
                                "Kotlin writes $shown into an attribute value without quotes, where a space starts a new attribute. Quote the value.",
                            )
                        } else {
                            issue(
                                if (fact == HoleFact.STRING) Severity.WARNING else Severity.HINT,
                                "unsafe-interpolation",
                                "Kotlin writes $shown into the markup's own structure: a tag name, an attribute name " +
                                    "or a list of attributes. Fine for one chosen in code; a value from outside decides " +
                                    "the markup itself, and escaping cannot prevent that.",
                            )
                        }
                }
            }
        }
        return issues
    }

    /** What the analysis knows of a hole's value: the editor's facts first, then what the text shows. */
    private fun holeFact(
        s: KotlinString,
        src: String,
        opts: AnalyzeOptions,
        ip: Interpolation,
    ): HoleFact? {
        val known = opts.holeFact?.invoke(ip.start, ip.end)
        if (known == HoleFact.CONSTANT || known == HoleFact.MARKUP) return known
        if (isMarkup(src, s, ip)) return HoleFact.MARKUP
        if (ip.kind != InterpolationKind.SIMPLE && literalOnly(ip.text)) return HoleFact.CONSTANT
        val name = plainName(ip)
        if (name != null && name in opts.constants && !shadowed(src, name, s.start)) return HoleFact.CONSTANT
        return known ?: name?.let { declaredKind(src, it, s.start) }
    }

    private fun unescapedMessage(shown: String): String =
        "Kotlin writes $shown into the HTML as it is. If it can hold text from outside, build the string with interpolate(...), which escapes it."

    private fun datastarMessage(
        shown: String,
        attribute: String,
    ): String =
        "Kotlin writes $shown into $attribute, which Datastar runs as an expression or follows as a signal path. " +
            "Text from outside becomes code there, even inside quotes. " +
            "Put the value in a signal, or in an attribute of its own read with el.dataset; only a number or a boolean is safe to write in."

    /**
     * `$name` where a signal could stand: the Kotlin value or the signal of that name, which only the
     * author knows. Null when the hole sits in a JavaScript string or an attribute that takes no
     * expression, where no signal was ever possible and the value is what is judged.
     */
    private fun datastarTrap(
        trap: Issue,
        ip: Interpolation,
        landing: HoleLanding,
        view: String,
        prefix: String,
    ): Issue? {
        val attribute = landing.attribute ?: return null
        if (landing.valueStart < 0) return null
        val spec = catalog.parseAttributeName(attribute.asciiLowercase(), prefix)?.spec
        if (spec?.valueKind != ValueKind.EXPRESSION) return null
        val quote = view.getOrNull(landing.valueStart - 1) ?: '"'
        val valueEnd = view.indexOf(quote, landing.valueStart)
        val value = view.substring(landing.valueStart, if (valueEnd < 0) view.length else valueEnd)
        val before = decodeEntities(value.substring(0, ip.decodedStart - landing.valueStart)).text
        if (quoteAt(before, before.length) != null) return null
        val name = ip.text
        val fixes = trap.fixes.filter { it.title.startsWith("Make it") || it.title.startsWith("Escape") }
        // data-signals:count="$initial" seeds a signal from the server, like the object form does.
        val whole = spec.name != "signals" && value.trim() == PLACEHOLDER
        return trap.copy(
            severity = Severity.WARNING,
            fixes = fixes,
            message =
                if (whole) {
                    "Kotlin interpolates \$$name here, as the whole Datastar expression. " +
                        "Meant as a server value, only a number or a boolean is safe there; " +
                        "meant as the signal \$$name, make the whole literal \$\$\"\"\"...\"\"\" (Kotlin 2.2+), or escape as \${'$'}$name."
                } else {
                    "Kotlin interpolates \$$name inside a Datastar expression. " +
                        "Meant as a server value, only a number or a boolean is safe there; " +
                        "meant as the signal \$$name, make the literal \$\$\"\"\"...\"\"\" (Kotlin 2.2+) or escape as \${'$'}$name."
                },
        )
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
        hyphenated: Set<String>,
        opts: AnalyzeOptions,
    ): List<Issue> {
        if (spec.onlyIfStringArgs && site.args.any { it.named == null && it.string == null }) return emptyList()
        val s = site.selectString(spec) ?: return emptyList()
        if (s.unterminated) return emptyList()
        val attribute = catalog.attributesByKotlin[site.name]
        val named = attribute?.let { CatalogNames.PREFIX + it.name } ?: "the expression of ${site.name}"
        val issues = ArrayList(expressionHoles(s, src, opts, named))
        val text = s.text
        if (PLACEHOLDER in text && s.interpolations.any { it.kind == InterpolationKind.SIMPLE }) return issues
        issues += mapIssues(s, src, expressions.validate(text, hyphenatedSignals = hyphenated, attribute = attribute))
        return issues
    }

    /**
     * The templates of a literal Datastar runs as an expression. `$name` where a signal could stand
     * is the trap. Any other template, `${expr}`, one inside a JavaScript string (`@post('/x/$id')`,
     * where Datastar leaves a `$` alone, engine.ts:448 in 1.0.4), or one in a `$$` literal, writes
     * the Kotlin value into code Datastar runs: a `'` in text from outside ends the string and the
     * rest runs. A number or a boolean is safe there, as `interpolate` lets in.
     */
    private fun expressionHoles(
        s: KotlinString,
        src: String,
        opts: AnalyzeOptions,
        attribute: String,
    ): List<Issue> {
        val traps = interpolationIssues(s, src).associateBy { it.start }
        val out = ArrayList<Issue>()
        for (ip in s.interpolations) {
            val trap = traps[ip.start]
            if (trap != null && quoteAt(s.text, ip.decodedStart) == null) {
                out += trap
                continue
            }
            val fact = holeFact(s, src, opts, ip)
            if (fact == HoleFact.CONSTANT || fact == HoleFact.NUMBER || fact == HoleFact.BOOLEAN) continue
            out +=
                Issue(
                    start = ip.start,
                    end = ip.end,
                    message = datastarMessage(holeDisplay(ip, s.dollars), attribute),
                    severity = Severity.WARNING,
                    code = "unsafe-interpolation",
                    link = Docs.EXPRESSIONS,
                )
        }
        return out
    }

    private fun checkHtmlSite(
        site: CallSite,
        spec: CallSiteSpec,
        opts: AnalyzeOptions,
        src: String,
        hyphenated: Set<String>,
    ): List<Issue> {
        val s = markupString(site, spec, src)
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
        val view = s?.let { markupView(src, it) } ?: ""
        val issues = ArrayList(s?.let { judgeHoles(it, src, opts, view) }.orEmpty())
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
            issues +=
                mapIssues(
                    s,
                    src,
                    markup.validateMarkup(view, MarkupOptions(requireIds, opts.prefix, opts.checkHtmlAttributes, hyphenated)),
                )
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
        if (site.name in valueNameHelpers) return valueNameIssues(site, spec)
        if (!spec.keyed || spec.keyCase == null) return emptyList()
        // Only helpers whose first parameter is a key: those whose expression is a later argument
        // (dataOn, dataSignals, dataClass, ...) and the two Pro helpers that take a key and no
        // expression. dataOnClick("expr") passes an expression, never a key.
        val expr = catalog.expressionCallSites[site.name]
        if (site.name in attributeNameHelpers) return attributeNameIssues(site, spec, src)
        if (if (expr != null) expr.arg == 0 else site.name !in keyOnlyHelpers) return emptyList()
        val positional = site.positional
        val namedKey = KEY_PARAMETERS.firstNotNullOfOrNull { site.named(it) }?.string
        val key = namedKey ?: positional.firstOrNull()?.string ?: return emptyList()
        // With an expression helper, one positional string is the object form (dataClass("{...}")); the key form has two.
        val objectForm = namedKey == null && expr != null && positional.size < 2 && site.named("expression") == null
        if (key.interpolations.isNotEmpty() || objectForm) return emptyList()
        val name = key.text
        // dataSignals("{fooBar: 1}", ...) is the object form: an expression, not a key.
        if (OBJECT_FORM.containsMatchIn(name)) return emptyList()
        // A key that names a signal goes through Casing.signalName, which refuses more than a key.
        val signalKey = spec.keyCase == KeyCase.CAMEL
        (
            if (signalKey) {
                signalNameRefusal(
                    name,
                )
            } else {
                AttributeKeys.nameProblem(name)
            }
        )?.let { return listOf(refusedKey(spec, key, name, it)) }
        // A hyphen it refuses before the modifiers are joined on, which signalNameHyphenIssues says.
        if (signalKey && '-' in name) return emptyList()
        // `case = Case.X`, a positional `Case.X` (dataClass("name", "x", Case.CAMEL)) or `case = Case.X` in the trailing lambda.
        val named = site.args.firstOrNull { it.named == "case" || (it.named == null && CASE_ARG.matches(it.text)) }?.text
        val explicit = if (named != null) CASE_VALUE.find(named) else CASE_IN_LAMBDA.find(modifiersLambda(site, src))
        val explicitCase = explicit?.groupValues?.get(1)?.lowercase()
        val capital = HAS_CAPITAL.containsMatchIn(name)
        val wire = if (capital) wireKey(name, spec.keyCase, explicitCase) else name + (explicitCase?.let { "__case.$it" } ?: "")
        // What the DSL writes after the key, as Casing.withModifiers is handed it: the case, then the rest.
        if (wire != null) {
            // The key itself, not what comes before the first __: in b___case.pascal that is b.
            val keyPart = if (capital) ClientCasing.kebab(name) else name
            val modifiers = wire.removePrefix(keyPart) + if (addsModifiers(site, spec, src)) "__" else ""
            AttributeKeys.runsIntoModifiers(keyPart, modifiers)?.let { return listOf(refusedKey(spec, key, name, it)) }
        }
        if (!capital) return emptyList()
        if (wire == null) {
            return listOf(
                Issue(
                    start = key.start,
                    end = key.end,
                    message =
                        "No key gives $name back: the browser lowercases attribute names, and with __case.camel or __case.pascal " +
                            "Datastar turns every hyphen before a lower-case letter into a capital and reads ${ClientCasing.readsAs(
                                name,
                            )}. " +
                            "Write it in one case.",
                    severity = Severity.HINT,
                    code = "key-case-wire",
                    link = Docs.attribute(spec.name),
                ),
            )
        }
        val what = if (explicitCase != null) "a $explicitCase-cased name" else keyReading(spec, ClientCasing.readKey(wire), wire)
        return listOf(
            Issue(
                start = key.start,
                end = key.end,
                message =
                    "Written on the wire as ${CatalogNames.PREFIX}${spec.name}:$wire, which Datastar reads back as $what: " +
                        "the browser lowercases attribute names, so a key is kebab-case. " +
                        "In HTML or a template you write it that way yourself." +
                        rawKeyNote(spec, CatalogNames.PREFIX, name),
                severity = Severity.HINT,
                code = "key-case-wire",
                link = Docs.attribute(spec.name),
            ),
        )
    }

    /**
     * Whether the DSL writes a modifier besides `__case` after the key of [site], as far as its text
     * shows: `ifMissing` of `dataSignals` and `session` of `dataPersist` set to true, or a modifier of
     * the attribute set in the lambda of modifiers, to anything but false or null. What it writes
     * does not matter, only that the key would run into it.
     */
    private fun addsModifiers(
        site: CallSite,
        spec: AttributeSpec,
        src: String,
    ): Boolean {
        fun on(parameter: String) = (site.named(parameter) ?: site.positional.getOrNull(2))?.text?.trim() == "true"
        if (site.name == "dataSignals" && on("ifMissing")) return true
        if (site.name == "dataPersist" && on("session")) return true
        val names = spec.modifiers.map { it.name.lowercase() }.filter { it != "case" }
        return LAMBDA_ASSIGNMENT.findAll(modifiersLambda(site, src).replace(KOTLIN_STRING, "\"\"")).any { m ->
            val property = m.groupValues[1].lowercase()
            val value = m.groupValues[2].trim()
            value != "false" && value != "null" && names.any { property.startsWith(it) }
        }
    }

    /** The lambda of modifiers of [site]: the trailing one, or one passed as an argument, by name or not. */
    private fun modifiersLambda(
        site: CallSite,
        src: String,
    ): String =
        trailingLambdaText(site, src).ifEmpty {
            (site.named("modifiers") ?: site.positional.firstOrNull { it.text.trimStart().startsWith("{") })?.text ?: ""
        }

    /** A name `dataBind`, `dataRef` or `dataIndicator` writes in the value that `Casing.signalName` refuses, as it does in a key ([signalNameRefusal]). */
    private fun valueNameIssues(
        site: CallSite,
        spec: AttributeSpec,
    ): List<Issue> {
        val name = (site.named("signal") ?: site.named("name") ?: site.positional.firstOrNull())?.string ?: return emptyList()
        if (name.interpolations.isNotEmpty()) return emptyList()
        return listOfNotNull(signalNameRefusal(name.text)?.let { refusedKey(spec, name, name.text, it) })
    }

    /** A key the DSL refuses at runtime, as `Casing` refuses it, where a hint would say what it writes. */
    private fun refusedKey(
        spec: AttributeSpec,
        key: KotlinString,
        name: String,
        reason: String,
    ): Issue =
        Issue(
            start = key.start,
            end = key.end,
            message = "Streamlord refuses \"$name\" at runtime: it $reason.",
            severity = Severity.ERROR,
            code = "key-refused",
            link = Docs.attribute(spec.name),
        )

    /**
     * A capital in the key of `dataAttr` or `dataAnimate`, which the DSL refuses at runtime: the
     * browser lowercases an attribute name, so `viewBox` could only reach `view-box`. The fixes are
     * the spellings HTML may have, lowercased for a name with a hyphen already (`aria-Label`), else
     * kebab-case or one word, which only the attribute tells apart (`aria-label`, `tabindex`); one
     * that really has capitals, such as SVG's `viewBox`, the message sends to the object form.
     */
    private fun attributeNameIssues(
        site: CallSite,
        spec: AttributeSpec,
        src: String,
    ): List<Issue> {
        // The key form takes the name and an expression; one positional string is the object form.
        val key =
            (site.named("name") ?: site.named("attribute"))?.string
                ?: site.positional
                    .takeIf { it.size >= 2 || site.named("expression") != null }
                    ?.first()
                    ?.string
                ?: return emptyList()
        val name = key.text
        if (key.interpolations.isNotEmpty() || OBJECT_FORM.containsMatchIn(name)) return emptyList()
        AttributeKeys.nameProblem(name)?.let { return listOf(refusedKey(spec, key, name, it)) }
        if (!HAS_CAPITAL.containsMatchIn(name)) {
            val modifiers = if (addsModifiers(site, spec, src)) "__" else ""
            return listOfNotNull(AttributeKeys.runsIntoModifiers(name, modifiers)?.let { refusedKey(spec, key, name, it) })
        }
        val reason = AttributeKeys.capitalIn(name, site.name) ?: return emptyList()
        val plain = src.substring(key.contentStart, key.contentEnd) == name
        return listOf(
            refusedKey(spec, key, name, reason).copy(
                code = "attribute-name-case",
                fixes =
                    if (plain) {
                        AttributeKeys
                            .spellings(
                                name,
                            ).map { Fix("Change to $it", key.contentStart, key.contentEnd, it) }
                    } else {
                        emptyList()
                    },
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

internal fun String.lastNonSpaceBefore(index: Int): Char? {
    var i = index - 1
    while (i >= 0 && this[i].isWhitespace()) i--
    return getOrNull(i)
}
