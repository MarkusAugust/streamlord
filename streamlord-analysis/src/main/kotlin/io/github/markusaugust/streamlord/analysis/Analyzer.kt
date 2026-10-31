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

/**
 * What the editor knows of a value: its type, or that it is a constant or markup. [NULLABLE] is a
 * number or a boolean that may be null (`Int?`): digits, true, false or null. [NULLABLE_STRING] is
 * a `String?`, which a Kotlin template writes as `null` and `interpolate` as nothing.
 */
public enum class HoleFact { STRING, NULLABLE_STRING, NUMBER, BOOLEAN, NULLABLE, CONSTANT, MARKUP }

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
private val CHAIN_HTML_START = Regex("""^\s*<(?:[A-Za-z]|__kt__|!--)""")
private val END_TAG_ATTRIBUTES = Regex("""</[^\t\n\u000C\r />]*[\t\n\u000C\r /][^>]*""")

/** The characters that end an unquoted attribute value, and the space before one: ASCII only, as HTML has it. */
private const val UNQUOTED_STOP = " \t\n\u000C\r\u000B\"'=<>`"

/** Where escaping is all a value needs, so that `interpolate(...)` takes it. */
private val ESCAPED_POSITIONS = setOf(Position.TEXT, Position.ATTRIBUTE, Position.URL)
private val TRUSTED_CALL = Regex("""(?:^|[^\p{L}\p{Nd}_.])Trusted\s*\(""")

