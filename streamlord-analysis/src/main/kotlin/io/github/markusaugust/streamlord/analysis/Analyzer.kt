package io.github.markusaugust.streamlord.analysis

import io.github.markusaugust.streamlord.core.catalog.CatalogNames
import io.github.markusaugust.streamlord.core.client.ClientCasing
import io.github.markusaugust.streamlord.core.client.asciiLowercase
import io.github.markusaugust.streamlord.core.client.decodeEntities
import io.github.markusaugust.streamlord.core.client.indexOfAsciiIgnoreCase
import io.github.markusaugust.streamlord.core.client.regex
import io.github.markusaugust.streamlord.core.domain.AttributeKeys
import io.github.markusaugust.streamlord.core.domain.CspNonce
import io.github.markusaugust.streamlord.core.domain.HoleLanding
import io.github.markusaugust.streamlord.core.domain.Position
import io.github.markusaugust.streamlord.core.domain.holeLandings
import io.github.markusaugust.streamlord.core.domain.urlHostFixedBy
import io.github.markusaugust.streamlord.core.domain.urlSchemeFixedBy
import java.io.File
import kotlin.concurrent.Volatile

/*
 * Editor-independent analysis: Kotlin or HTML source in, issues with source offsets out.
 */

public data class AnalyzeOptions(
    val prefix: String = CatalogNames.PREFIX,
    val checkHtmlAttributes: Boolean = true,
    /** The `const val` names the workspace declares: a value the author wrote, silent wherever it lands. */
    val constants: Set<String> = emptySet(),
    /** What the editor knows of a Kotlin value written into an HTML string, from types the text cannot show (IntelliJ). */
    val valueFact: ((start: Int, end: Int) -> ValueFact?)? = null,
    /** Whether streamlord-html is on the classpath: null when the host does not know, and the file's imports decide. */
    val htmlDsl: Boolean? = null,
    /** Whether streamlord-core is on the classpath; null counts as present. Without core there is no streamlord-html either. */
    val core: Boolean? = null,
    /** Where the name of each call starts ([CallSite.nameStart]) that the host knows is not Streamlord's. */
    val foreignCalls: Set<Int> = emptySet(),
    /** How the editor names its attribute prefix setting, where a message sends the reader to it. */
    val prefixSetting: String = PREFIX_SETTING,
    /** Whether the project's core has `Html`, which came in 0.16.0 and a message may send the author to; null counts as having it. */
    val coreHtml: Boolean? = null,
)

/** The setting as the IntelliJ plugin's settings page shows it. */
internal const val PREFIX_SETTING: String = "the Streamlord attribute prefix setting"

/** Which of Streamlord's modules the analysed file can use, as [AnalyzeOptions] says. */
internal class Sdk private constructor(
    val core: Boolean,
    /** As [AnalyzeOptions.htmlDsl]: null leaves it to the file's imports. */
    val htmlDsl: Boolean?,
) {
    companion object {
        val DEFAULT = Sdk(true, null)
        private val DSL = Sdk(true, true)
        private val CORE_ONLY = Sdk(true, false)
        private val NONE = Sdk(false, false)

        fun of(opts: AnalyzeOptions): Sdk =
            when {
                opts.core == false -> NONE
                opts.htmlDsl == true -> DSL
                opts.htmlDsl == false -> CORE_ONLY
                else -> DEFAULT
            }
    }
}

/**
 * What the editor knows of a value: its type, or that it is a constant or markup. [NULLABLE] is a
 * number or a boolean that may be null (`Int?`): digits, true, false or null. [NULLABLE_STRING] is
 * a `String?`, which a Kotlin template writes as `null` and `interpolate` as nothing. [UNSIGNED] is
 * a `UInt`, `ULong`, `UShort` or `UByte`: digits, but not a `kotlin.Number`, so `interpolate`
 * refuses it where only a number is let in. [TRUSTED] is core's `Trusted`, judged as [MARKUP] is,
 * which `interpolate` writes as it stands wherever it lands, so a rewrite escapes nothing of it.
 * [OTHER] is a class the editor resolved whose name the
 * text alone would take for Streamlord's markup, an `Html` or a `Trusted` of another package
 * ([ofResolvedClass]): nothing about it is known, and no reading of the text makes it markup. Only
 * IntelliJ gives it; VS Code has no resolver.
 */
public enum class ValueFact {
    STRING,
    NULLABLE_STRING,
    NUMBER,
    BOOLEAN,
    NULLABLE,
    CONSTANT,
    MARKUP,
    TRUSTED,
    UNSIGNED,
    OTHER,
    ;

    /** What [ofTypeName] in ValueTypes.kt hangs on. */
    public companion object
}

private val MODE_NAME = regex("""(?:^|\.)([A-Z]+)\s*$""")
private val CASE_ARG = regex("""^\s*Case\.[A-Z]+\s*$""")
private val CASE_VALUE = regex("""Case\.([A-Z]+)""")
private val CASE_IN_LAMBDA = regex("""\bcase\s*=\s*Case\.([A-Z]+)""")
private val OBJECT_FORM = regex("""^\s*[{\[]""")
private val HAS_CAPITAL = regex("""[A-Z]""")