private val INTERPOLATE = setOf("interpolate")
private val FORMAT_SPECIFIER = Regex("""%[-#+0,(]*\d*(?:\.\d+)?[dfxXoeEgGcb](?![A-Za-z])""")

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
        val facts = fileFacts(src, lex)
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
            val expression = catalog.expressionCallSites[site.name]
            val chain = if (html != null) markupString(site, html, src) else expression?.let { expressionString(site, it, src) }
            if (chain != null && site.args.none { it.string === chain }) {
                for (x in lex.strings) if (x.start >= chain.start && x.end <= chain.end) claimed += x.start
            }
            if (issues.isNotEmpty()) out += SiteIssues(site, null, issues)
        }
        for (site in findCallSites(src, INTERPOLATE, lex)) {
            val format = interpolateFormat(site, src) ?: continue
            for (x in lex.strings) if (x.start >= format.second.start && x.end <= format.second.end) claimed += x.start
            val issues = checkInterpolateSite(site, format.first, format.second, src, opts, hyphenated)
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
            // A literal inside a template is joined to its neighbours in the template's code.
            val chain = chainFrom(src, s, if (s in topLevel) lex.mask else facts.code)
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
        return chainArg(site, spec, src)
    }

    /** The argument a call site takes its string from, when it is a `+` chain that starts with a literal: `patchElements("<p>" + name + "</p>")`. */
    private fun chainArg(
        site: CallSite,
        spec: CallSiteSpec,
        src: String,
    ): KotlinString? {
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

    /** The format handed to `interpolate`: its first argument or `format =`, a literal or a `+` chain that starts with one. */
    private fun interpolateFormat(
        site: CallSite,
        src: String,
    ): Pair<Arg, KotlinString>? {
        val arg = site.named("format") ?: site.positional.firstOrNull() ?: return null
        arg.string?.let { return if (it.unterminated) null else arg to it }
        val lex = lex(src)
        val first = lex.strings.firstOrNull { it.start == arg.start } ?: return null
        val chain = chainFrom(src, first, lex.mask)?.takeIf { it.end == arg.end } ?: return null
        return arg to chain
    }

    /**
     * A call to `interpolate`, judged as it will run. A Kotlin template in the format is filled in
     * before `interpolate` sees the markup, so it is neither escaped nor refused. The `%s` holes
     * are counted against the values, and each is judged by where it lands and what the text shows
     * the value to be: core's `admits`, so an error only where `interpolate` throws for certain.
     * The format is read as HTML like any free string; it is not a patch, so no id is asked for.
     */
    private fun checkInterpolateSite(
        site: CallSite,
        formatArg: Arg,
        s: KotlinString,
        src: String,
        opts: AnalyzeOptions,
        hyphenated: Set<String>,
    ): List<Issue> {
        val holes = percentHoles(s.text)
        val (format, at) = withPercentHoles(s, holes)
        val values = site.args.filter { it !== formatArg && it.named == null }
        // The values one by one, as the vararg overload takes them: no spread and nothing by name.
        val plain = site.args.all { it.named == null } && values.none { it.text.startsWith("*") }
        val known = values.map { opts.holeFact?.invoke(it.start, it.end) }
        val kinds = values.mapIndexed { k, it -> factKind(known[k]) ?: valueKind(src, it.text, it.string != null, s.start) }
        val issues = ArrayList<Issue>()
        // The overload that takes a List is called with one or two values, each of which could be a List.
        val counted = plain && (values.isEmpty() || values.size >= 3 || kinds.any { it != null })
        if (counted && holes.size != values.size) {
            val h = holes.size
            val v = values.size
            // `%d` as String.format writes it: interpolate knows only %s.
            val specifier = FORMAT_SPECIFIER.find(s.text.replace("%%", "  "))?.value
            val advice =
                if (specifier != null) {
                    "Only %s is a hole, whatever the value: write %s in place of $specifier."
                } else {
                    "Write %% for a per cent sign that is not a hole."
                }
            issues +=
                Issue(
                    site.nameStart,
                    site.nameStart + site.name.length,
                    "interpolate throws IllegalArgumentException here: the markup has $h %s ${if (h == 1) "hole" else "holes"} and " +
                        "$v ${if (v == 1) "value is" else "values are"} given. $advice",
                    Severity.ERROR,
                    "interpolate-holes",
                )
        }
        val mapped = plain && holes.size == values.size
        // With one or two values the first of which is a List, the call is the List overload's, and a
        // value added would make it the vararg one's, which writes the List as one value.
        val listOverload =
            values.size in 1..2 && kinds[0] == null && known[0] == null && visiblyList(src, values[0].text, s.start)
        val view = markupView(src, format)
        val landings =
            holeLandings(
                view,
                (format.interpolations.map { it.decodedStart } + at).map { it until it + PLACEHOLDER.length },
                listOf(opts.prefix, CatalogNames.PREFIX, CatalogNames.ALIASED_PREFIX).distinct(),
            )
        val trapOf = traps(format, src)
        for ((k, ip) in format.interpolations.withIndex()) {
            val landing = landings[k]
            val told = holeFact(format, src, opts, ip)
            val fact = plainFact(told)
            if (ip.kind == InterpolationKind.SIMPLE && landing.position == Position.DATASTAR) {
                val judged = trapOf(ip)?.let { datastarTrap(it, ip, landing, view, opts.prefix, fact) }
                if (judged != null) {
                    issues += judged
                    continue
                }
            }
            if (fact == HoleFact.CONSTANT || fact == HoleFact.NUMBER || fact == HoleFact.BOOLEAN) continue
            if (fact == HoleFact.MARKUP && landing.position == Position.TEXT) continue
            if (fact == HoleFact.NULLABLE && landing.position in ESCAPED_POSITIONS) continue
            val shown = holeDisplay(ip, format.dollars)
            // A `%` right before the template would make `%%s` of the hole: a per cent sign and an s.
            val percents = runBefore(format.text, ip.decodedStart, '%')
            // A %s hole writes nothing for null and escapes markup, so the fix would change the page.
            val keeps =
                told != HoleFact.NULLABLE_STRING &&
                    !mayBeNull(src, ip.text, s.start) &&
                    !(landing.position == Position.TEXT && mayHoldMarkup(src, ip.text, s.start, opts.constants))
            val fixable =
                landing.position in ESCAPED_POSITIONS && ip.kind != InterpolationKind.OPERAND && mapped && !listOverload &&
                    percents % 2 == 0 && keeps
            val fixes = if (fixable) listOf(holeFix(ip, shown, at, values, formatArg)) else emptyList()
            val message = templateMessage(shown, landing, endTagAt(view, ip.decodedStart))
            issues += Issue(ip.start, ip.end, message, Severity.WARNING, "interpolate-template", Docs.EXPRESSIONS, fixes)
        }
        for ((k, d) in at.withIndex()) {
            val landing = landings[format.interpolations.size + k]
            val arg = if (mapped) values[k] else null
            val kind = if (arg != null) kinds[k] else null
            val r = format.toSource(d, d + PLACEHOLDER.length)
            if (landing.position in ESCAPED_POSITIONS) {
                // Markup held as a String is text to interpolate: in element text it escapes the tags, which are then printed.
                if (landing.position == Position.TEXT && arg != null && kind != ArgKind.MARKUP && kind != ArgKind.TRUSTED &&
                    markupHeldAsString(src, arg.text, s.start)
                ) {
                    val message = escapedMarkupMessage(shortened(arg.text))
                    issues += Issue(r.first, r.last + 1, message, Severity.WARNING, "interpolate-template", Docs.EXPRESSIONS)
                }
                continue
            }
            val allowed =
                when (landing.position) {
                    Position.DATASTAR -> setOf(ArgKind.NUMBER, ArgKind.BOOLEAN)
                    Position.STYLE -> setOf(ArgKind.NUMBER)
                    else -> emptySet<ArgKind>()
                }
            if (kind == ArgKind.TRUSTED || kind in allowed) continue
            val shown = arg?.let { shortened(it.text) } ?: "the value of this %s"
            issues +=
                Issue(
                    r.first,
                    r.last + 1,
                    refusedMessage(shown, landing, kind != null, endTagAt(view, d)),
                    if (kind == null) Severity.WARNING else Severity.ERROR,
                    "unsafe-interpolation",
                    Docs.EXPRESSIONS,
                )
        }
        if (opts.checkHtmlAttributes) issues += mapIssues(format, src, analyzeHtml(view, opts, hyphenated))
        return issues
    }

    private fun factKind(fact: HoleFact?): ArgKind? =
        when (fact) {
            HoleFact.STRING, HoleFact.NULLABLE_STRING -> ArgKind.STRING
            HoleFact.NUMBER -> ArgKind.NUMBER
            HoleFact.BOOLEAN -> ArgKind.BOOLEAN
            HoleFact.MARKUP -> ArgKind.MARKUP
            else -> null
        }

    /** A fact as the judgement of a hole reads it: a `String?` is a String, whose null only the fixes ask about. */
    private fun plainFact(fact: HoleFact?): HoleFact? = if (fact == HoleFact.NULLABLE_STRING) HoleFact.STRING else fact

    /** How many times [c] stands right before [at] in [text]. */
    private fun runBefore(
        text: String,
        at: Int,
        c: Char,
    ): Int {
        var k = at
        while (k > 0 && text[k - 1] == c) k--
        return at - k
    }

    /** Whether [at] in the markup is past the name of an end tag, `</p x="%s">`, whose attributes the browser drops. */
    private fun endTagAt(
        view: String,
        at: Int,
    ): Boolean {
        val lt = if (at > 0) view.lastIndexOf('<', at - 1) else -1
        return lt >= 0 && END_TAG_ATTRIBUTES.matches(view.substring(lt, at))
    }

    /** The template made a `%s` hole in place, and its expression passed as the value at the same place among the others. */
    private fun holeFix(
        ip: Interpolation,
        shown: String,
        holes: List<Int>,
        values: List<Arg>,
        format: Arg,
    ): Fix {
        val index = countBelow(holes, ip.decodedStart)
        val expr = if (ip.kind == InterpolationKind.SIMPLE) ip.text else ip.text.trim()
        val before = values.getOrNull(index)
        val after = values.lastOrNull() ?: format
        val edit = if (before != null) Edit(before.start, before.start, "$expr, ") else Edit(after.end, after.end, ", $expr")
        return Fix("Make $shown a %s hole, which interpolate escapes", ip.start, ip.end, "%s", listOf(edit))
    }

    /** Where a hole that is not escaped lands, said for a message, and what to write instead. */
    private fun landingWords(
        landing: HoleLanding,
        endTag: Boolean,
    ): Pair<String, String> =
        when (landing.position) {
            Position.DATASTAR -> {
                "${landing.attribute ?: "a Datastar attribute"}, which Datastar runs as an expression or follows as a signal path" to
                    "Put the value in a signal and reference it from the expression, or write the signal's name in the markup yourself."
            }

            Position.STYLE -> {
                "${landing.attribute ?: "style"}, which is read as CSS" to
                    "Pass a number and write the unit in the markup, or choose between classes."
            }

            Position.CODE -> {
                "${landing.attribute ?: "a script or style element"}, which the browser runs" to
                    "Put the value in a data-* attribute or a signal and read it from there; wrap markup you built yourself in Trusted(...)."
            }

            else -> {
                if (endTag) {
                    "an end tag, which takes no attributes" to "Write the end tag from literals alone."
                } else {
                    "the markup's own structure, a tag name, an attribute name or an unquoted value" to
                        "Build that part of the markup from literals, or quote the attribute value; wrap markup you built yourself in Trusted(...)."
                }
            }
        }

    private fun templateMessage(
        shown: String,
        landing: HoleLanding,
        endTag: Boolean,
    ): String {
        val base = "Kotlin fills in $shown before interpolate sees the markup, so interpolate neither escapes nor refuses it."
        if (landing.position in ESCAPED_POSITIONS) return "$base Make it a %s hole and pass the value to interpolate."
        val (where, instead) = landingWords(landing, endTag)
        return "$base It lands in $where. $instead"
    }

    private fun refusedMessage(
        shown: String,
        landing: HoleLanding,
        certain: Boolean,
        endTag: Boolean,
    ): String {
        val allowed =
            when (landing.position) {
                Position.DATASTAR -> "a number or a boolean"
                Position.STYLE -> "a number"
                else -> "Trusted(...)"
            }
        val (where, instead) = landingWords(landing, endTag)
        return if (certain) {
            "interpolate always throws here: $shown lands in $where, and only $allowed is let in there. $instead"
        } else {
            "interpolate throws here unless $shown is $allowed: it lands in $where. $instead"
        }
    }

    private fun escapedMarkupMessage(shown: String): String =
        "interpolate escapes $shown here, so the tags it holds are printed as text: it is markup held as a String. " +
            "Pass the Html itself, join a list of Html with joinToHtml(), or wrap markup you built yourself in Trusted(...)."

    /**
     * The whole literal rewritten as a `$$` literal (Kotlin 2.2+): the flagged template becomes a
     * signal by staying as it is, every other template gains a dollar so it stays Kotlin, and the
     * `${'$'}` idiom becomes the plain dollar it always meant. [dollars] is how many the literal
     * needs; the text is made only when it is read.
     */
    private fun multiDollarFix(
        s: KotlinString,
        src: String,
        signal: Interpolation,
        dollars: Int,
    ): Fix {
        val prefix = "$".repeat(dollars)
        return Fix.later("Make it a $prefix literal, where \$${signal.text} is a signal", s.start, s.end) {
            // A kept template gains the dollars it now needs; the dollars before it stay text, as they were.
            // The flagged one keeps its run, which is now text.
            val keep = s.interpolations.filter { it !== signal }.mapTo(HashSet()) { it.start }
            val out = StringBuilder(prefix).append(src.substring(s.start, s.contentStart).trimStart('$'))
            var i = s.contentStart
            while (i < s.contentEnd) {
                if (i in keep) out.append("$".repeat(dollars - 1))
                if (src.startsWith("\${'$'}", i)) {
                    out.append('$')
                    i += 6
                    continue
                }
                out.append(src[i])
                i++
            }
            out.append(src, s.contentEnd, s.end).toString()
        }
    }

    /** What every trap of a literal shares: the dollars a `$$` rewrite needs, the helper that says the whole literal, and whether it is one literal. */
    private class TrapShared(
        val dollars: Int,
        val helpers: List<Fix>,
        val whole: Boolean,
    )

    /**
     * The trap of each `$name` in a literal, `kotlin-interpolation`, made when a hole asks for it:
     * what is shared by every hole of the literal is read once.
     */
    private fun traps(
        s: KotlinString,
        src: String,
    ): (Interpolation) -> Issue? {
        // In a multi-dollar literal a single $ is text, so there is no trap: every template there is deliberate.
        if (s.dollars > 1) return { null }
        val shared by lazy(LazyThreadSafetyMode.NONE) {
            // Literals joined with `+` are one string to the judgement, but a prefix makes only the first a `$$` literal.
            val whole = readKotlinStringAt(src, s.start)?.end == s.end
            val content = src.substring(s.contentStart, s.contentEnd)
            // Enough dollars that no run already in the text opens a template: one more than the longest run.
            val longestRun = DOLLAR_RUNS.findAll(content).maxOfOrNull { it.value.length } ?: 0
            val helpers = ArrayList<Fix>()
            // The literal as the author meant it, with every template read as a signal.
            if (whole && !BRACED_TEMPLATE.containsMatchIn(content)) {
                // A helper is not a string: it replaces the literal together with the trim call after it.
                val end = s.end + (TRIM_AFTER.matchAt(src, s.end)?.value?.length ?: 0)
                for ((re, helper) in HELPERS) {
                    val m = re.find(content)
                    if (m != null) helpers.add(0, Fix("Use ${helper(m.groupValues[1])}", s.start, end, helper(m.groupValues[1])))
                }
                val toggle = TOGGLE.find(content)
                if (toggle != null) {
                    val n = toggle.groupValues[1]
                    helpers.add(0, Fix("Use toggle(\"$n\")", s.start, end, "toggle(\"$n\")"))
                }
            }
            TrapShared(maxOf(2, longestRun + 1), helpers, whole)
        }
        return trap@{ ip ->
            if (ip.kind != InterpolationKind.SIMPLE) return@trap null
            val fixes = ArrayList<Fix>(shared.helpers)
            if (shared.whole) fixes += multiDollarFix(s, src, ip, shared.dollars)
            fixes += Fix("Escape as \${'$'}${ip.text}", ip.start, ip.start + 1, "\${'$'}")
            if (!s.raw) fixes += Fix("Escape as \\$${ip.text}", ip.start, ip.start, "\\")
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
        val trapOf = traps(s, src)
        val landings =
            holeLandings(
                view,
                s.interpolations.map { it.decodedStart until it.decodedStart + PLACEHOLDER.length },
                // Both bundles' prefixes, as interpolate reads them: a value is judged by what either would run.
                listOf(opts.prefix, CatalogNames.PREFIX, CatalogNames.ALIASED_PREFIX).distinct(),
            )
        val told = s.interpolations.map { holeFact(s, src, opts, it) }
        // interpolate(...) can take the literal only when every hole is a value it escapes and keeps.
        val fixable =
            s.dollars == 1 &&
                !trimmed(src, s) &&
                s.interpolations.none { it.kind == InterpolationKind.OPERAND } &&
                s.interpolations.indices.all { k -> interpolateKeeps(src, s, s.interpolations[k], told[k], landings[k].position, opts) }
        // The rewrite is made once for the literal, and only when a fix is applied.
        val rewrite = interpolateText(src, s)
        val issues = ArrayList<Issue>()
        for ((k, ip) in s.interpolations.withIndex()) {
            val landing = landings[k]
            val fact = plainFact(told[k])
            val shown = holeDisplay(ip, s.dollars)
            // Markup is the author's own in element text; in an attribute or code it is characters that can break out.
            val own = fact == HoleFact.CONSTANT || (fact == HoleFact.MARKUP && landing.position == Position.TEXT)
            // Digits, true, false or null: safe wherever escaping is enough.
            val scalar = fact == HoleFact.NUMBER || fact == HoleFact.BOOLEAN || fact == HoleFact.NULLABLE
            // A String, or markup outside element text, is characters that can end what holds them.
            val severity = if (fact == HoleFact.STRING || fact == HoleFact.MARKUP) Severity.WARNING else Severity.HINT

            fun rewritten(
                title: String,
                preferred: Boolean = true,
            ): List<Fix> = if (fixable) listOf(interpolateFix(src, s, rewrite, title, preferred)) else emptyList()

            fun issue(
                severity: Severity,
                code: String,
                message: String,
                fixes: List<Fix> = emptyList(),
            ): Issue = Issue(ip.start, ip.end, message, severity, code, Docs.EXPRESSIONS, fixes)

            fun unescaped(): Issue {
                // A String in element text may be markup the author built, which only the author knows: then
                // both the rewrite, which escapes it, and Trusted, which keeps it, are offered, and neither first.
                val text = landing.position == Position.TEXT
                val trust =
                    text && told[k] != HoleFact.NULLABLE_STRING &&
                        (fact == HoleFact.STRING || (fact == null && mayHoldMarkup(src, ip.text, s.start, opts.constants))) &&
                        !mayBeNull(src, ip.text, s.start)
                val fixes = rewritten(ESCAPES_TITLE, !trust) + if (trust) listOf(trustedFix(src, s, ip, shown)) else emptyList()
                return issue(severity, "unescaped-interpolation", unescapedMessage(shown, fact == HoleFact.MARKUP, text), fixes)
            }
            when (landing.position) {
                Position.TEXT, Position.ATTRIBUTE -> {
                    if (!own && !scalar) issues += unescaped()
                }

                Position.URL -> {
                    if (own || scalar) continue
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
                                rewritten(REFUSES_URL_TITLE),
                            )
                        }
                }

                Position.STYLE -> {
                    if (own || fact == HoleFact.NUMBER) continue
                    val css =
                        "Kotlin writes $shown into ${landing.attribute ?: "style"}, where it is read as CSS, " +
                            "so escaping does not help."
                    issues +=
                        issue(
                            Severity.WARNING,
                            "unsafe-interpolation",
                            if (fact == HoleFact.NULLABLE) {
                                "$css It is a number that may be null, which Kotlin writes as null: give it a value with ?:, or choose between classes."
                            } else {
                                "$css Choose between classes, or write a number."
                            },
                        )
                }

                Position.DATASTAR -> {
                    val trap = if (ip.kind == InterpolationKind.SIMPLE) trapOf(ip) else null
                    val judged = trap?.let { datastarTrap(it, ip, landing, view, opts.prefix, fact) }
                    if (judged != null) {
                        issues += judged
                    } else if (!own && fact != HoleFact.NUMBER && fact != HoleFact.BOOLEAN) {
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
                    if (own) continue
                    // `</h$n>` names the element `<h$n` opened; the opening one is judged.
                    val closing = closingTagBefore(view, ip.decodedStart)
                    if (closing != null &&
                        s.interpolations.take(k).any {
                            it.text == ip.text && it.decodedStart > closing.length &&
                                view.startsWith("<$closing", it.decodedStart - closing.length - 1)
                        }
                    ) {
                        continue
                    }
                    issues +=
                        when {
                            endTagAt(view, ip.decodedStart) -> {
                                issue(
                                    severity,
                                    "unsafe-interpolation",
                                    "Kotlin writes $shown into an end tag, which takes no attributes: the browser drops them, " +
                                        "and a value from outside can still end the tag and write markup. Write the end tag from literals alone.",
                                )
                            }

                            unquotedValueBefore(view, ip.decodedStart) -> {
                                issue(
                                    if (scalar) Severity.HINT else Severity.WARNING,
                                    "unsafe-interpolation",
                                    "Kotlin writes $shown into an attribute value without quotes, where a space starts a new attribute. Quote the value.",
                                    listOfNotNull(quoteFix(s, view, ip)),
                                )
                            }

                            else -> {
                                issue(
                                    severity,
                                    "unsafe-interpolation",
                                    "Kotlin writes $shown into the markup's own structure: a tag name, an attribute name " +
                                        "or a list of attributes. Fine for one chosen in code; a value from outside decides " +
                                        "the markup itself, and escaping cannot prevent that.",
                                )
                            }
                        }
                }
            }
        }
        return issues
    }

    /** Whether [at] in the markup is inside an attribute value written without quotes: `x=$q`. */
    private fun unquotedValueBefore(
        view: String,
        at: Int,
    ): Boolean {
        var k = at
        while (k > 0 && view[k - 1] !in UNQUOTED_STOP) k--
        while (k > 0 && view[k - 1] in " \t\n\u000C\r") k--
        return k > 0 && view[k - 1] == '='
    }

    /** The name an end tag has so far right before [at], `h` of `</h$n>`, or null when [at] does not follow one. */
    private fun closingTagBefore(
        view: String,
        at: Int,
    ): String? {
        var k = at
        while (k > 0 && (view[k - 1].let { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it in ":_-" })) k--
        return if (k >= 2 && view.startsWith("</", k - 2)) view.substring(k, at) else null
    }

    /**
     * Whether `interpolate(...)` writes the value as the template does, escaped at most, so the
     * rewrite keeps the page: anything it admits where it lands, but not a value that may be null
     * (a template writes `null`, interpolate nothing), a `Trusted` outside element text (it is not
     * escaped there, so the rewrite would fix nothing), nor markup held as a String in element text,
     * which it would escape.
     */
    private fun interpolateKeeps(
        src: String,
        s: KotlinString,
        ip: Interpolation,
        told: HoleFact?,
        position: Position,
        opts: AnalyzeOptions,
    ): Boolean {
        val fact = plainFact(told)
        if (position == Position.DATASTAR) return fact == HoleFact.NUMBER || fact == HoleFact.BOOLEAN
        if (position == Position.STYLE) return fact == HoleFact.NUMBER
        if (position !in ESCAPED_POSITIONS) return false
        if (fact == HoleFact.NULLABLE || told == HoleFact.NULLABLE_STRING || mayBeNull(src, ip.text, s.start)) return false
        if (TRUSTED_CALL.containsMatchIn(ip.text)) return position == Position.TEXT
        if (fact == HoleFact.MARKUP) return position != Position.TEXT || markupValued(src, s, ip)
        if (position == Position.TEXT && fact != HoleFact.NUMBER && fact != HoleFact.BOOLEAN &&
            mayHoldMarkup(src, ip.text, s.start, opts.constants)
        ) {
            return false
        }
        return !(fact == HoleFact.CONSTANT && position == Position.TEXT && ip.kind != InterpolationKind.SIMPLE && '<' in ip.text)
    }

    /**
     * What the analysis knows of a hole's value: the editor's fact when it gives one, which no rule
     * of the text overrides; otherwise what the text shows, of the hole or of each value an `if`, a
     * `when` or an `?:` in it can give.
     */
    private fun holeFact(
        s: KotlinString,
        src: String,
        opts: AnalyzeOptions,
        ip: Interpolation,
    ): HoleFact? {
        opts.holeFact?.invoke(ip.start, ip.end)?.let { return it }
        val values = if (ip.kind == InterpolationKind.SIMPLE) listOf(HoleValue(ip.text, false)) else holeValues(ip.text)
        val kinds = values.map { valueKind(src, it.text, s.start, opts.constants, it.nonNull) }
        if (kinds.all { it == HoleFact.CONSTANT }) return HoleFact.CONSTANT
        if (isMarkup(src, s, ip)) return HoleFact.MARKUP
        return weakest(kinds)
    }

    private fun unescapedMessage(
        shown: String,
        markup: Boolean,
        text: Boolean,
    ): String {
        // Markup an attribute holds is only characters, whose quotes end the value: that it is markup is the problem.
        if (markup) {
            return "Kotlin writes $shown into the HTML as it is, and it is markup: inside an attribute its quotes and tags " +
                "are only characters, and a quote ends the value. Write text there rather than markup."
        }
        val base =
            "Kotlin writes $shown into the HTML as it is. If it can hold text from outside, " +
                "build the string with interpolate(...), which escapes it"
        return if (text) "$base; if it is markup you built, declare it Html or wrap it in Trusted(...)." else "$base."
    }

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
        fact: HoleFact?,
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
        // A number or a boolean is safe written in: what is left to ask is whether a signal was meant.
        if (fact == HoleFact.NUMBER || fact == HoleFact.BOOLEAN) {
            return trap.copy(
                severity = Severity.HINT,
                fixes = fixes,
                message =
                    "Kotlin writes \$$name here, a ${fact.name.lowercase()} from the server, which is safe in a Datastar expression. " +
                        "Meant as the signal \$$name, make the literal \$\$\"\"\"...\"\"\" (Kotlin 2.2+), or escape as \${'$'}$name.",
            )
        }
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

    /** The expression a DSL call is handed: a literal, or a `+` chain that starts with one, `"@get('/x/" + id + "')"`. */
    private fun expressionString(
        site: CallSite,
        spec: CallSiteSpec,
        src: String,
    ): KotlinString? = site.selectString(spec) ?: chainArg(site, spec, src)

    private fun checkExpressionSite(
        site: CallSite,
        spec: CallSiteSpec,
        src: String,
        hyphenated: Set<String>,
        opts: AnalyzeOptions,
    ): List<Issue> {
        if (spec.onlyIfStringArgs && site.args.any { it.named == null && it.string == null }) return emptyList()
        val s = expressionString(site, spec, src) ?: return emptyList()
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
     * The templates of a literal Datastar runs as an expression, and the values joined to it with
     * `+`. `$name` where a signal could stand is the trap. Any other template, `${expr}`, one inside
     * a JavaScript string (`@post('/x/$id')`, where Datastar leaves a `$` alone, engine.ts:448 in
     * 1.0.4), or one in a `$$` literal, writes the Kotlin value into code Datastar runs: a `'` in text
     * from outside ends the string and the rest runs. A number or a boolean is safe there, as
     * `interpolate` lets in.
     */
    private fun expressionHoles(
        s: KotlinString,
        src: String,
        opts: AnalyzeOptions,
        attribute: String,
    ): List<Issue> {
        val trapOf = traps(s, src)
        val out = ArrayList<Issue>()
        for (ip in s.interpolations) {
            val trap = trapOf(ip)
            if (trap != null && quoteAt(s.text, ip.decodedStart) == null) {
                out += trap
                continue
            }
            val fact = plainFact(holeFact(s, src, opts, ip))
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