// A property set in a lambda of modifiers, `{ once = true }`, and its value; `==` is a comparison and
// `val once = 1` a variable of the lambda's own.
private val LAMBDA_ASSIGNMENT = regex("""(?<!\b(?:val|var)\s)\b([A-Za-z]+)\s*=(?!=)\s*([^;\n}]*)""")
private val KOTLIN_STRING = regex(""""(?:[^"\\]|\\.)*"""")

// The parameter a DSL helper takes its key by, when it is passed by name.
private val KEY_PARAMETERS = listOf("name", "event", "key", "property", "attribute", "signal")
private val DOLLAR_RUNS = regex("""\$+""")
private val ASSIGN_BEFORE = regex("""\s*=\s*$""")
private val BRACED_TEMPLATE = regex("""\$\{""")

// A name, a property chain or a number literal, which takes a call after it without brackets.
private val SIMPLE_RECEIVER = regex("""[\p{L}_][\p{L}\p{Nd}_]*(?:\.[\p{L}_][\p{L}\p{Nd}_]*)*|\p{Nd}[\p{L}\p{Nd}_]*""")
private val END_TAG_ATTRIBUTES = regex("""</[^\t\n\u000C\r />]*[\t\n\u000C\r /][^>]*""")

/** The characters that end an unquoted attribute value, and the space before one: ASCII only, as HTML has it. */
private const val UNQUOTED_STOP = " \t\n\u000C\r\u000B\"'=<>`"

/** Where escaping is all a value needs, so that `interpolate(...)` takes it. */
private val ESCAPED_POSITIONS = setOf(Position.TEXT, Position.ATTRIBUTE, Position.URL)

/** What interpolate does with a URL whose value decides the scheme. */
private const val REFUSES_SCHEME = "interpolate(...), which refuses a scheme that runs"

private const val CODE_URL_ADVICE =
    "Start the value with a path such as /js/ or a whole origin such as https://cdn.example/, so that the value only picks a file there"

/** The folders [Analyzer.analyzeProject] leaves unread, as the VS Code index does: build output and dependencies. */
private val SKIPPED_FOLDERS = setOf("build", "node_modules", ".gradle", "dist", "out", "target")

/** Where a markup's holes land, read with the text of the known ones in place. */
private class HoleReading(
    val landings: List<HoleLanding>,
    /** What the markup reads from where the value of hole `j`'s attribute starts to where hole `to` starts, each known text in place. */
    val prefix: (j: Int, to: Int) -> String,
)

private val INTERPOLATE = setOf("interpolate")

/** A list of plain string literals, as `interpolate` takes its prefixes: `listOf("data-x-")`. */
private val PREFIX_LIST = regex("""(?:listOf|mutableListOf|arrayListOf)\s*\(\s*((?:"[^"\\$]*"\s*,\s*)*(?:"[^"\\$]*"\s*,?\s*)?)\)""")
private val PLAIN_LITERAL = regex(""""([^"\\$]*)"""")

/** What stands before the URL in the content of a refresh: a delay, a separator and `url=`, with a quote where one is written. */
private val REFRESH_BEFORE_URL = regex("""\s*\d+(?:\.\d*)?\s*[;,]\s*(?:[Uu][Rr][Ll]\s*=\s*)?['"]?""")
private val CONST_VAL_BEFORE = regex("""\bconst\s+val\s+[^=\n]*=\s*\z""")
private val ANNOTATION_BEFORE = regex("""@[\p{L}_][\p{L}\p{Nd}_.:]*\s*\z""")

private val FORMAT_SPECIFIER = regex("""%[-#+0,(]*\d*(?:\.\d+)?[dfxXoeEgGcb](?![A-Za-z])""")

/** kotlinx.html's own way to write any attribute: `attributes["data-on:click"] = "..."`. */
private val ATTRIBUTES_INDEX = regex("""\battributes\s*\[\s*"(data-[^"\\]*)"\s*]\s*=\s*""")

/** What may follow the value of an assignment: the end of the statement, nothing more of an expression. */
private val STATEMENT_END = regex("""[ \t]*(?:[;}\n\r]|$)""")

/** The DSL helper that says the same as a whole-string expression, when there is one, by its name. */
private val HELPERS: List<Pair<Regex, String>> =
    listOf(
        regex("""^\$([A-Za-z_][A-Za-z0-9_.]*)$""") to "signal",
        regex("""^\$([A-Za-z_][A-Za-z0-9_.]*)\+\+$""") to "increment",
        regex("""^\$([A-Za-z_][A-Za-z0-9_.]*)--$""") to "decrement",
        regex("""^!\$([A-Za-z_][A-Za-z0-9_.]*)$""") to "not",
        regex("""^\$([A-Za-z_][A-Za-z0-9_.]*) = !\$\1$""") to "toggle",
    )

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

    /**
     * The calls streamlord-html alone defines, which are somebody else's in a project without it:
     * the attributes' helpers, the expression call sites and the expression helpers.
     */
    public val dslNames: Set<String> =
        catalog.attributes.flatMap { it.kotlin }.toSet() + catalog.expressionCallSites.keys + catalog.signalNameHelpers.keys +
            "statements"

    /** Whether the call named [name] at [nameStart] is not Streamlord's, as [opts] says: then its strings are free strings. */
    private fun foreignCalls(opts: AnalyzeOptions): (name: String, nameStart: Int) -> Boolean {
        val sdk = Sdk.of(opts)
        val given = opts.foreignCalls
        // Every call the analysis looks for is core's or the DSL's, so without core none is Streamlord's.
        return { name, at -> !sdk.core || at in given || (sdk.htmlDsl == false && name in dslNames) }
    }

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

    /**
     * Every `.kt` file under [root] that VS Code indexes, analysed with the constants the files
     * declare between them ([projectConstants]) added to [opts]'s: the verdict VS Code gives the
     * same workspace. Below [root], folders named `build`, `node_modules`, `.gradle`, `dist`, `out`
     * or `target`, and hidden ones, are left out. The issues come in path order, then by offset;
     * [failures] keeps those that should fail a build.
     */
    public fun analyzeProject(
        root: File,
        opts: AnalyzeOptions = AnalyzeOptions(),
    ): List<ProjectIssue> = issuesUnder(root, opts, SKIPPED_FOLDERS)

    /** The issues of a Kotlin file grouped by the string or call they belong to, so a host can veto a call it knows is not Streamlord's. */
    public fun analyzeKotlinSites(
        src: String,
        opts: AnalyzeOptions = AnalyzeOptions(),
    ): List<SiteIssues> {
        val out = ArrayList<SiteIssues>()
        val claimed = HashSet<Int>()
        val lex = lex(src)
        val facts = fileFacts(src, lex)
        val foreign = foreignCalls(opts)
        val hyphenated = hyphenatedSignals(src, SourceLanguage.KOTLIN, foreign, lex)
        val dsl = Sdk.of(opts).htmlDsl
        // At a DSL call the DSL is there; an import from elsewhere may still take a helper's name.
        val siteHelpers by lazy(LazyThreadSafetyMode.NONE) { unshadowed(src, EXPRESSION_HELPERS) }
        for (site in findCallSites(src, allSiteNames, lex)) {
            // Somebody else's call: its strings are judged as any string handed to an unknown function.
            if (foreign(site.name, site.nameStart)) continue
            val issues = ArrayList<Issue>()
            catalog.expressionCallSites[site.name]?.let { issues += checkExpressionSite(site, it, src, hyphenated, opts, siteHelpers) }
            val html = catalog.htmlCallSites[site.name]?.let { checkHtmlSite(site, it, opts, src, hyphenated) }
            html?.let { issues += it.issues }
            catalog.scriptCallSites[site.name]?.let { issues += checkScriptSite(site, it) }
            catalog.selectorCallSites[site.name]?.let { issues += checkSelectorSite(site, it) }
            issues += checkKeyCaseSite(site, src)
            // The literals of a `+` chain handed to the site are judged there, as one string; the arguments it does not read are free.
            for (judged in judgedStrings(site, src)) {
                for (x in lex.stringsIn(judged.start, judged.end)) claimed += x.start
            }
            if (issues.isNotEmpty() || html?.rewrite != null) out += SiteIssues(site, null, issues, html?.rewrite)
        }
        // An interpolate the file takes from elsewhere is not core's, whose format this reads; nor one it
        // declares, since the text cannot tell whether the call fits it.
        val otherInterpolate = shadowsCore(src, "interpolate")
        for (site in findCallSites(src, INTERPOLATE, lex)) {
            if (foreign(site.name, site.nameStart) || otherInterpolate) continue
            val format = interpolateFormat(site, src) ?: continue
            for (x in lex.stringsIn(format.second.start, format.second.end)) claimed += x.start
            val issues = checkInterpolateSite(site, format.first, format.second, src, opts, hyphenated)
            if (issues.isNotEmpty()) out += SiteIssues(site, null, issues)
        }
        for (site in findCallSites(src, nonceCalls, lex)) {
            if (foreign(site.name, site.nameStart)) continue
            val issues = checkNonceSite(site)
            if (issues.isNotEmpty()) out += SiteIssues(site, null, issues)
        }
        val hyphenSites = signalNameHyphenSites(src, lex, dslHelpers(src, EXPRESSION_HELPERS, dsl), allSiteNames + "statements", foreign)
        for ((site, issues) in hyphenSites) {
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
        val topLevel = lex.strings.mapTo(HashSet()) { it.start }
        for (s in withNested(lex.strings)) {
            if (s.start in claimed || s.start in chained || s.unterminated) continue
            // A literal inside a template is joined to its neighbours in the template's code.
            val chain = chainFrom(src, s, if (s.start in topLevel) lex.mask else facts.code)
            val whole = chain ?: s
            if (chain != null) for (at in chain.start until chain.end) if (src[at] == '"' || src[at] == '$') chained += at
            if (!isHtmlString(src, s) && !(chain != null && textOpensWithTag(chain.text))) continue
            val judged = checkFreeHtmlString(whole, opts, src, hyphenated)
            if (judged.issues.isNotEmpty() || judged.rewrite != null) out += SiteIssues(null, whole, judged.issues, judged.rewrite)
        }
        return out
    }

    /**
     * The strings [site] judges: the markup, the expression, the script or the selector it is handed,
     * a `+` chain whole, and the key or the name of a helper that takes only that.
     */
    private fun judgedStrings(
        site: CallSite,
        src: String,
    ): List<KotlinString> {
        val out = ArrayList<KotlinString>(1)
        catalog.htmlCallSites[site.name]?.let { spec -> markupString(site, spec, src)?.let { out += it } }
        catalog.expressionCallSites[site.name]?.let { spec ->
            if (!(spec.onlyIfStringArgs && site.args.any { it.named == null && it.string == null })) {
                expressionString(site, spec, src)?.let { out += it }
            }
        }
        catalog.scriptCallSites[site.name]?.let { spec -> site.selectString(spec)?.let { out += it } }
        catalog.selectorCallSites[site.name]?.let { spec -> site.selectString(spec)?.let { out += it } }
        if (site.name in keyOnlyHelpers) {
            (KEY_PARAMETERS.firstNotNullOfOrNull { site.named(it) } ?: site.positional.firstOrNull())?.string?.let { out += it }
        }
        if (site.name in valueNameHelpers) {
            (site.named("signal") ?: site.named("name") ?: site.positional.firstOrNull())?.string?.let { out += it }
        }
        return out
    }

    /** The DSL helpers that write `data-nonce`: `dataNonce`. */
    private val nonceCalls: Set<String> = catalog.attributesByName["nonce"]?.kotlin?.toSet() ?: emptySet()

    /** `dataNonce("...")` of a literal it refuses as the page renders, through [CspNonce.checked]: empty, or no base64 value. */
    private fun checkNonceSite(site: CallSite): List<Issue> {
        val s = (site.named("nonce") ?: site.positional.firstOrNull())?.string ?: return emptyList()
        if (s.unterminated || s.interpolations.isNotEmpty()) return emptyList()
        val problem = CspNonce.problem(s.text) ?: return emptyList()
        val empty = s.text.isEmpty()
        return listOf(
            Issue(
                start = if (empty) s.start else s.contentStart,
                end = if (empty) s.end else s.contentEnd,
                message = problem.removeSuffix(".") + ". ${site.name} refuses it as the page renders.",
                severity = Severity.ERROR,
                code = if (empty) "missing-nonce" else "invalid-nonce",
                link = Docs.attribute("nonce"),
            ),
        )
    }

    /**
     * Each `attributes["data-..."] = "..."` in [src], kotlinx.html's own way to write an attribute, for completion and colouring:
     * the name, where it starts, and the literal that follows the `=`, unterminated while it is typed.
     */
    public fun attributesIndexes(src: String): List<AttributesIndex> {
        val lex = lex(src)
        val out = ArrayList<AttributesIndex>()
        for (m in ATTRIBUTES_INDEX.findAll(src)) {
            if (!lex.mask[m.range.first]) continue
            val value = lex.stringAt(m.range.last + 2)?.takeIf { it.start == m.range.last + 1 }
            val nameStart = m.range.first + m.value.indexOf('"') + 1
            out += AttributesIndex(m.groupValues[1], nameStart, value, attributesIndexValue(src, m, lex) != null)
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
        foreign: (String, Int) -> Boolean = { _, _ -> false },
        lex: KotlinLex? = null,
    ): Set<String> = collectSignalDefinitions(src, language, foreign, lex).filterTo(HashSet()) { '-' in it }

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
        if (value != null && kind == ValueKind.EXPRESSION) {
            // kotlinx.html's own form: a helper is offered only where the file can call it.
            issues += expressionHoles(value, src, opts, name, dslHelpers(src, EXPRESSION_HELPERS, Sdk.of(opts).htmlDsl))
        }
        if (value != null && kind == ValueKind.SIGNAL) issues += signalNameHoles(value, src, opts, name)
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
        for (i in markup.validateAttributes(tag, opts.prefix, null, hyphenated, kotlin = true, prefixSetting = opts.prefixSetting)) {
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
     * The templates in the value of an attribute that takes a signal's name, `data-bind`: `$count`
     * there is a Kotlin value, and the name is written without the dollar. Any other template
     * writes a Kotlin value into the signal path, as in markup.
     */
    private fun signalNameHoles(
        s: KotlinString,
        src: String,
        opts: AnalyzeOptions,
        attribute: String,
    ): List<Issue> {
        val out = ArrayList<Issue>()
        for (ip in s.interpolations) {
            // A constant or a number the author wrote is the name, as it is in markup.
            val fact = holeFact(s, src, opts, ip).plain()
            if (fact.writesItself()) continue
            if (ip.kind == InterpolationKind.SIMPLE && ip.dollars == 1) {
                // The literal without the dollar, as written: what the fix writes and the message shows.
                val written = src.substring(s.contentStart, ip.start) + src.substring(ip.start + 1, s.contentEnd)
                out +=
                    Issue(
                        start = ip.start,
                        end = ip.end,
                        message =
                            "Kotlin interpolates \$${ip.text} here, and $attribute takes a signal name, not an expression. Write $written.",
                        severity = Severity.ERROR,
                        code = "kotlin-interpolation",
                        link = Docs.EXPRESSIONS,
                        fixes = listOf(Fix("Write $written", s.contentStart, s.contentEnd, written)),
                    )
                continue
            }
            out += datastarIssue(ip, attribute)
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
        templates: Boolean = true,
    ): List<Issue> {
        val issues = ArrayList<Issue>()
        for (tag in tokenize(src, templates).tags) {
            if (tag.closing) continue
            issues +=
                markup.validateAttributes(
                    tag,
                    opts.prefix,
                    src.substring(tag.start, tag.end),
                    hyphenated,
                    prefixSetting = opts.prefixSetting,
                )
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
        val foreign = foreignCalls(opts)

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
            if (foreign(site.name, site.nameStart)) continue
            // The literals the analysis judges at the site are the site's; a `+` chain's are read as free strings, as ever.
            for (judged in judgedStrings(site, src)) {
                if (site.args.any { it.string?.start == judged.start && it.string.end == judged.end }) claimed += judged.start
            }
            catalog.expressionCallSites[site.name]?.let { spec ->
                if (spec.onlyIfStringArgs && site.args.any { it.named == null && it.string == null }) return@let
                val s = site.selectString(spec)?.takeUnless { it.unterminated } ?: return@let
                add(s, expressionSignalReferences(s.text), site)
            }
            catalog.htmlCallSites[site.name]?.let { spec ->
                val s = markupString(site, spec)?.takeUnless { it.unterminated } ?: return@let
                add(s, markupSignalReferences(s.text, catalog, opts.prefix, templates = false), site)
            }
        }
        val helpers =
            dslHelpers(src, HELPER_READ_CALLS, Sdk.of(opts).htmlDsl).takeIf { it.isNotEmpty() }?.let { findCallSites(src, it, lex) }
                ?: emptyList()
        for (site in helpers) {
            // A call on a receiver, `filter.not("x")`, is not the top-level helper.
            if (src.lastNonSpaceBefore(site.nameStart) == '.' || foreign(site.name, site.nameStart)) continue
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
            add(s, markupSignalReferences(s.text, catalog, opts.prefix, templates = false), null)
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
    ): Judged {
        val view = markupView(src, s)
        val judged = judgeHoles(s, src, opts, view)
        if (!opts.checkHtmlAttributes) return judged
        return Judged(judged.issues + mapIssues(s, src, analyzeHtml(view, opts, hyphenated, templates = false)), judged.rewrite)
    }

    /**
     * The prefixes an `interpolate(prefixes, format, ...)` call is given, which it alone reads, where
     * they are written as a list of plain literals; null where none are given or the text cannot tell them.
     */
    private fun literalPrefixes(
        site: CallSite,
        formatArg: Arg,
    ): List<String>? {
        val arg = site.named("prefixes") ?: site.positional.firstOrNull()?.takeIf { it.start < formatArg.start } ?: return null
        val list = PREFIX_LIST.matchEntire(arg.text.trim()) ?: return null
        return PLAIN_LITERAL.findAll(list.groupValues[1]).map { it.groupValues[1] }.toList()
    }

    /** The format handed to `interpolate`: its first argument or `format =`, a literal or a `+` chain that starts with one. */
    private fun interpolateFormat(
        site: CallSite,
        src: String,
    ): Pair<Arg, KotlinString>? {
        val lex = lex(src)
        // The prefixes of an aliased bundle may come first, as a list; the format is the first string.
        val leading = site.positional.firstOrNull()
        val prefixed = leading != null && leading.string == null && lex.strings.none { it.start == leading.start }
        val arg = site.named("format") ?: (if (prefixed) site.positional.getOrNull(1) else null) ?: leading ?: return null
        arg.string?.let { return if (it.unterminated) null else arg to it }
        val first = lex.strings.firstOrNull { it.start == arg.start } ?: return null
        // A trim call leaves each placeholder where it stood.
        if (!first.unterminated && trimAfter(src, first)?.text?.let { first.end + it.length } == arg.end) return arg to first
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
        // Each value as the code writes it, without a comment after it, which the editor's range leaves out too.
        // What stands before the format is the prefixes of an aliased bundle, and no value.
        val values = site.args.filter { it.start > formatArg.start && it.named == null }.map { uncommented(src, it) }
        // The values one by one, as the vararg overload takes them: no spread and nothing by name.
        val plain = site.args.all { it.named == null } && values.none { it.text.startsWith("*") }
        val known = values.map { opts.valueFact?.invoke(it.start, it.end) }
        val kinds =
            values.mapIndexed { k, it ->
                if (known[k].isOther()) null else known[k].argKind() ?: valueKind(src, it.text, it.string != null, s.start)
            }
        val issues = ArrayList<Issue>()
        // The values are always the values: a List among them is one, written as a template writes it.
        if (plain && holes.size != values.size) {
            val h = holes.size
            val v = values.size
            // `%d` as String.format writes it: interpolate knows only %s.
            val specifier = FORMAT_SPECIFIER.find(s.text.replace("%%", "  "))?.value
            val advice =
                if (specifier != null) {
                    "Only %s is a placeholder, whatever the value: write %s in place of $specifier."
                } else {
                    "Write %% for a per cent sign that is not a placeholder."
                }
            issues +=
                Issue(
                    site.nameStart,
                    site.nameStart + site.name.length,
                    "interpolate throws IllegalArgumentException here: the markup has " +
                        "$h %s ${if (h == 1) "placeholder" else "placeholders"} and " +
                        "$v ${if (v == 1) "value is" else "values are"} given. $advice",
                    Severity.ERROR,
                    "interpolate-placeholders",
                )
        }
        val mapped = plain && holes.size == values.size
        val view = markupView(src, format)
        val starts = format.interpolations.map { it.decodedStart } + at
        // interpolate reads the markup with each Trusted value's text in place: known where the value is a literal.
        val written = HashMap<Int, String>()
        val unknown = ArrayList<Int>()
        if (mapped) {
            for ((k, a) in values.withIndex()) {
                if (kinds[k] != ArgKind.TRUSTED) continue
                val text = trustedLiteral(src, a.text, s.start)
                if (text == null) unknown += at[k] else written[format.interpolations.size + k] = text
            }
        }
        val reading =
            readHoles(
                view,
                starts.map { it until it + PLACEHOLDER.length },
                literalPrefixes(site, formatArg) ?: listOf(opts.prefix, CatalogNames.PREFIX, CatalogNames.ALIASED_PREFIX).distinct(),
                written,
            )
        val landings = reading.landings
        // After a Trusted whose text is not known, a value may land elsewhere than the markup shows.
        val uncertainFrom = unknown.minOrNull() ?: Int.MAX_VALUE
        val percentAt = { j: Int -> j >= format.interpolations.size }
        val trapOf = traps(format, src)
        for ((k, ip) in format.interpolations.withIndex()) {
            val landing = landings[k]
            val told = holeFact(format, src, opts, ip)
            val fact = told.plain()
            if (ip.kind == InterpolationKind.SIMPLE && landing.position == Position.DATASTAR) {
                val judged = trapOf(ip)?.let { datastarTrap(it, ip, landing, view, opts.prefix, fact) }
                if (judged != null) {
                    issues += judged
                    continue
                }
            }
            // Kotlin fills in the digits of an unsigned number before interpolate sees the markup, so it is a number here.
            if (fact.writesItself()) continue
            if (fact.isNullable() && landing.position in ESCAPED_POSITIONS) continue
            // Markup in element text is safe as markup, but interpolate reads it as format: a %s in it is a hole.
            val markupText = fact.isMarkup() && landing.position == Position.TEXT
            val shown = holeDisplay(ip)
            // A `%` right before the template would make `%%s` of the hole: a per cent sign and an s.
            val percents = runBefore(format.text, ip.decodedStart, '%')
            // A %s hole writes nothing for null and escapes markup held as a String, so the fix would change the page;
            // an Html or a Trusted it writes as it is.
            val keeps =
                !told.isNullableString() &&
                    !mayBeNull(src, ip.text, s.start) &&
                    if (markupText) {
                        markupValued(src, format, ip)
                    } else {
                        !(landing.position == Position.TEXT && mayHoldMarkup(src, ip.text, s.start, opts.constants))
                    }
            // As a %s hole it would be refused, along with every hole after it in the URL, unless the literal before it fixes the host.
            val loadsAnywhere = hostUnfixed(reading, starts, k) { j -> j == k || percentAt(j) }
            val fixable =
                landing.position in ESCAPED_POSITIONS && !loadsAnywhere && ip.kind != InterpolationKind.OPERAND && mapped &&
                    percents % 2 == 0 && keeps
            val fixes = if (fixable) listOf(holeFix(ip, shown, at, values, formatArg, markupText)) else emptyList()
            val message =
                when {
                    loadsAnywhere -> templateCodeUrlMessage(shown, landing.attribute ?: "the URL")
                    markupText -> rereadMessage(shown)
                    else -> templateMessage(shown, landing, endTagAt(view, ip.decodedStart))
                }
            issues += Issue(ip.start, ip.end, message, Severity.WARNING, "interpolate-template", Docs.EXPRESSIONS, fixes)
        }
        for ((k, d) in at.withIndex()) {
            val landing = landings[format.interpolations.size + k]
            val arg = if (mapped) values[k] else null
            val kind = if (arg != null) kinds[k] else null
            val r = format.toSource(d, d + PLACEHOLDER.length)
            val base = format.interpolations.size
            val uncertain = d > uncertainFrom
            val shown = arg?.let { shortened(it.text) } ?: "the value of this %s"

            // An error only where interpolate throws for certain.
            fun refused(
                message: String,
                certain: Boolean,
                fixes: List<Fix> = emptyList(),
            ): Issue {
                val severity = if (certain) Severity.ERROR else Severity.WARNING
                return Issue(r.first, r.last + 1, message, severity, "unsafe-interpolation", Docs.EXPRESSIONS, fixes)
            }
            // interpolate refuses everything but Trusted in a URL that loads code, unless what comes before the
            // URL's first value nobody vouched for fixes its host: the markup as written, a literal Trusted's text
            // in place. It names that value, and a URL of Trusted values alone goes unjudged.
            val attribute = landing.attribute
            if (landing.loadsCode && attribute != null) {
                // A Trusted of known text is read as markup and is not among them.
                val inUrl = at.indices.filter { landings[base + it].valueStart == landing.valueStart }
                val first = inUrl.firstOrNull { (if (mapped) kinds[it] else null) != ArgKind.TRUSTED }
                // A Trusted before it whose text is not known ends what is known to come before.
                val vouched = if (first != null && inUrl[0] != first) inUrl[0] else null
                val before = first?.let { reading.prefix(base + k, base + (vouched ?: it)) }
                if (first != null && before != null && !urlHostFixedBy(attribute, before)) {
                    if (first != k) continue
                    // A template before the hole is filled in first, so what the literal fixes is known only without one.
                    val templated = format.interpolations.any { it.decodedStart >= landing.valueStart && it.decodedStart < d }
                    val certain = !templated && vouched == null && !uncertain && kind != null
                    issues += refused(refusedCodeUrlMessage(shown, attribute, certain, vouched != null || uncertain), certain)
                    continue
                }
            }
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
            val endTag = endTagAt(view, d)
            val numbersOnly = landing.position == Position.DATASTAR || landing.position == Position.STYLE
            issues +=
                when {
                    kind == ArgKind.UNSIGNED && arg != null && numbersOnly -> {
                        val message = if (uncertain) uncertainMessage(shown, landing, false) else unsignedRefusedMessage(shown, landing)
                        refused(message, !uncertain, listOf(toLongFix(arg)))
                    }

                    uncertain && kind != null -> {
                        refused(uncertainMessage(shown, landing, endTag), false)
                    }

                    else -> {
                        refused(refusedMessage(shown, landing, kind != null, endTag), kind != null && !uncertain)
                    }
                }
        }
        if (opts.checkHtmlAttributes) issues += mapIssues(format, src, analyzeHtml(view, opts, hyphenated, templates = false))
        return issues
    }

    /** The value converted to a Long, which is a `kotlin.Number`: `u.toLong()`, `(a + b).toLong()`. */
    private fun toLongFix(arg: Arg): Fix {
        val t = arg.text.trim()
        val receiver = if (SIMPLE_RECEIVER.matchEntire(t) != null) t else "($t)"
        return Fix("Convert ${shortened(t)} with toLong()", arg.start, arg.end, "$receiver.toLong()")
    }

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
        return lt >= 0 && END_TAG_ATTRIBUTES.matchEntire(view.substring(lt, at)) != null
    }

    /** The template made a `%s` hole in place, and its expression passed as the value at the same place among the others. */
    private fun holeFix(
        ip: Interpolation,
        shown: String,
        holes: List<Int>,
        values: List<Arg>,
        format: Arg,
        markup: Boolean,
    ): Fix {
        val index = countBelow(holes, ip.decodedStart)
        val expr = if (ip.kind == InterpolationKind.SIMPLE) ip.text else ip.text.trim()
        val before = values.getOrNull(index)
        val after = values.lastOrNull() ?: format
        val edit = if (before != null) Edit(before.start, before.start, "$expr, ") else Edit(after.end, after.end, ", $expr")
        val title = "Make $shown a %s placeholder, " + if (markup) "where interpolate writes it as it is" else "which interpolate escapes"
        return Fix(title, ip.start, ip.end, "%s", listOf(edit))
    }

    /** Where a hole that is not escaped lands, said for a message, and what to write instead. */
    private fun landingWords(
        landing: HoleLanding,
        endTag: Boolean,
    ): Pair<String, String> =
        when (landing.position) {
            Position.DATASTAR -> {
                "${landing.attribute ?: "a Datastar attribute"}, which Datastar runs as an expression or follows as a signal path" to
                    "Put the value in an attribute of its own, such as data-label, and read it in the expression with el.dataset.label."
            }

            Position.STYLE -> {
                "${landing.attribute ?: "style"}, which is read as CSS" to
                    "Pass a number and write the unit in the markup, or choose between classes."
            }

            Position.CODE -> {
                "${landing.attribute ?: "a script or style element"}, which the browser runs" to
                    "Put the value in a data-* attribute and read it from there; wrap markup you built yourself in Trusted(...)."
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

    /** Markup a Kotlin template writes into the format, which interpolate then reads as format. */
    private fun rereadMessage(shown: String): String =
        "Kotlin fills in $shown before interpolate reads the markup, so interpolate reads what it holds as markup too: " +
            "a %s in it is a placeholder and a %% a per cent sign, and interpolate throws or changes the markup. " +
            "Make it a %s placeholder and pass the Html: interpolate writes an Html or a Trusted there as it is."

    private fun templateMessage(
        shown: String,
        landing: HoleLanding,
        endTag: Boolean,
    ): String {
        val base = "Kotlin fills in $shown before interpolate sees the markup, so interpolate neither escapes nor refuses it."
        if (landing.position in ESCAPED_POSITIONS) return "$base Make it a %s placeholder and pass the value to interpolate."
        val (where, instead) = landingWords(landing, endTag)
        return "$base It lands in $where. $instead"
    }

    /** What interpolate lets in where escaping is not enough. */
    private fun letIn(position: Position): String =
        when (position) {
            Position.DATASTAR -> "a number or a boolean"
            Position.STYLE -> "a number"
            else -> "Trusted(...)"
        }

    private fun unsignedRefusedMessage(
        shown: String,
        landing: HoleLanding,
    ): String {
        val (where, _) = landingWords(landing, false)
        return "interpolate always throws here: $shown is an unsigned number, which is not a kotlin.Number, and it lands in $where, " +
            "where only ${letIn(landing.position)} is let in. Convert it with toLong() first."
    }

    /** An unsigned number a Kotlin template writes where interpolate would refuse it: its digits are safe as they are. */
    private fun unsignedWrittenMessage(
        shown: String,
        where: String,
    ): String =
        "Kotlin writes $shown into $where as digits, which is safe there, but it is an unsigned number, which interpolate refuses there: " +
            "it is not a kotlin.Number. Convert it with toLong() if you build this with interpolate."

    private fun refusedMessage(
        shown: String,
        landing: HoleLanding,
        certain: Boolean,
        endTag: Boolean,
    ): String {
        val allowed = letIn(landing.position)
        val (where, instead) = landingWords(landing, endTag)
        return if (certain) {
            "interpolate always throws here: $shown lands in $where, and only $allowed is let in there. $instead"
        } else {
            "interpolate throws here unless $shown is $allowed: it lands in $where. $instead"
        }
    }

    /** A value interpolate refuses where the markup shows it landing, which a Trusted before it whose text is not known may move. */
    private fun uncertainMessage(
        shown: String,
        landing: HoleLanding,
        endTag: Boolean,
    ): String {
        val allowed = letIn(landing.position)
        val (where, instead) = landingWords(landing, endTag)
        return "interpolate throws here unless the Trusted text before $shown moves it: it lands in $where, " +
            "where only $allowed is let in. $instead"
    }

    /**
     * Whether the hole at [k] is in a URL that loads code, `src` of a script and the like, whose host
     * the literal before the URL's first hole does not fix: interpolate refuses every value there but
     * Trusted, as Landscape.checkUrls in core does. [counts] says which holes interpolate sees as holes.
     */
    private fun hostUnfixed(
        reading: HoleReading,
        starts: List<Int>,
        k: Int,
        counts: (Int) -> Boolean,
    ): Boolean {
        val landing = reading.landings[k]
        val attribute = landing.attribute
        if (!landing.loadsCode || attribute == null) return false
        var first = k
        for ((j, l) in reading.landings.withIndex()) {
            if (counts(j) && l.valueStart == landing.valueStart && starts[j] < starts[first]) first = j
        }
        return !urlHostFixedBy(attribute, reading.prefix(k, first))
    }

    /**
     * The holes of [view] as interpolate reads them: holeLandings with the text of the [known] holes,
     * a literal Trusted's, in place. A known text can hold the start of a value, where holeLandings
     * gives the offset of the hole, so a prefix is read from the markup as written.
     */
    private fun readHoles(
        view: String,
        holes: List<IntRange>,
        prefixes: List<String>,
        known: Map<Int, String>,
    ): HoleReading {
        val landings = holeLandings(view, holes, prefixes, known)
        if (known.isEmpty()) return HoleReading(landings) { j, to -> view.substring(landings[j].valueStart, holes[to].first) }
        val text = StringBuilder()
        var cut = 0
        val starts = IntArray(holes.size)
        for (i in holes.indices.sortedBy { holes[it].first }) {
            val hole = holes[i]
            text.append(view, cut, hole.first)
            starts[i] = text.length
            text.append(known[i] ?: view.substring(hole.first, hole.last + 1))
            cut = hole.last + 1
        }
        text.append(view, cut, view.length)
        val markup = text.toString()
        val open = holes.indices.filter { it !in known }
        val written =
            lazy(LazyThreadSafetyMode.NONE) {
                holeLandings(markup, open.map { starts[it] until starts[it] + holes[it].last + 1 - holes[it].first }, prefixes)
            }
        return HoleReading(landings) { j, to -> markup.substring(written.value[open.indexOf(j)].valueStart, starts[to]) }
    }

    private fun codeUrlMessage(
        shown: String,
        attribute: String,
    ): String =
        "Kotlin writes $shown into $attribute, which loads code from wherever the value points, and the markup before it does not fix " +
            "the host: even an https:// URL can load code from another origin. Escaping does not help. $CODE_URL_ADVICE."

    private fun templateCodeUrlMessage(
        shown: String,
        attribute: String,
    ): String =
        "Kotlin fills in $shown before interpolate sees the markup, so interpolate neither escapes nor refuses it. " +
            "It lands in $attribute, which loads code from wherever the value points, and the markup before it does not fix the host. " +
            "$CODE_URL_ADVICE, and make it a %s placeholder."

    private fun refusedCodeUrlMessage(
        shown: String,
        attribute: String,
        certain: Boolean,
        vouched: Boolean,
    ): String {
        val where = "$attribute, which loads code from wherever the value points"
        val instead = "$CODE_URL_ADVICE, or wrap a URL you chose yourself in Trusted(...)."
        return when {
            vouched -> {
                "interpolate throws here unless the Trusted text before $shown fixes the host or $shown is Trusted(...): " +
                    "it lands in $where. $instead"
            }

            certain -> {
                "interpolate always throws here: $shown lands in $where, and the markup before it does not fix the host. " +
                    "Only Trusted(...) is let in there. $instead"
            }

            else -> {
                "interpolate throws here unless $shown is Trusted(...): it lands in $where, " +
                    "and the markup before it does not fix the host. $instead"
            }
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

    /**
     * The trap of each `$name` in a literal, `kotlin-interpolation`, made when a hole asks for it:
     * what every hole of the literal shares is read once, when the first asks. [available] names the DSL helpers
     * a fix may write, each with its import; null where the literal is handed to a DSL call.
     */
    private fun traps(
        s: KotlinString,
        src: String,
        available: Set<String>? = null,
    ): (Interpolation) -> Issue? {
        // Literals joined with `+` are one string to the judgement, but a prefix makes only the first a `$$` literal.
        val whole = s.literals.isEmpty()
        val content by lazy(LazyThreadSafetyMode.NONE) { src.substring(s.contentStart, s.contentEnd) }
        // Enough dollars that no run already in the text opens a template: one more than the longest run.
        val dollars by lazy(LazyThreadSafetyMode.NONE) { maxOf(2, (DOLLAR_RUNS.findAll(content).maxOfOrNull { it.value.length } ?: 0) + 1) }
        // The literal as the author meant it, with every template read as a signal.
        val helpers by lazy(LazyThreadSafetyMode.NONE) {
            val found = ArrayList<Fix>()
            if (whole && !BRACED_TEMPLATE.containsMatchIn(content)) {
                // A helper is not a string: it replaces the literal together with the trim call after it.
                val end = s.end + (trimAfter(src, s)?.text?.length ?: 0)
                for ((re, helper) in HELPERS) {
                    if (available != null && helper !in available) continue
                    // The whole content: `$` alone would also match before a final line break.
                    val m = re.matchEntire(content) ?: continue
                    val call = "$helper(\"${m.groupValues[1]}\")"
                    found.add(0, Fix("Use $call", s.start, end, call, importEdits(src, HTML_PACKAGE, helper)))
                }
            }
            found
        }
        // Where the DSL may be missing, signal(...) is advised only where it can be called.
        val advisesSignal = available == null || "signal" in available
        return trap@{ ip ->
            // In a multi-dollar literal a single $ is text, so there is no trap: every template there is deliberate.
            if (ip.kind != InterpolationKind.SIMPLE || ip.dollars > 1) return@trap null
            val fixes = ArrayList<Fix>(helpers)
            if (whole) fixes += multiDollarFix(s, src, ip, dollars)
            fixes += Fix("Escape as \${'$'}${ip.text}", ip.start, ip.start + 1, "\${'$'}")
            if (!s.rawAt(ip)) fixes += Fix("Escape as \\$${ip.text}", ip.start, ip.start, "\\")
            Issue(
                start = ip.start,
                end = ip.end,
                message =
                    "Kotlin interpolates \$${ip.text} here; the browser will never see a signal. " +
                        (if (advisesSignal) "Write signal(\"${ip.text}\"), make" else "Make") +
                        " the literal \$\$\"...\" (Kotlin 2.2+), or escape as \${'$'}${ip.text}.",
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
    ): Judged {
        if (s.interpolations.isEmpty()) return Judged()
        val trapOf = traps(s, src)
        // Without core there is no interpolate to build the string with, and Trusted is not Streamlord's.
        val sdk = Sdk.of(opts)
        val core = sdk.core
        // A fix writes interpolate or Trusted only where the name is core's: not somebody else's call or import.
        val canInterpolate = coreNameUsable(src, "interpolate", sdk, opts.foreignCalls)
        val canTrust = coreNameUsable(src, "Trusted", sdk, opts.foreignCalls)
        // A literal Trusted is markup the author wrote, read in place: the other holes are judged against it.
        val written = HashMap<Int, String>()
        for ((k, ip) in s.interpolations.withIndex()) {
            // A Trusted the editor resolves to another package is none of core's, whatever the text says.
            if (ip.kind == InterpolationKind.OPERAND || !core || opts.valueFact?.invoke(ip.start, ip.end).isOther()) continue
            trustedLiteral(src, ip.text, s.start)?.let { written[k] = it }
        }
        val reading =
            readHoles(
                view,
                s.interpolations.map { it.decodedStart until it.decodedStart + PLACEHOLDER.length },
                // Both bundles' prefixes, as interpolate reads them: a value is judged by what either would run.
                listOf(opts.prefix, CatalogNames.PREFIX, CatalogNames.ALIASED_PREFIX).distinct(),
                written,
            )
        val landings = reading.landings
        val told = s.interpolations.map { holeFact(s, src, opts, it) }
        val starts = s.interpolations.map { it.decodedStart }
        val holes = s.interpolations.indices
        // What the rewrite can do is asked only by a value that has something to say of it: read when the first asks.
        // interpolate takes a Trusted wherever it lands and writes it as it is, as a template does.
        val trusted by lazy(LazyThreadSafetyMode.NONE) {
            holes.map { k ->
                told[k] == ValueFact.TRUSTED ||
                    (!told[k].isOther() && trustedValue(src, s.interpolations[k].text, s.start))
            }
        }
        // It refuses every other value in a URL that loads code unless the literal fixes its host.
        val loose by lazy(LazyThreadSafetyMode.NONE) { holes.map { k -> hostUnfixed(reading, starts, k) { true } } }
        val keeps by lazy(LazyThreadSafetyMode.NONE) {
            holes.map { k ->
                trusted[k] || (!loose[k] && interpolateKeeps(src, s, s.interpolations[k], told[k], landings[k].position, opts))
            }
        }
        // CSS in the body of a <style> is the author's to vouch for: the rewrite passes it as Trusted(...), which takes
        // a String. An editor that types the values says which are; the text alone leaves most unknown.
        val vouched by lazy(LazyThreadSafetyMode.NONE) {
            holes.filterTo(LinkedHashSet()) { k ->
                canTrust && !keeps[k] && (told[k].isString() || (told[k] == null && opts.valueFact == null)) &&
                    rawElement(view, landings[k], starts[k]) == "style" && !mayBeNull(src, s.interpolations[k].text, s.start)
            }
        }
        val trim = trimAfter(src, s)
        // Why the string cannot be rewritten whatever its values are, in a few words, or null where it can.
        val unfit by lazy(LazyThreadSafetyMode.NONE) {
            when {
                s.called -> "a call after one literal of the + chain applies to that literal alone"

                s.unread -> "what is joined to the string with + cannot be read as values"

                // The trim call stays on the format, inside the call: one read here, with a margin that is no format character.
                trim != null && (trim.text == null || trim.margin?.contains('%') == true) -> "the trim call after the string cannot be kept"

                // An annotation and a const val take a constant, which no call is.
                constantOnly(src, s.start) -> ""

                else -> null
            }
        }
        // interpolate(...) can take the string only when every value is one it accepts where it lands.
        val fixable by lazy(LazyThreadSafetyMode.NONE) { canInterpolate && unfit == null && holes.all { keeps[it] || it in vouched } }
        // The first values it does not take as they are: what the message of another value names.
        val inTheWay by lazy(LazyThreadSafetyMode.NONE) { holes.filterNot { keeps[it] }.take(2) }
        // Whether the rewrite leaves the page as it was, so that an editor may prefer it: not where it vouches for a
        // value, where one may be null, which a template writes as `null` and interpolate as nothing, nor under
        // trimIndent, where a value with a line break changed what was trimmed.
        val same by lazy(LazyThreadSafetyMode.NONE) {
            vouched.isEmpty() && (trim == null || trim.margin != null) &&
                holes.none { told[it].isNullable() || told[it].isNullableString() || mayBeNull(src, s.interpolations[it].text, s.start) }
        }
        // The rewrite is made once for the string, and only where an issue offers it.
        val rewrite by lazy(LazyThreadSafetyMode.NONE) { interpolateFix(src, s, trim?.text.orEmpty(), vouched) }
        var offered = false

        fun rewriteAs(
            title: String = rewrite.title,
            preferred: Boolean = false,
        ): Fix {
            offered = true
            return rewrite.copy(title = title, preferred = preferred && same)
        }
        val issues = ArrayList<Issue>()
        for ((k, ip) in s.interpolations.withIndex()) {
            if (k in written) continue
            val landing = landings[k]
            val fact = told[k].plain()
            val shown = holeDisplay(ip)
            val factSays = verdict(fact, landing.position)
            // In a Datastar expression `$name` may be meant as a signal, whatever the value is.
            if (factSays == Verdict.SAFE && landing.position != Position.DATASTAR) continue
            val severity = if (fact.knownCharacters()) Severity.WARNING else Severity.HINT

            // The rewrite escapes nothing of a Trusted, so the issue of one does not offer it.
            fun rewritten(
                title: String,
                preferred: Boolean = true,
            ): List<Fix> = if (fixable && !trusted[k]) listOf(rewriteAs(title, preferred)) else emptyList()

            // What the rewrite does with another value, or why none is offered: the first value in the way.
            fun rewriteNote(): String {
                if (!canInterpolate || trusted[k]) return ""
                if (!unfit.isNullOrEmpty()) return " interpolate(...) is not offered here: $unfit."
                // This value alone in the way, for the reason interpolateKeeps gives in element text.
                val own = !keeps[k] && landing.position == Position.TEXT && mayHoldMarkup(src, ip.text, s.start, opts.constants)
                val other =
                    inTheWay.firstOrNull { it != k }
                        ?: return if (own) " interpolate(...) is not offered here: it would escape this value, which may be markup you built." else ""
                val named = holeDisplay(s.interpolations[other])
                val place = refusedPlace(view, landings[other], starts[other])
                val refused = loose[other] || landings[other].position !in ESCAPED_POSITIONS
                return when {
                    fixable -> " The fix passes $named, in $place, as Trusted(...)."
                    refused -> " interpolate(...) cannot take this string while $named stands in $place."
                    else -> " interpolate(...) is not offered here: it would escape $named, which may be markup you built."
                }
            }

            fun issue(
                message: String,
                severity: Severity = Severity.WARNING,
                code: String = "unsafe-interpolation",
                fixes: List<Fix> = emptyList(),
            ): Issue {
                val link = if (landing.position == Position.DATASTAR) Docs.EXPRESSIONS else Docs.STRINGS
                return Issue(ip.start, ip.end, message, severity, code, link, fixes)
            }

            fun unescaped(): Issue {
                // A String in element text may be markup the author built, which only the author knows: then
                // both the rewrite, which escapes it, and Trusted, which keeps it, are offered, and neither first.
                val text = landing.position == Position.TEXT
                val trust =
                    text && !told[k].isNullableString() &&
                        (
                            fact.isString() ||
                                (
                                    fact == null &&
                                        (callResult(ip.text) || markupTextValue(src, ip.text, s.start)) &&
                                        mayHoldMarkup(src, ip.text, s.start, opts.constants)
                                )
                        ) &&
                        !mayBeNull(src, ip.text, s.start) && !visiblyList(src, ip.text, s.start)
                // A Trusted that opens a `+` chain would be joined to nothing, since Kotlin has no plus for it: no such fix there.
                val fixes =
                    rewritten(ESCAPES_TITLE, !trust) +
                        if (trust && canTrust && ip.start != s.start && !ip.unread) listOf(trustedFix(src, ip, shown)) else emptyList()
                // Without core, plain Kotlin and kotlinx.html stand in for interpolate and Trusted, and a call or a
                // value of unknown type may be markup the author composed; a known String stays a warning.
                val message = unescapedMessage(shown, fact.isMarkup(), text, canInterpolate, canTrust, opts.coreHtml != false)
                val level = if (!core && trust && fact == null) Severity.HINT else severity
                return issue(message + rewriteNote(), level, "unescaped-interpolation", fixes)
            }

            fun schemeIssue(attribute: String): Issue {
                val fixes = rewritten(REFUSES_URL_TITLE)
                return issue(
                    "Kotlin writes $shown at the start of $attribute, so the value decides the scheme, " +
                        "and a javascript: URL runs. Escaping does not help: start the value with /, # or https://" +
                        (if (fixes.isNotEmpty()) ", or build the string with $REFUSES_SCHEME." else ".") + rewriteNote(),
                    fixes = fixes,
                )
            }

            // Its digits are safe; only interpolate would refuse it, which a project without core has none of.
            fun unsigned(where: String): Issue? = if (core) issue(unsignedWrittenMessage(shown, where), Severity.HINT) else null

            issues +=
                when (landing.position) {
                    Position.TEXT, Position.ATTRIBUTE -> {
                        // The content of a <meta> may be a refresh, whose URL interpolate judges as any URL.
                        when {
                            factSays != Verdict.ESCAPE -> null
                            refreshUrlStart(view, landing) { reading.prefix(k, k) } -> schemeIssue("the URL of content")
                            else -> unescaped()
                        }
                    }

                    Position.URL -> {
                        val attribute = landing.attribute ?: "the URL"
                        val prefix = reading.prefix(k, k)
                        when {
                            landing.loadsCode && !urlHostFixedBy(attribute, prefix) -> issue(codeUrlMessage(shown, attribute))
                            urlSchemeFixedBy(attribute, decodeEntities(prefix).text) -> unescaped()
                            else -> schemeIssue(attribute)
                        }
                    }

                    Position.STYLE -> {
                        val where = landing.attribute ?: "style"
                        val advice =
                            if (fact.isNullable()) {
                                "It is a number that may be null, which Kotlin writes as null: give it a value with ?:, or choose between classes."
                            } else {
                                "Choose between classes, or write a number."
                            }
                        if (factSays == Verdict.DIGITS) {
                            unsigned(where)
                        } else {
                            issue("Kotlin writes $shown into $where, where it is read as CSS, so escaping does not help. $advice")
                        }
                    }

                    Position.DATASTAR -> {
                        val attribute = landing.attribute ?: "a Datastar attribute"
                        val trap = trapOf(ip)?.let { datastarTrap(it, ip, landing, view, opts.prefix, fact) }
                        when {
                            trap != null -> trap
                            factSays == Verdict.DIGITS -> unsigned(attribute)
                            factSays == Verdict.UNSAFE -> datastarIssue(ip, attribute)
                            else -> null
                        }
                    }

                    Position.CODE -> {
                        val vouch = fixable && k in vouched
                        issue(
                            "Kotlin writes $shown into ${landing.attribute ?: "a script or style element"}, which the browser runs, " +
                                "so escaping does not help. Put the value in a data-* attribute and read it from there." +
                                if (vouch) " CSS you wrote yourself can be passed to interpolate(...) as Trusted(...)." else "",
                            fixes = if (vouch) listOf(rewriteAs()) else emptyList(),
                        )
                    }

                    Position.STRUCTURE -> {
                        // `</h$n>` names the element `<h$n` opened; the opening one is judged.
                        val closing = closingTagBefore(view, ip.decodedStart)
                        val closes =
                            closing != null &&
                                s.interpolations.take(k).any {
                                    it.text == ip.text && it.decodedStart > closing.length &&
                                        view.startsWith("<$closing", it.decodedStart - closing.length - 1)
                                }
                        when {
                            closes -> {
                                null
                            }

                            endTagAt(view, ip.decodedStart) -> {
                                issue(
                                    "Kotlin writes $shown into an end tag, which takes no attributes: the browser drops them, " +
                                        "and a value from outside can still end the tag and write markup. Write the end tag from literals alone.",
                                    severity,
                                )
                            }

                            unquotedValueBefore(view, ip.decodedStart) -> {
                                issue(
                                    "Kotlin writes $shown into an attribute value without quotes, where a space starts a new attribute. Quote the value.",
                                    if (fact.scalar()) Severity.HINT else Severity.WARNING,
                                    fixes = listOfNotNull(quoteFix(s, view, ip)),
                                )
                            }

                            else -> {
                                issue(
                                    "Kotlin writes $shown into the markup's own structure: a tag name, an attribute name " +
                                        "or a list of attributes. Fine for one chosen in code; a value from outside decides " +
                                        "the markup itself, and escaping cannot prevent that.",
                                    severity,
                                )
                            }
                        }
                    }
                } ?: continue
        }
        // The context action is for a string some issue of which offers the rewrite: one with a value to escape.
        return Judged(issues, if (offered) rewrite else null)
    }

    /** The `<style>` or `<script>` whose body the value at [at] of the markup lands in, as the markup scan reads it, or null. */
    private fun rawElement(
        view: String,
        landing: HoleLanding,
        at: Int,
    ): String? {
        if (landing.position != Position.CODE || landing.attribute != null) return null
        var open: String? = null
        for (tag in tokenize(view, false).tags) {
            if (tag.start >= at) break
            val name = tag.name.asciiLowercase()
            if (tag.closing && name == open) {
                open = null
            } else if (!tag.closing && (name == "style" || name == "script")) {
                open = name
            }
        }
        return open
    }

    /** Whether a value after [before] in the `content` of a `<meta>` starts the URL a refresh reads: `0;url=`. */
    private fun refreshUrlStart(
        view: String,
        landing: HoleLanding,
        before: () -> String,
    ): Boolean =
        landing.attribute?.asciiLowercase() == "content" && landing.tagStart >= 0 && landing.valueStart >= 0 &&
            view.startsWith("<meta", landing.tagStart, ignoreCase = true) &&
            REFRESH_BEFORE_URL.matchEntire(decodeEntities(before()).text) != null

    /** Whether the literal at [start] stands where Kotlin takes a constant alone: the argument of an annotation, or a `const val`. */
    private fun constantOnly(
        src: String,
        start: Int,
    ): Boolean {
        val before = src.substring(maxOf(0, start - 400), start)
        if (CONST_VAL_BEFORE.containsMatchIn(before)) return true
        // The bracket the literal is an argument in, and what it belongs to.
        var depth = 0
        for (k in before.indices.reversed()) {
            val c = before[k]
            if (c == ')' || c == ']') depth++
            if ((c == '(' || c == '[') && depth-- == 0) return ANNOTATION_BEFORE.containsMatchIn(before.substring(0, k))
            // A line that goes on from no argument list is where the statement starts.
            if (c == '\n' && depth == 0 && before.lastNonSpaceBefore(k).let { it != ',' && it != '(' && it != '[' }) return false
        }
        return false
    }

    /** Where a value interpolate refuses stands, as the issue of another value names it. */
    private fun refusedPlace(
        view: String,
        landing: HoleLanding,
        at: Int,
    ): String =
        landing.attribute ?: when {
            landing.position == Position.CODE -> "a <${rawElement(view, landing, at) ?: "script"}>"
            unquotedValueBefore(view, at) -> "an attribute value without quotes"
            else -> "the markup's structure"
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
     * rewrite keeps the page: anything it admits where it lands, but not a `Trusted` outside element
     * text (it is not escaped there, so the rewrite would fix nothing), nor markup held as a String
     * in element text, which it would escape. A value that may be null is kept: a template writes
     * `null` for it, which no page means, and interpolate nothing.
     */
    private fun interpolateKeeps(
        src: String,
        s: KotlinString,
        ip: Interpolation,
        told: ValueFact?,
        position: Position,
        opts: AnalyzeOptions,
    ): Boolean {
        val fact = told.plain()
        if (position == Position.DATASTAR || position == Position.STYLE) return fact.interpolateTakesIn(position)
        if (position !in ESCAPED_POSITIONS) return false
        // A value that holds a Trusted among other things is escaped as a whole anywhere but in element text.
        if (!told.isOther() && callsTrusted(src, ip.text)) return position == Position.TEXT
        if (fact.isMarkup()) return position != Position.TEXT || markupValued(src, s, ip)
        if (position == Position.TEXT && !fact.digitsOrBoolean() &&
            mayHoldMarkup(src, ip.text, s.start, opts.constants)
        ) {
            return false
        }
        return !(fact.isConstant() && position == Position.TEXT && ip.kind != InterpolationKind.SIMPLE && '<' in ip.text)
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
    ): ValueFact? {
        opts.valueFact?.invoke(ip.start, ip.end)?.let { return it }
        val sdk = Sdk.of(opts)
        val values = if (ip.kind == InterpolationKind.SIMPLE) listOf(HoleValue(ip.text, false)) else holeValues(ip.text)
        val kinds = values.map { valueKind(src, it.text, s.start, opts.constants, it.nonNull, sdk) }
        if (kinds.all { it.isConstant() }) return ValueFact.CONSTANT
        if (isMarkup(src, s, ip, sdk)) return ValueFact.MARKUP
        return weakest(kinds)
    }

    /**
     * What a value written into the HTML as it is can do, and what to write instead: [interpolate]
     * and [trusted] say whether a fix may name `interpolate` and `Trusted` in this file, which
     * plain Kotlin and kotlinx.html stand in for where it may not, and [html] whether the project's
     * core has `Html` to declare.
     */
    private fun unescapedMessage(
        shown: String,
        markup: Boolean,
        text: Boolean,
        interpolate: Boolean,
        trusted: Boolean,
        html: Boolean,
    ): String {
        // Markup an attribute holds is only characters, whose quotes end the value: that it is markup is the problem.
        if (markup) {
            return "Kotlin writes $shown into the HTML as it is, and it is markup: inside an attribute its quotes and tags " +
                "are only characters, and a quote ends the value. Write text there rather than markup."
        }
        val base =
            "Kotlin writes $shown into the HTML as it is. If it can hold text from outside, " +
                if (interpolate) {
                    "build the string with interpolate(...), which escapes it"
                } else {
                    "escape it before it goes in, or build this part with kotlinx.html, which escapes " +
                        if (text) "text" else "attribute values"
                }
        if (!text) return "$base."
        val built = if (!trusted) "this is fine" else (if (html) "declare it Html or " else "") + "wrap it in Trusted(...)"
        return "$base; if it is markup you built, $built."
    }

    /** A Kotlin value written into [attribute], which Datastar runs: an attribute of the markup, or the one a DSL call writes. */
    private fun datastarIssue(
        ip: Interpolation,
        attribute: String,
    ): Issue =
        Issue(
            ip.start,
            ip.end,
            "Kotlin writes ${holeDisplay(ip)} into $attribute, which Datastar runs as an expression or follows as a signal path. " +
                "Text from outside becomes code there, even inside quotes. " +
                "Put the value in an attribute of its own, such as data-label, and read it in the expression with el.dataset.label; " +
                "only a number or a boolean is safe to write in.",
            Severity.WARNING,
            "unsafe-interpolation",
            Docs.EXPRESSIONS,
        )

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
        fact: ValueFact?,
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
        if (fact != null && fact.digitsOrBoolean()) {
            val what = fact.described()
            return trap.copy(
                severity = Severity.HINT,
                fixes = fixes,
                message =
                    "Kotlin writes \$$name here, $what from the server, which is safe in a Datastar expression. " +
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
        helpers: Set<String>,
    ): List<Issue> {
        if (spec.onlyIfStringArgs && site.args.any { it.named == null && it.string == null }) return emptyList()
        val s = expressionString(site, spec, src) ?: return emptyList()
        if (s.unterminated) return emptyList()
        val attribute = catalog.attributesByKotlin[site.name]
        val named = attribute?.let { CatalogNames.PREFIX + it.name } ?: "the expression of ${site.name}"
        // An import from elsewhere, Hamcrest's not, takes a helper's name here too.
        val issues = ArrayList(expressionHoles(s, src, opts, named, helpers))
        val text = s.text
        if (PLACEHOLDER in text && s.interpolations.any { it.kind == InterpolationKind.SIMPLE }) return issues
        issues += mapIssues(s, src, expressions.validate(text, hyphenatedSignals = hyphenated, attribute = attribute))
        return issues
    }

    /**
     * The templates of a literal Datastar runs as an expression, and the values joined to it with
     * `+`. `$name` where a signal could stand is the trap. Any other template, `${expr}`, one inside
     * a JavaScript string (`@post('/x/$id')`, where Datastar leaves a `$` alone, engine.ts:546 in
     * 1.0.5), or one in a `$$` literal, writes the Kotlin value into code Datastar runs: a `'` in text
     * from outside ends the string and the rest runs. A number or a boolean is safe there, as
     * `interpolate` lets in.
     */
    private fun expressionHoles(
        s: KotlinString,
        src: String,
        opts: AnalyzeOptions,
        attribute: String,
        helpers: Set<String>? = null,
    ): List<Issue> {
        val trapOf = traps(s, src, helpers)
        val out = ArrayList<Issue>()
        for (ip in s.interpolations) {
            val trap = trapOf(ip)
            if (trap != null && quoteAt(s.text, ip.decodedStart) == null) {
                out += trap
                continue
            }
            val fact = holeFact(s, src, opts, ip).plain()
            // Kotlin writes the digits of an unsigned number; it is interpolate that would refuse it.
            if (fact.writesItself()) continue
            out += datastarIssue(ip, attribute)
        }
        return out
    }

    private fun checkHtmlSite(
        site: CallSite,
        spec: CallSiteSpec,
        opts: AnalyzeOptions,
        src: String,
        hyphenated: Set<String>,
    ): Judged {
        val s = markupString(site, spec, src)
        if (s == null && !site.trailingLambda) return Judged()
        if (s != null && s.unterminated) return Judged()
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
        val judged = s?.let { judgeHoles(it, src, opts, view) }
        val issues = ArrayList(judged?.issues.orEmpty())
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
                    markup.validateMarkup(
                        view,
                        MarkupOptions(
                            requireIds,
                            opts.prefix,
                            opts.checkHtmlAttributes,
                            hyphenated,
                            templates = false,
                            prefixSetting = opts.prefixSetting,
                        ),
                    ),
                )
        }
        return Judged(issues, judged?.rewrite)
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
        val named = site.args.firstOrNull { it.named == "case" || (it.named == null && CASE_ARG.matchEntire(it.text) != null) }?.text
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
        // A name given by name is the name, literal or not: no positional argument stands in for it.
        val named = site.named("name") ?: site.named("attribute")
        val first = site.positional.takeIf { it.size >= 2 || site.named("expression") != null }?.firstOrNull()
        val key = (named ?: first)?.string ?: return emptyList()
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

/**
 * An `attributes["data-..."] = "..."`: the attribute's [name], where it starts in the source, and the literal assigned. The
 * diagnostics judge the value only when it is [whole], the full statement and not the first half of a concatenation.
 */
public data class AttributesIndex(
    val name: String,
    val nameStart: Int,
    val value: KotlinString?,
    val whole: Boolean,
)

/**
 * The issues that belong to one DSL call, or to one free-standing HTML string, and the [rewrite]
 * of its HTML string to a call of `interpolate`, where every value in it is one `interpolate` keeps.
 */
public data class SiteIssues(
    val site: CallSite?,
    val string: KotlinString?,
    val issues: List<Issue>,
    val rewrite: Fix? = null,
)

/**
 * The rewrite of an HTML string to a call of `interpolate` that an editor offers as a context
 * action for the caret or selection from [start] to [end] in a file whose [sites] are analysed:
 * the selection lies in one string that has a value the rewrite escapes and whose values
 * `interpolate` accepts, and no issue the selection touches offers the rewrite already. Null anywhere else.
 */
public fun rewriteAt(
    sites: List<SiteIssues>,
    start: Int,
    end: Int = start,
): Fix? {
    // The innermost: a literal inside a template of another has a rewrite of its own.
    val site =
        sites
            .filter { it.rewrite != null && start >= it.rewrite.start && end <= it.rewrite.end }
            .minByOrNull { it.rewrite!!.end - it.rewrite.start } ?: return null
    val rewrite = site.rewrite!!
    val offered = site.issues.any { i -> i.start <= end && start <= i.end && i.fixes.any { it.sharesTextWith(rewrite) } }
    return rewrite.takeUnless { offered }
}

/** The issues of the values of an HTML string, and its rewrite to `interpolate` where that can be offered. */
internal class Judged(
    val issues: List<Issue> = emptyList(),
    val rewrite: Fix? = null,
)

internal fun String.lastNonSpaceBefore(index: Int): Char? {
    var i = index - 1
    while (i >= 0 && this[i].isWhitespace()) i--
    return getOrNull(i)
}
