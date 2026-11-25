package io.github.markusaugust.streamlord.analysis

import io.github.markusaugust.streamlord.core.catalog.CatalogNames
import io.github.markusaugust.streamlord.core.client.ClientCasing
import io.github.markusaugust.streamlord.core.client.asciiLowercase
import io.github.markusaugust.streamlord.core.client.indexOfAsciiIgnoreCase
import io.github.markusaugust.streamlord.core.client.regex
import io.github.markusaugust.streamlord.core.domain.AttributeKeys
import io.github.markusaugust.streamlord.core.domain.CspNonce
import io.github.markusaugust.streamlord.core.domain.ElementNamespace
import io.github.markusaugust.streamlord.core.domain.ElementPatchMode
import io.github.markusaugust.streamlord.core.domain.ModifierRules
import kotlin.concurrent.Volatile
import kotlin.time.Duration

/*
 * Editor-independent analysis: Kotlin or HTML source in, issues with source offsets out.
 */

/** The setting as the IntelliJ plugin's settings page shows it. */
internal const val PREFIX_SETTING: String = "the Streamlord attribute prefix setting"

/** A file as the rules read it: its [source], and its [modules] settled once for all of them. */
internal class Reading(
    val source: Source,
    val modules: FileModules,
    /** Where each value the editor is asked about is noted, when the questions themselves are what is wanted. */
    private val asked: MutableMap<Pair<Int, Int>, ValueQuestion>? = null,
) {
    val prefix: String get() = source.prefix
    val prefixSetting: String get() = source.prefixSetting
    val constants: Set<String> get() = source.constants

    /**
     * What the editor knows of the value at [start] to [end]. Of one handed to `interpolate(...)`, an [argument], only that
     * its class is somebody else's is taken: the rest is read from the text. Asked
     * whatever the answers to the others are, so a reading with no answers notes every value one with answers asks about.
     */
    fun fact(
        start: Int,
        end: Int,
        argument: Boolean = false,
    ): ValueFact? = answer(start, end)?.fact()?.takeIf { !argument || it == ValueFact.OTHER }

    /** All the editor answered of the value at [start] to [end], which is noted as asked about. */
    private fun answer(
        start: Int,
        end: Int,
    ): ValueAnswer? {
        asked?.getOrPut(start to end) { ValueQuestion(start, end) }
        return source.valueAnswer?.invoke(start, end)
    }

    /**
     * The editor's answers of the parts of what is written at [start] to [end], a value or what a declaration is set to,
     * each found by its text between the two, wherever it is written there as code and as a whole: not after a dot, where
     * it is a member of something, nor before `->`, where a lambda names it. One written twice is answered for only where
     * the editor says the same of both, as `it` of two lambdas may be two things. A template is asked about by its own range.
     */
    fun parts(
        start: Int,
        end: Int,
    ): Editor {
        val text = source.text
        val template = text[start] == '$'
        val region = text.substring(start, end)
        val whole = region.trimStart('$').removeSurrounding("{", "}").trim()
        // A part is written as the readings see it, each comment a blank: where it is in the file runs from its first character to its last.
        val (seen, offsets) = uncommentedAt(region)

        fun ranges(part: String): List<Pair<Int, Int>> {
            if (template && part.trim() == whole) return listOf(start to end)
            val code = fileFacts(text).code
            val out = LinkedHashSet<Pair<Int, Int>>()

            // Where [t] is written in what the readings see, not as the letters of a longer name, `it` of `items`, nor the text of a literal, `n` of `"n"`.
            fun find(t: String) {
                if (t.isEmpty()) return
                val name = isWordPart(t.first())

                fun elsewhere(
                    at: Int,
                    from: Int,
                    to: Int,
                ): Boolean {
                    if (!code[from] && t.first() != '"') return true
                    if (name && isWordPart(text.getOrNull(from - 1))) return true
                    if (isWordPart(t.last()) && isWordPart(text.getOrNull(to))) return true
                    val before = codeBefore(text, code, from).takeIf { it >= start }?.let { text[it] }
                    var after = at + t.length
                    while (after < seen.length && seen[after].isWhitespace()) after++
                    return name && (before == '.' || before == ':' || seen.startsWith("->", after))
                }
                var at = seen.indexOf(t)
                while (at >= 0) {
                    val from = start + offsets[at]
                    val to = start + offsets[at + t.length - 1] + 1
                    if (!elsewhere(at, from, to)) out += from to to
                    at = seen.indexOf(t, at + 1)
                }
            }
            // A part of a template's code is written with its comments, which the readings see as blanks outside a literal and
            // as they are inside one, a literal of the value being passed over whole.
            val written = part.trim()
            val blanked = uncommented(written).trim()
            find(blanked)
            if (written != blanked) find(written)
            return out.sortedBy { it.first }
        }

        fun <T> agreed(
            part: String,
            answer: (Int, Int) -> T?,
        ): T? = ranges(part).map { answer(it.first, it.second) }.distinct().singleOrNull()
        return Editor(
            { part -> agreed(part) { from, to -> fact(from, to) } },
            { part -> agreed(part) { from, to -> answer(from, to) } },
            { from, to -> parts(from, to) },
        )
    }
}

/**
 * A value of a Kotlin file the analysis asks the editor about, by where it stands: a template of an HTML string, an
 * operand of a `+` chain, a value marked with `+` in `interpolate { }`, an argument of `interpolate(...)`, or a part of
 * one of these whose type decides how the whole is read, as `rows` of `rows.joinToString("")`, or of what a `val` or a
 * function of the file that such a value names is set to.
 */
public class ValueQuestion internal constructor(
    public val start: Int,
    public val end: Int,
)

/**
 * What is known of a value, from the text or from what an editor answered ([ValueAnswer]): its type, or that it
 * is a constant or markup. [NULLABLE] is a
 * number or a boolean that may be null (`Int?`): digits, true, false or null. [NULLABLE_STRING] is
 * a `String?`, which a Kotlin template writes as `null` and `interpolate` as nothing. [UNSIGNED] is
 * a `UInt`, `ULong`, `UShort` or `UByte`: digits, but not a `kotlin.Number`, so `interpolate`
 * refuses it where only a number is let in. [TRUSTED] is core's `Trusted`, judged as [MARKUP] is,
 * which `interpolate` writes as it stands wherever it lands, so a rewrite escapes nothing of it.
 * [OTHER] is a class the editor resolved whose name the
 * text alone would take for Streamlord's markup, an `Html` or a `Trusted` of another package:
 * nothing about it is known, and no reading of the text makes it markup. Only
 * IntelliJ gives it; VS Code has no resolver.
 */
internal enum class ValueFact {
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

    /** A List, a Set, a Map or an array, which a template writes in brackets: no CSS, nor anything to vouch for. */
    COLLECTION,

    /** A `kotlin.Number` of no closed type: a number to interpolate, but a class of it may write any text in code. */
    OPEN_NUMBER,
    ;

    /** What [ofTypeName] in ValueTypes.kt hangs on. */
    companion object
}

/** The name of Trusted's argument written before it, `html = `. */
private val NAMED_HTML = regex("""^\s*html\s*=""")

/**
 * A call of the placeholder form, [site], in [block], whose Html the block reads: its markup is its format, at [format],
 * and the text of each Trusted handed to it, at [trusted]. It judged its values with the block's marks unread, so a value
 * marked there may stand in element text alone. [marks] are those its markup holds, a call's handed to it among them;
 * [givenAt] where a template of the block's markup is the call whole, which writes the Html there; [handedOn] where it is
 * a value of a second call, which may escape it where it lands there, marks and all.
 */
private class PlaceholderHtml(
    val site: CallSite,
    val block: InterpolateBlock,
    val format: IntRange?,
    val trusted: List<IntRange>,
    val marks: List<Interpolation>,
    val givenAt: Int?,
    val handedOn: Boolean,
    /** Whether its markup writes a quote, which may end the attribute it lands in and put a mark in element text. */
    val quotes: Boolean,
    /** Where each literal starts that the call reads in place, a Trusted's of marks alone, judging its marks itself. */
    val inPlace: Set<Int>,
) {
    /** Whether a literal standing in the argument of a `Trusted(...)` at [inTrusted] is of this Html's markup. */
    fun holds(inTrusted: IntRange?): Boolean =
        inTrusted != null && !handedOn &&
            (
                trusted.any { inTrusted.first > it.first && inTrusted.last < it.last } ||
                    format?.let { inTrusted.first > it.first && inTrusted.last < it.last } == true
            )
}

private val CONSTANT_NAME = regex("""(?:^|\.)([A-Z]+)\s*$""")

/** The constant [arg] names, `APPEND` of `ElementPatchMode.APPEND` or of an imported `APPEND`; null for a value the code computes. */
private fun constantName(arg: Arg): String? = CONSTANT_NAME.find(arg.text)?.groupValues?.get(1)

private val CASE_ARG = regex("""^\s*Case\.[A-Z]+\s*$""")
private val CASE_VALUE = regex("""Case\.([A-Z]+)""")
private val CASE_IN_LAMBDA = regex("""\bcase\s*=\s*Case\.([A-Z]+)""")
internal val OPENS_OBJECT_OR_ARRAY = regex("""^\s*[{\[]""")
internal val HAS_CAPITAL = regex("""[A-Z]""")

// A property set in a lambda of modifiers, `{ once = true }`, and its value; `==` is a comparison and
// `val once = 1` a variable of the lambda's own.
private val LAMBDA_ASSIGNMENT = regex("""(?<!\b(?:val|var)\s)\b([A-Za-z]+)\s*=(?!=)\s*([^;\n}]*)""")
private val KOTLIN_STRING = regex(""""(?:[^"\\]|\\.)*"""")

// No time at all, `0.seconds` or `Duration.ZERO`, which an interval refuses.
private val ZERO_DURATION =
    regex("""(?:0+(?:\.0+)?\.(?:nanoseconds|microseconds|milliseconds|seconds|minutes|hours|days)|(?:Duration\.)?ZERO)""")

// The parameter a DSL helper takes its key by, when it is passed by name.
private val KEY_PARAMETERS = listOf("name", "event", "key", "property", "attribute", "signal")

/** The length of the `=` and the white space around it that end [src] before [end], or 0: read back from [end], not from the start. */
private fun assignBefore(
    src: String,
    end: Int,
): Int {
    var k = end
    while (k > 0 && src[k - 1] in JAVA_SPACE) k--
    if (k == 0 || src[k - 1] != '=') return 0
    k--
    while (k > 0 && src[k - 1] in JAVA_SPACE) k--
    return end - k
}

/** What `\s` matches in java.util.regex. */
private const val JAVA_SPACE = " \t\n\u000B\u000C\r"

private val INTERPOLATE = setOf("interpolate")

/**
 * `interpolate {`, `interpolate(prefixes) {`, or the block in the parentheses, `interpolate({` and
 * `interpolate(prefixes, {`, by its name `block =` too: a call of its own and no member of anything.
 */
private val INTERPOLATE_BLOCK =
    regex(
        // Each piece is a pattern of its own: the name, the parenthesis that holds the block, the prefixes before it.
        """(?<![\p{L}\p{Nd}_.`])(?:io\.github\.markusaugust\.streamlord\.core\.domain\.)?(interpolate)\s*""" +
            """(?:\(\s*(?:([^(){}]*(?:\([^()]*\)[^(){}]*)*),\s*)?(?:block\s*=\s*)?(?=\{))?""" +
            """(?:\(([^()]*(?:\([^()]*\)[^()]*)*)\)\s*)?\{""",
    )
private val PREFIXES_GIVEN =
    regex("""(?:prefixes\s*=\s*)?(?:listOf|mutableListOf|arrayListOf)\s*\(\s*((?:"[^"\\$]*"\s*,\s*)*(?:"[^"\\$]*"\s*,?\s*)?)\)""")
private val PLAIN_STRING = regex(""""([^"\\$]*)"""")

internal class InterpolateBlock(
    val site: CallSite,
    val braces: IntRange,
    val prefixes: List<String>?,
)

/** The braces of each `interpolate { ... }` and `interpolate(prefixes) { ... }` of the file, with the prefixes written as plain literals. */
internal fun interpolateBlocks(
    src: String,
    code: BooleanArray,
): List<InterpolateBlock> =
    INTERPOLATE_BLOCK.findAll(src).filter { code[it.range.first] }.mapNotNullTo(ArrayList()) { m ->
        val name = m.range.first + m.value.indexOf("interpolate")
        // A function declared by the name is no call of it.
        val before = codeBefore(src, code, m.range.first)
        if (before >= 2 && src.startsWith("fun", before - 2) && !isWordPart(src.getOrNull(before - 3))) {
            return@mapNotNullTo null
        }
        // The braces of code, a template's code among it: a block may stand in a template of another's markup.
        var depth = 0
        val close =
            (m.range.last until src.length).firstOrNull {
                code[it] &&
                    ((src[it] == '{' && ++depth < 0) || (src[it] == '}' && --depth == 0))
            }
                ?: return@mapNotNullTo null
        val list = (m.groups[2] ?: m.groups[3])?.let { PREFIXES_GIVEN.matchEntire(it.value.trim()) }
        InterpolateBlock(
            CallSite("interpolate", name, m.range.last, close, emptyList(), trailingLambda = true),
            m.range.last..close,
            list?.let { l ->
                PLAIN_STRING.findAll(l.groupValues[1]).map { it.groupValues[1] }.toList()
            },
        )
    }

private val INDICATOR_ORDER_CALLS = setOf("dataInit", "dataIndicator")

/** The codes of what Kotlin writes into a string, whatever the client then makes of it. */
private val KOTLIN_OWN =
    setOf(
        IssueCode.KOTLIN_INTERPOLATION,
        IssueCode.UNESCAPED_INTERPOLATION,
        IssueCode.UNSAFE_INTERPOLATION,
        IssueCode.INTERPOLATE_TEMPLATE,
        IssueCode.INTERPOLATE_PLACEHOLDERS,
    )

/** The words every message of a refusal of the DSL's as the page renders has. */
private val STREAMLORD_REFUSES = regex("""Streamlord (?:refuses|throws)|as the page renders""")

/** kotlinx.html's own way to write any attribute: `attributes["data-on:click"] = "..."`. */
private val ATTRIBUTES_KEY = regex("""\battributes\s*\[\s*"(data-[^"\\]*)"\s*]""")
private val ATTRIBUTES_INDEX = regex("""\battributes\s*\[\s*"(data-[^"\\]*)"\s*]\s*=\s*""")

/** What may follow the value of an assignment: the end of the statement, nothing more of an expression. */
private val STATEMENT_END = regex("""[ \t]*(?:[;}\n\r]|$)""")

/** A declaration of the name that follows, `val payloadExpr = `, which sets no option. */
private val DECLARED_BEFORE = regex("""\bva[lr]\s+\z""")

/** The `to` before a value in a map an option is set to, `headerExprs = mapOf(...)`. */
private val TO_BEFORE = regex("""\bto\s*\z""")

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

    /** The SSE calls and answers whose arguments core judges, of those [allSiteNames] leaves out. */
    private val eventOnlyNames: Set<String> = eventCallNames - allSiteNames

    /**
     * The calls streamlord-html alone defines, which are somebody else's in a project without it:
     * the attributes' helpers, the expression call sites and the expression helpers.
     */
    internal val dslNames: Set<String> =
        catalog.attributes.flatMap { it.kotlin }.toSet() + catalog.expressionCallSites.keys + catalog.signalNameHelpers.keys +
            catalog.expressionOptions.keys + "statements"

    /** Whether the call named [name] at [nameStart] is not Streamlord's, as [opts] says: then its strings are free strings. */
    private fun foreignCalls(opts: Reading): (name: String, nameStart: Int) -> Boolean {
        val modules = opts.modules
        // Every call the analysis looks for is core's or the DSL's, so without core none is Streamlord's.
        return { name, at -> !modules.core || modules.foreign(name, at) || (modules.htmlDsl == false && name in dslNames) }
    }

    /** `payloadExpr = ` and the other options that take an expression, set: what [expressionOptions] looks for. */
    private val optionAssigned: Regex? =
        catalog.expressionOptions.keys
            .takeIf { it.isNotEmpty() }
            ?.let { regex("""\b(""" + it.joinToString("|") + """)\s*=(?!=)\s*""") }

    /** Names of all DSL functions the analysis recognises in Kotlin source. */
    internal val callSiteNames: Set<String> get() = allSiteNames

    private val lexCacheCap = 4 * 1024 * 1024

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

    @Volatile
    private var lastNested: Pair<KotlinLex, List<KotlinString>>? = null

    /** Every literal of [src], those in a template's code among them, each before the ones inside it: read once per text. */
    internal fun nested(src: String): List<KotlinString> {
        val lex = lex(src)
        lastNested?.let { if (it.first === lex) return it.second }
        return withNested(lex.strings).also { lastNested = lex to it }
    }

    /** The issues of a Kotlin file of which nothing more is known than its text. */
    public fun analyzeKotlin(src: String): List<Issue> = analyzeKotlin(Source(src, SourceLanguage.KOTLIN))

    public fun analyzeKotlin(source: Source): List<Issue> = analyzeKotlinSites(source).flatMap { it.issues }

    /** The issues of a Kotlin file grouped by the string or call they belong to, so a host can veto a call it knows is not Streamlord's. */
    public fun analyzeKotlinSites(source: Source): List<SiteIssues> = kotlinSites(Reading(source, settle(source)))

    /**
     * The values of the Kotlin file [source] the analysis asks the editor about through [Source.valueAnswer], in the order it
     * asks: an editor answers for each of these and for nothing else, by the very range it is asked by. An answer that
     * places a declaration in the file ([ValueAnswer.declaredAt]) leads on to what that declaration is set to, so an
     * editor that gave one asks again with its answers in [source], until nothing new is listed.
     * The file is read as [analyzeKotlinSites] reads it, and costs as much. A call [Source.foreign] names is read as that
     * reading reads it, so its string may ask what it did not ask as Streamlord's.
     */
    public fun valueQuestions(source: Source): List<ValueQuestion> {
        val asked = LinkedHashMap<Pair<Int, Int>, ValueQuestion>()
        kotlinSites(Reading(source, settle(source), asked))
        return asked.values.toList()
    }

    private fun kotlinSites(opts: Reading): List<SiteIssues> {
        val source = opts.source
        val src = source.text
        val out = ArrayList<SiteIssues>()
        val claimed = HashSet<Int>()
        val lex = lex(src)
        val facts = fileFacts(src, lex)
        val foreign = foreignCalls(opts)
        val hyphenated = hyphenatedSignals(src, SourceLanguage.KOTLIN, opts.prefix, foreign, lex)
        val dsl = opts.modules.htmlDsl
        val eventCalls = EventCalls(src, lex.mask)
        // At a DSL call the DSL is there; an import from elsewhere may still take a helper's name.
        val siteHelpers by lazy(LazyThreadSafetyMode.NONE) { unshadowed(src, EXPRESSION_HELPERS) }
        for (site in findCallSites(src, allSiteNames, lex)) {
            // Somebody else's call: its strings are judged as any string handed to an unknown function.
            if (foreign(site.name, site.nameStart)) continue
            val issues = ArrayList<Issue>()
            catalog.expressionCallSites[site.name]?.let { issues += checkExpressionSite(site, it, src, lex, hyphenated, opts, siteHelpers) }
            val html = catalog.htmlCallSites[site.name]?.let { checkHtmlSite(site, it, opts, src, lex, hyphenated, eventCalls) }
            html?.let { issues += it.issues }
            // An answer sends its script as it is, and the client sets it as the element's text: only a stream escapes a script end.
            if (catalog.eventCalls[site.name]?.response !=
                true
            ) {
                catalog.scriptCallSites[site.name]?.let { issues += checkScriptSite(site, it) }
            }
            if (site.name in eventCallNames &&
                site.name !in htmlSiteNames
            ) {
                issues += eventArgumentIssues(site, null, eventCalls, opts.prefix)
            }
            issues += checkKeyCaseSite(site, src, lex, opts.prefix)
            issues += modifierLambdaIssues(site, src, lex)
            // The literals of a `+` chain handed to the site are judged there, as one string; the arguments it does not read are free.
            for (judged in judgedStrings(site, src, lex)) {
                for (x in lex.stringsIn(judged.start, judged.end)) claimed += x.start
            }
            if (issues.isNotEmpty() || html?.rewrite != null) out += SiteIssues(site, null, issues, html?.rewrite)
        }
        // The calls whose arguments core alone judges, those the loop above has not read.
        for (site in findCallSites(src, eventOnlyNames, lex)) {
            if (foreign(site.name, site.nameStart)) continue
            val issues = eventArgumentIssues(site, null, eventCalls, opts.prefix)
            if (issues.isNotEmpty()) out += SiteIssues(site, null, issues)
        }
        // An interpolate the file takes from elsewhere is not core's, whose format this reads; nor one it
        // declares, since the text cannot tell whether the call fits it.
        val otherInterpolate = shadowsCore(src, "interpolate")
        // The block form: every literal in the braces is markup interpolate reads, its marked values taken where they stand.
        val blocks =
            if (otherInterpolate) {
                emptyList()
            } else {
                interpolateBlocks(
                    src,
                    facts.code,
                ).filter { !foreign(it.site.name, it.site.nameStart) }
            }
        // The placeholder form's calls, in a template's code too: `"<p>${interpolate("<b>%s</b>", name)}</p>"`.
        val placeholderSites =
            if (otherInterpolate) {
                emptyList()
            } else {
                findCallSites(
                    src,
                    INTERPOLATE,
                    KotlinLex(lex.strings, facts.code, lex.comments),
                )
            }
        val htmls = if (blocks.isEmpty()) emptyList() else placeholderHtmls(src, facts.code, placeholderSites, blocks)
        for (site in placeholderSites) {
            if (foreign(site.name, site.nameStart)) continue
            val format = interpolateFormat(site, src, facts.code) ?: continue
            claimed += format.second.start
            for (x in format.second.literals) claimed += x.start
            val inHtml = htmls.firstOrNull { it.site === site }?.let { MarksInHtml(it.givenAt != null) }
            val issues = checkInterpolateSite(site, format.first, format.second, src, opts, hyphenated, inHtml)
            if (issues.isNotEmpty()) out += SiteIssues(site, null, issues)
        }
        out += indicatorOrderIssues(src, lex, foreign)
        for (site in findCallSites(src, nonceCalls, lex)) {
            if (foreign(site.name, site.nameStart)) continue
            val issues = checkNonceSite(site)
            if (issues.isNotEmpty()) out += SiteIssues(site, null, issues)
        }
        // The helpers the file can call, read from its imports once for all its calls and assignments.
        val helpers = dslHelpers(src, EXPRESSION_HELPERS, dsl)
        val hyphenSites = signalNameHyphenSites(src, lex, helpers, allSiteNames + "statements", foreign)
        for ((site, issues) in hyphenSites) {
            out += SiteIssues(site, null, issues)
        }
        for (m in ATTRIBUTES_INDEX.findAll(src)) {
            if (!lex.mask[m.range.first]) continue
            val value = attributesIndexValue(src, m, lex)
            value?.let { claimed += it.start }
            val issues = checkAttributesIndex(m, value, opts, src, hyphenated, helpers)
            if (issues.isNotEmpty()) out += SiteIssues(null, value, issues)
        }
        for (o in expressionOptions(src, lex)) {
            if (foreign(o.name, o.nameStart)) continue
            claimed += o.value.start
            // FetchOptions writes each expression option in parentheses.
            val issues = expressionIssues(o.value, src, opts, hyphenated, siteHelpers, "the expression of ${o.name}", null, null, o.name)
            if (issues.isNotEmpty()) out += SiteIssues(null, o.value, issues)
        }
        // A literal joined to values with `+` is read as one string with a placeholder for each value.
        val chained = HashSet<Int>()
        val topLevel = lex.strings.mapTo(HashSet()) { it.start }
        for (s in withNested(lex.strings)) {
            if (s.start in claimed || s.start in chained || s.unterminated) continue
            // A literal inside a template is joined to its neighbours in the template's code.
            val chain = chainFrom(src, s, if (s.start in topLevel) lex.mask else facts.code)
            val whole = chain ?: s
            // The chain's own literals are read with it; one in a value it joins, or in a template, is a string of its own.
            if (chain != null) for (l in chain.literals) chained += l.start
            // The innermost block a literal stands in reads it, where it is markup or marks a value: its call is the
            // issues' site, so a host that resolves calls can say it is somebody else's.
            val trusted = facts.trustedAt(s.start)
            val innermost = blocks.lastOrNull { s.start in it.braces }
            val html = innermost?.let { b -> htmls.firstOrNull { it.block === b && it.holds(trusted) } }
            val block = innermost?.takeUnless { html == null && inMarkedValue(src, lex.strings, s, it.braces, trusted) }
            if (block != null && (isHtmlString(src, s) || '<' in whole.text || whole.interpolations.any { markedValue(it) != null })) {
                val issues =
                    checkInterpolateBlock(
                        whole,
                        block.prefixes,
                        src.substring(block.braces.first, block.braces.last),
                        src,
                        opts,
                        hyphenated,
                        facts.inBlockValue(s.start, block.braces),
                        // A Trusted's literal of marks alone the call judges where it lands, its marks among its own values.
                        html?.let { MarksInHtml(it.givenAt != null, judgedHere = s.start !in it.inPlace) },
                        held = { ip -> htmls.firstOrNull { it.givenAt == ip.start }?.let { marksHeld(src, opts, it) } },
                    )
                if (issues.isNotEmpty()) out += SiteIssues(block.site, whole, issues)
                continue
            }
            if (!isHtmlString(src, s) && !(chain != null && textOpensWithTag(chain.text))) continue
            val judged = checkFreeHtmlString(whole, opts, src, hyphenated, s.start !in topLevel)
            if (judged.issues.isNotEmpty() || judged.rewrite != null) out += SiteIssues(null, whole, judged.issues, judged.rewrite)
        }
        val ignored = dslIgnored(src, lex, foreign, opts.prefix)
        return out.mapNotNull { s ->
            val kept = s.issues.filter { !ignored(it.start) || ownIssue(it) }
            if (kept.size == s.issues.size) s else s.copy(issues = kept).takeIf { kept.isNotEmpty() || it.rewrite != null }
        }
    }

    /**
     * What Kotlin and Streamlord do with a call or a string, which `dataIgnore` does not change: a
     * template Kotlin writes into the text, and what the DSL refuses as the page renders.
     */
    private fun ownIssue(issue: Issue): Boolean = issue.code in KOTLIN_OWN || STREAMLORD_REFUSES.containsMatchIn(issue.message)

    /**
     * The strings [site] judges: the markup, the expression, the script or the selector it is handed,
     * a `+` chain whole, and the key or the name of a helper that takes only that.
     */
    private fun judgedStrings(
        site: CallSite,
        src: String,
        lex: KotlinLex,
    ): List<KotlinString> {
        val out = ArrayList<KotlinString>(1)
        catalog.htmlCallSites[site.name]?.let { spec -> markupString(site, spec, src, lex)?.let { out += it } }
        catalog.expressionCallSites[site.name]?.let { spec ->
            if (!(spec.onlyIfStringArgs && site.args.any { it.named == null && it.string == null })) {
                expressionString(site, spec, src, lex)?.let { out += it }
                out += site.moreStrings(spec)
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

    /** The DSL helper of a backend action, `get("/x")`, which a `dataInit` handed it sends at once. */
    private val backendHelper by lazy(LazyThreadSafetyMode.NONE) {
        regex("""\s*(?:""" + catalog.actions.filter { it.kind == "backend" }.joinToString("|") { Regex.escape(it.kotlin) } + """)\s*\(""")
    }

    /**
     * `dataIndicator` called after a `dataInit` that sends at once, in the same element's block:
     * the DSL writes the attributes in the order of the calls, and the indicator then comes too
     * late, as the indicator-order check says of HTML. A dataInit with a lambda of modifiers may
     * wait, and is left alone.
     */
    private fun indicatorOrderIssues(
        src: String,
        lex: KotlinLex,
        foreign: (name: String, nameStart: Int) -> Boolean,
    ): List<SiteIssues> {
        val sites = findCallSites(src, INDICATOR_ORDER_CALLS, lex).filter { !foreign(it.name, it.nameStart) }
        if (sites.none { it.name == "dataInit" } || sites.none { it.name == "dataIndicator" }) return emptyList()

        val blocks = lex.blocks(src)

        fun blockOf(at: Int) = blocks.around(at)

        fun sendsAtOnce(init: CallSite): Boolean {
            if (init.trailingLambda) return false
            val arg = init.positional.firstOrNull() ?: init.named("expression") ?: return false
            val s = arg.string
            if (s != null) return !s.unterminated && s.interpolations.isEmpty() && markup.sendsAtOnce(s.text)
            return backendHelper.matchAt(arg.text, 0) != null
        }
        val sending = sites.filter { it.name == "dataInit" && sendsAtOnce(it) }.map { blockOf(it.nameStart) to it.nameStart }
        return sites
            .filter { it.name == "dataIndicator" }
            .filter { ind -> blockOf(ind.nameStart).let { b -> b != null && sending.any { it.first == b && it.second < ind.nameStart } } }
            .map { ind ->
                SiteIssues(
                    ind,
                    null,
                    listOf(
                        Issue(
                            ind.nameStart,
                            ind.nameStart + ind.name.length,
                            "dataIndicator comes after dataInit, which starts its request as Datastar applies it, " +
                                "before the indicator exists, so the signal stays false. Call dataIndicator first.",
                            Severity.WARNING,
                            IssueCode.INDICATOR_ORDER,
                            Docs.attribute("indicator"),
                        ),
                    ),
                )
            }
    }

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
                code = if (empty) IssueCode.MISSING_NONCE else IssueCode.INVALID_NONCE,
                link = Docs.attribute(catalog.attributesByName.getValue("nonce")),
            ),
        )
    }

    /**
     * Each `attributes["data-..."] = "..."` in [src], kotlinx.html's own way to write an attribute, for completion and colouring:
     * the name, where it starts, and the literal that follows the `=`, unterminated while it is typed.
     */
    internal fun attributesIndexes(src: String): List<AttributesIndex> {
        lastIndexes?.takeIf { it.first == src }?.let { return it.second }
        return readAttributesIndexes(src).also { lastIndexes = src to it }
    }

    // Completion and hover ask for the same file's several times in one question.
    @Volatile
    private var lastIndexes: Pair<String, List<AttributesIndex>>? = null

    private fun readAttributesIndexes(src: String): List<AttributesIndex> {
        val lex = lex(src)
        val out = ArrayList<AttributesIndex>()
        for (m in ATTRIBUTES_KEY.findAll(src)) {
            if (!lex.mask[m.range.first]) continue
            val nameStart = m.range.first + m.value.indexOf('"') + 1
            // While the assignment is still being typed the key stands alone, with no value.
            val assigned =
                ATTRIBUTES_INDEX.matchAt(src, m.range.first) ?: run {
                    out += AttributesIndex(m.groupValues[1], nameStart, null, false)
                    continue
                }
            val value = lex.stringAt(assigned.range.last + 2)?.takeIf { it.start == assigned.range.last + 1 }
            out += AttributesIndex(m.groupValues[1], nameStart, value, attributesIndexValue(src, assigned, lex) != null)
        }
        return out
    }

    /**
     * Each option of a backend action's FetchOptions in [src] set to an expression, for the checks,
     * completion and colouring: `payloadExpr = "..."`, a literal that is the whole value, unterminated
     * while it is typed, and each value of a map option, `headerExprs = mapOf("X-Id" to "...")`.
     */
    internal fun expressionOptions(
        src: String,
        lex: KotlinLex = lex(src),
    ): List<ExpressionOption> {
        val assigned = optionAssigned ?: return emptyList()
        val out = ArrayList<ExpressionOption>()
        val maps by lazy(LazyThreadSafetyMode.NONE) { findCallSites(src, MAP_CALLS, lex) }
        val lambdas by lazy(LazyThreadSafetyMode.NONE) { optionLambdas(src, lex) }
        for (m in assigned.findAll(src)) {
            val at = m.range.first
            if (!lex.mask[at] || DECLARED_BEFORE.containsMatchIn(src.substring(maxOf(0, at - 16), at))) continue
            // Set in the lambda of a backend action's options, and on nothing else: `form.payloadExpr` is another's.
            if (src.getOrNull(codeBefore(src, lex.mask, at)) == '.' || lambdas.none { at in it }) continue
            val name = m.groupValues[1]
            val after = m.range.last + 1
            if ("map" !in catalog.expressionOptions.getValue(name)) {
                val value = lex.stringAt(after + 1)?.takeIf { it.start == after } ?: continue
                if (value.unterminated || STATEMENT_END.matchesAt(src, value.end)) out += ExpressionOption(name, at, value)
                continue
            }
            val call = maps.firstOrNull { it.nameStart == after } ?: continue
            for (arg in call.args) {
                val value = lex.stringsIn(arg.start, arg.end).lastOrNull() ?: continue
                if (uncommented(src, arg).end == value.end && TO_BEFORE.containsMatchIn(src.substring(arg.start, value.start))) {
                    out += ExpressionOption(name, at, value)
                }
            }
        }
        return out
    }

    /**
     * The braces of each lambda that sets a backend action's options: the one after `post("/x")`, or
     * the one passed as an argument, `options = { ... }`, of the DSL's backend helpers.
     */
    private fun optionLambdas(
        src: String,
        lex: KotlinLex,
    ): List<IntRange> {
        val out = ArrayList<IntRange>()
        for (site in findCallSites(src, backendHelpers, lex)) {
            for (arg in site.args) if (src.getOrNull(arg.start) == '{') out += arg.start until arg.end
            trailingLambda(site, src, lex)?.let { out += it }
        }
        return out
    }

    /** The braces of [site]'s trailing lambda, matched in code by braces alone, to the end of the file where it never closes. */
    private fun trailingLambda(
        site: CallSite,
        src: String,
        lex: KotlinLex,
    ): IntRange? {
        if (!site.trailingLambda) return null
        val open = src.indexOf('{', site.closeParen + 1).takeIf { it >= 0 } ?: return null
        return open..matching(src, lex.mask, open, alone = true).let { if (it < 0) src.length - 1 else it }
    }

    /**
     * Whether [s] stands in a template of a literal of the block at [braces] that marks its value with `+`: it is that value's
     * then. Not where it stands in [trusted], the argument of a `Trusted(...)` the marked value gives as its markup: the
     * Trusted itself or a value it may result in, or one handed to the placeholder form, `interpolate("%s", Trusted(...))`,
     * whose Html holds its text. The block reads such markup as its own, marks and all.
     */
    private fun inMarkedValue(
        src: String,
        strings: List<KotlinString>,
        s: KotlinString,
        braces: IntRange,
        trusted: IntRange?,
    ): Boolean {
        val argument = trusted?.let { src.substring(it.first, it.last + 1).trim() }

        // Whether [value] gives the Trusted at [argument] as markup, itself or handed to the placeholder form; not through a
        // second placeholder form, which may escape the Html where it lands, marks and all.
        fun givesTrusted(
            value: String,
            throughInterpolate: Boolean = true,
        ): Boolean {
            fun handedOn(argument: String) = givesTrusted(argument, throughInterpolate = false)
            return templateValues(value).any { v ->
                val call = lastCall(v.text)
                when {
                    call == null || call.receiver != null || call.lambda != null -> false
                    coreCall(src, v.text, "Trusted") -> call.args.replace(NAMED_HTML, "").trim() == argument
                    throughInterpolate && coreCall(src, v.text, "interpolate") -> splitTop(call.args, ",").drop(1).any(::handedOn)
                    else -> false
                }
            }
        }
        return withNested(strings).any { outer ->
            outer.start in braces && outer.start < s.start && s.end <= outer.end &&
                outer.interpolations.any { ip ->
                    val marked = markedValue(ip)
                    ip.start <= s.start && s.end <= ip.end && marked != null && (argument == null || !givesTrusted(marked))
                }
        }
    }

    /** Whether the marks [html]'s markup holds are a value of the block: true where one is no number, false where each may be, null for none. */
    private fun marksHeld(
        src: String,
        opts: Reading,
        html: PlaceholderHtml,
    ): Boolean? {
        var held: Boolean? = null
        for (m in html.marks) {
            when (markKind(src, opts, m, markedValue(m)!!, m.start)) {
                ArgKind.NUMBER -> {}

                null -> {
                    if (held == null) held = false
                }

                else -> {
                    return !html.quotes
                }
            }
        }
        return held
    }

    private fun placeholderHtmls(
        src: String,
        code: BooleanArray,
        sites: List<CallSite>,
        blocks: List<InterpolateBlock>,
    ): List<PlaceholderHtml> {
        val calls = sites.filter { !it.trailingLambda }
        val strings = nested(src)
        val templates = strings.flatMap { it.interpolations }
        return calls.mapNotNull { site ->
            val block = blocks.lastOrNull { site.nameStart in it.braces } ?: return@mapNotNull null
            val handedOn =
                calls.any {
                    it !== site && it.openParen > block.braces.first && it.openParen < site.nameStart && site.closeParen < it.closeParen
                }
            val call = src.substring(site.nameStart, site.closeParen + 1)
            val format = interpolateFormat(site, src, code)?.second
            val trusted = site.positional.filter { coreCall(src, it.text, "Trusted") }.map { it.start until it.end }
            // Its markup's marks, and those of a call handed to it, whose Html its own then holds.
            val markup =
                strings.filter { l ->
                    l.start > site.openParen && l.end <= site.closeParen &&
                        (
                            format?.let { l.start >= it.start && l.end <= it.end } == true ||
                                trusted.any { l.start > it.first && l.end <= it.last + 1 } ||
                                calls.any {
                                    it !== site && it.openParen > site.openParen && it.closeParen < site.closeParen &&
                                        l.start > it.openParen &&
                                        l.end <= it.closeParen
                                }
                        )
                }
            PlaceholderHtml(
                site,
                block,
                format?.let { it.start until it.end },
                trusted,
                markup.flatMap { l -> l.interpolations.filter { markedValue(it) != null } },
                // The whole of a template of the block's markup, marked or not, rather than kept, measured or handed elsewhere.
                templates.firstOrNull { site.nameStart in it.start until it.end && (markedValue(it) ?: it.text.trim()) == call }?.start,
                handedOn,
                markup.any { l -> '"' in l.text || '\'' in l.text },
                // Read only where the call is judged, which a format the text cannot read rules out.
                if (format == null) emptySet() else site.positional.mapNotNullTo(HashSet()) { markedTrustedLiteral(src, it)?.start },
            )
        }
    }

    /** The DSL's backend helpers, `get` and `getExpr` and the rest, which take a lambda of FetchOptions. */
    private val backendHelpers: Set<String> =
        catalog.actions
            .filter { it.kind == "backend" && it.kotlin.isNotEmpty() }
            .flatMap { listOf(it.kotlin, it.kotlin + "Expr") }
            .toSet()

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
        prefix: String,
        foreign: (String, Int) -> Boolean = { _, _ -> false },
        lex: KotlinLex? = null,
    ): Set<String> =
        collectSignalDefinitions(src, language, foreign, lex, prefix).filter { '-' in it }.mapTo(HashSet()) { it.removePrefix(REFUSED) }

    /**
     * `attributes["data-on:click"] = "$count++"` judged as the attribute it writes: the name as in
     * markup, the value as the expression a DSL call takes, with Kotlin's interpolation trap. A
     * value that is not a literal is taken for a template, so only the name is judged.
     */
    private fun checkAttributesIndex(
        m: MatchResult,
        value: KotlinString?,
        opts: Reading,
        src: String,
        hyphenated: Set<String>,
        helpers: Set<String>,
    ): List<Issue> {
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
            issues += expressionTemplates(value, src, opts, name, helpers)
        }
        if (value != null && kind == ValueKind.SIGNAL) issues += signalNameTemplates(value, src, opts, name)

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
            if (!inName && value == null) continue
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
    private fun signalNameTemplates(
        s: KotlinString,
        src: String,
        opts: Reading,
        attribute: String,
    ): List<Issue> {
        val out = ArrayList<Issue>()
        for (ip in s.interpolations) {
            // A constant or a number the author wrote is the name, as it is in markup.
            val fact = factOf(s, src, opts, ip).plain()
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
                        code = IssueCode.KOTLIN_INTERPOLATION,
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
     * The string literal at an offset when it holds HTML: the argument of an HTML call site, the
     * format of core's `interpolate`, however it opens, or a free-standing literal that looks like
     * HTML (see [isHtmlString]). Completion, hover and colours use it to give Kotlin the HTML side.
     */
    internal fun htmlStringAt(
        src: String,
        offset: Int,
    ): KotlinString? {
        val lex = lex(src)
        return lex.stringAt(offset)?.takeIf(htmlStrings(src, lex))
    }

    /** Whether a string literal of [src] holds HTML, as [htmlStringAt] has it, with the calls of the file found once for them all. */
    internal fun htmlStrings(
        src: String,
        lex: KotlinLex,
    ): (KotlinString) -> Boolean {
        val formats by lazy(LazyThreadSafetyMode.NONE) {
            if (shadowsCore(src, "interpolate")) emptyList() else outermost(findCallSites(src, INTERPOLATE, lex))
        }
        val calls by lazy(LazyThreadSafetyMode.NONE) { outermost(findCallSites(src, htmlSiteNames, lex)) }
        return { s ->
            isHtmlString(src, s) ||
                around(formats, s)?.let { interpolateFormat(it, src) }?.second?.start == s.start ||
                around(calls, s)?.let { site -> catalog.htmlCallSites[site.name]?.let { markupString(site, it) } }?.start == s.start
        }
    }

    /** Those of [sites], in the order of the file, that none before them holds. */
    private fun outermost(sites: List<CallSite>): List<CallSite> {
        var end = -1
        return sites.filter { site -> (site.openParen > end).also { if (it) end = site.closeParen } }
    }

    /** The one of [outermost] calls that holds [s] between its parentheses. */
    private fun around(
        outermost: List<CallSite>,
        s: KotlinString,
    ): CallSite? {
        val before = outermost.binarySearch { if (it.openParen < s.start) -1 else 1 }.let { -it - 2 }
        return outermost.getOrNull(before)?.takeIf { s.end <= it.closeParen + 1 }
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
        lex: KotlinLex? = null,
    ): KotlinString? {
        if (site.trailingLambda) return null
        val s = site.selectString(spec)
        if (s != null || src == null || lex == null) return s
        return chainArg(site, spec, src, lex)
    }

    /** The argument a call site takes its string from, when it is a `+` chain that starts with a literal: `patchElements("<p>" + name + "</p>")`. */
    private fun chainArg(
        site: CallSite,
        spec: CallSiteSpec,
        src: String,
        lex: KotlinLex,
    ): KotlinString? {
        val positional = site.positional
        val arg =
            spec.named?.let { site.named(it) }
                ?: (if (spec.arg == null) positional.lastOrNull() else positional.getOrNull(spec.arg))
                ?: return null
        // The strings come in the order of the file, so the one the argument starts with is looked up, not searched for.
        val first = lex.strings.getOrNull(lex.strings.binarySearchBy(arg.start) { it.start }) ?: return null
        return chainFrom(src, first, lex.mask)?.takeIf { it.end == arg.end }
    }

    /** Analyse an HTML document (a template): attributes and expressions only, no id or completeness rules. */
    public fun analyzeHtml(src: String): List<Issue> = analyzeHtml(Source(src, SourceLanguage.HTML))

    /** As [analyzeHtml], for markup the host knows more of: its prefix, and how it names the prefix setting. */
    public fun analyzeHtml(source: Source): List<Issue> =
        analyzeHtml(source.text, Reading(source, settle(source)), hyphenatedSignals(source.text, SourceLanguage.HTML, source.prefix))

    internal fun analyzeHtml(
        src: String,
        opts: Reading,
        hyphenated: Set<String>,
        templates: Boolean = true,
    ): List<Issue> {
        val issues = ArrayList<Issue>()
        for (tag in appliedTags(tokenize(src, templates).tags, opts.prefix)) {
            issues +=
                markup.validateAttributes(
                    tag,
                    opts.prefix,
                    src.substring(tag.start, tag.end),
                    hyphenated,
                    prefixSetting = opts.prefixSetting,
                    movable = templates,
                    engine = opts.source.engine,
                )
        }
        return issues
    }

    /**
     * Every signal the Datastar expressions of a file read. In markup, those of its attributes; in
     * Kotlin, those in the expression handed to a DSL call, in the name handed to a helper such as
     * `signal("count")`, and in the attributes of HTML strings, handed to a call or free-standing. A
     * Kotlin template (`$count` in a plain literal) is not a signal and is left out; the
     * interpolation check speaks for it.
     */
    public fun signalReferences(source: Source): List<SignalReference> {
        val src = source.text
        if (source.language == SourceLanguage.SETTINGS) return emptyList()
        if (source.language != SourceLanguage.KOTLIN) return markupSignalReferences(src, catalog, source.prefix, engine = source.engine)
        val out = ArrayList<SignalReference>()
        val claimed = HashSet<Int>()
        val lex = lex(src)
        val opts = Reading(source, settle(source))
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
            for (judged in judgedStrings(site, src, lex)) {
                if (site.args.any { it.string?.start == judged.start && it.string.end == judged.end }) claimed += judged.start
            }
            catalog.expressionCallSites[site.name]?.let { spec ->
                if (spec.onlyIfStringArgs && site.args.any { it.named == null && it.string == null }) return@let
                for (s in site.expressionStrings(spec)) if (!s.unterminated) add(s, expressionSignalReferences(s.text), site)
            }
            catalog.htmlCallSites[site.name]?.let { spec ->
                val s = markupString(site, spec)?.takeUnless { it.unterminated } ?: return@let
                add(s, markupSignalReferences(s.text, catalog, opts.prefix, templates = false), site)
            }
        }
        // A helper in a template's code is a call too, `"${signal("shown")}"`, as the readings of signals take it.
        val code by lazy(LazyThreadSafetyMode.NONE) { codeMask(src, lex).first }
        val helpers =
            dslHelpers(src, HELPER_READ_CALLS, opts.modules.htmlDsl)
                .takeIf { it.isNotEmpty() }
                ?.let { findCallSites(src, it, KotlinLex(lex.strings, code, lex.comments)) }
                ?: emptyList()
        for (site in helpers) {
            // A call on a receiver, `filter.not("x")`, is not the top-level helper.
            if (src.getOrNull(codeBefore(src, code, site.nameStart)) == '.' || foreign(site.name, site.nameStart)) continue
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
        for (o in expressionOptions(src, lex)) {
            if (foreign(o.name, o.nameStart)) continue
            claimed += o.value.start
            if (!o.value.unterminated) add(o.value, expressionSignalReferences(o.value.text), null)
        }
        for (s in lex.strings) {
            if (s.start in claimed || s.unterminated || !isHtmlString(src, s)) continue
            add(s, markupSignalReferences(s.text, catalog, opts.prefix, templates = false), null)
        }
        val ignored = dslIgnored(src, lex, foreign, opts.prefix)
        return out.filter { !ignored(it.start) }
    }

    /** The markup rules of one HTML string handed to a patch, with the id rule on or off. */
    public fun validateMarkup(
        html: String,
        opts: MarkupOptions,
    ): List<Issue> = markup.validateMarkup(html, opts)

    internal fun validateExpression(text: String): List<Issue> = expressions.validate(text)

    internal fun mapIssues(
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
        lex: KotlinLex,
    ): KotlinString? = site.selectString(spec) ?: chainArg(site, spec, src, lex)

    private fun checkExpressionSite(
        site: CallSite,
        spec: CallSiteSpec,
        src: String,
        lex: KotlinLex,
        hyphenated: Set<String>,
        opts: Reading,
        helpers: Set<String>,
    ): List<Issue> {
        if (spec.onlyIfStringArgs && site.args.any { it.named == null && it.string == null }) return emptyList()
        val attribute = catalog.attributesByKotlin[site.name]
        val named = attribute?.let { opts.prefix + it.name } ?: "the expression of ${site.name}"
        val strings = listOfNotNull(expressionString(site, spec, src, lex)) + site.moreStrings(spec)
        val oneValue = site.name.takeIf { spec.oneValue }
        return strings.flatMap { s ->
            // An attribute's expression passes ExpressionGuard as the page renders, which refuses an empty one.
            if (attribute != null && !s.unterminated && s.interpolations.isEmpty() && s.text.isBlank()) {
                listOf(
                    Issue(
                        s.start,
                        s.end,
                        "${site.name} throws as the page renders: Streamlord refuses an empty expression.",
                        Severity.ERROR,
                        IssueCode.EMPTY_EXPRESSION,
                        Docs.attribute(attribute),
                    ),
                )
            } else {
                expressionIssues(s, src, opts, hyphenated, helpers, named, attribute, dslEvent(site), oneValue)
            }
        }
    }

    /**
     * The first comma at the top of [s], which [function] writes in parentheses as one value: there it
     * is JavaScript's comma operator, and the value is what follows it. `postExpr("'/a', {retry: 'never'}")`
     * sends to no address.
     */
    private fun commaOperatorIssue(
        s: KotlinString,
        function: String,
    ): Issue? {
        val at = Brackets(s.text, jsRegions(s.text)).topCommas.firstOrNull() ?: return null
        val backend = catalog.actions.any { it.kind == "backend" && it.kotlin + "Expr" == function }
        val r = s.toSource(at, at + 1)
        return Issue(
            r.first,
            r.last + 1,
            "$function writes the expression in parentheses, as one value, so JavaScript reads this comma as its comma " +
                "operator and keeps only what follows it." +
                if (backend) " The options go in the lambda after the call." else "",
            Severity.WARNING,
            IssueCode.ONE_VALUE,
            Docs.EXPRESSIONS,
        )
    }

    /** The issues of [s], a literal Datastar runs as an expression: its Kotlin templates, and the expression itself. */
    private fun expressionIssues(
        s: KotlinString,
        src: String,
        opts: Reading,
        hyphenated: Set<String>,
        helpers: Set<String>,
        named: String,
        attribute: AttributeSpec?,
        event: String?,
        oneValue: String? = null,
    ): List<Issue> {
        if (s.unterminated) return emptyList()
        // An import from elsewhere, Hamcrest's not, takes a helper's name here too.
        val issues = ArrayList(expressionTemplates(s, src, opts, named, helpers))
        oneValue?.let { commaOperatorIssue(s, it) }?.let { issues += it }
        val judged =
            expressions.validate(
                s.text,
                hyphenatedSignals = hyphenated,
                attribute = attribute,
                event = event,
                prefix = opts.prefix,
            )
        val simple = s.interpolations.filter { it.kind == InterpolationKind.SIMPLE }.mapTo(HashSet()) { it.decodedStart }
        issues += mapIssues(s, src, aroundPlaceholders(s.text, judged) { it in simple })
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
    private fun expressionTemplates(
        s: KotlinString,
        src: String,
        opts: Reading,
        attribute: String,
        helpers: Set<String>? = null,
    ): List<Issue> {
        val trapOf = traps(s, src, helpers)
        val out = ArrayList<Issue>()
        val quotes = QuoteScan(s.text)
        for (ip in s.interpolations) {
            val trap = trapOf(ip)
            if (trap != null && quotes.at(ip.decodedStart) == null) {
                out += trap
                continue
            }
            val fact = factOf(s, src, opts, ip).plain()
            // Kotlin writes the digits of an unsigned number; it is interpolate that would refuse it.
            if (fact.writesItself()) continue
            out += datastarIssue(ip, attribute)
        }
        return out
    }

    private fun checkHtmlSite(
        site: CallSite,
        spec: CallSiteSpec,
        opts: Reading,
        src: String,
        lex: KotlinLex,
        hyphenated: Set<String>,
        eventCalls: EventCalls,
    ): Judged {
        val s = markupString(site, spec, src, lex)
        if (s == null && !site.trailingLambda) return Judged()
        if (s != null && s.unterminated) return Judged()
        // In every signature the selector and the mode follow the elements, so they may be given by
        // position. A trailing lambda holds the elements, so the selector comes first.
        val elements =
            if (s == null) -1 else (spec.arg ?: 0).takeIf { spec.named == null || site.named(spec.named) == null }

        fun argument(
            name: String,
            place: Int,
        ) = site.named(name) ?: elements?.let { site.positional.getOrNull(it + place) }
        val selectorArg = argument("selector", 1)
        val selector = selectorArg?.text?.takeIf { it != "null" }
        val modeArg = argument("mode", 2)
        val mode =
            modeArg
                ?.let(::constantName)
                ?.lowercase()
                ?.let {
                    if (it ==
                        "default"
                    ) {
                        "outer"
                    } else {
                        it
                    }
                }?.takeIf { it in catalog.patchModes }
        val view = s?.let { markupView(src, it) } ?: ""
        val judged = s?.let { judgeValues(it, src, opts, view) }
        val issues = ArrayList(judged?.issues.orEmpty())
        val modeKnown = if (modeArg == null) ElementPatchMode.DEFAULT else mode?.let { ElementPatchMode.fromWire(it) }
        // A mode without a selector is judged on a receiver of any name, as it always was.
        val unselected = modeArg != null && selector == null && eventCalls.named(site)
        val needsSelector = modeKnown?.takeIf { unselected }?.let { modeWithoutSelector(site, eventCalls, it) }
        if (modeArg != null && needsSelector != null) {
            val assign = assignBefore(src, modeArg.start)
            val at = modeArg.start - (modeArg.named?.length ?: 0) - assign
            issues +=
                Issue(
                    start = site.nameStart,
                    end = site.openParen,
                    message = needsSelector,
                    severity = Severity.ERROR,
                    code = IssueCode.MODE_NEEDS_SELECTOR,
                    link = Docs.SSE,
                    fixes = if (modeArg.named != null) listOf(Fix("Add selector = \"\"", at, at, "selector = \"\", ")) else emptyList(),
                )
        }
        issues += eventArgumentIssues(site, selectorArg, eventCalls, opts.prefix)
        if (s != null && modeKnown != null && eventCalls.ours(site)) {
            // A namespace the code computes is not known here.
            val namespaceArg = argument("namespace", 3)
            val named = namespaceArg?.let(::constantName)?.let { n -> if (n == "DEFAULT") ElementNamespace.DEFAULT.name else n }
            val namespace =
                if (namespaceArg ==
                    null
                ) {
                    ElementNamespace.DEFAULT
                } else {
                    ElementNamespace.entries.firstOrNull { it.name == named }
                }
            elementsIssue(site, eventCalls, s, modeKnown, selectorArg, namespace)?.let { issues += it }
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
                            true,
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
        lex: KotlinLex,
        prefix: String,
    ): List<Issue> {
        val spec = catalog.attributesByKotlin[site.name] ?: return emptyList()
        if (site.name in valueNameHelpers) return valueNameIssues(site, spec)
        if (!spec.keyed || spec.keyCase == null) return emptyList()
        // Only helpers whose first parameter is a key: those whose expression is a later argument
        // (dataOn, dataSignals, dataClass, ...) and the two Pro helpers that take a key and no
        // expression. dataOnClick("expr") passes an expression, never a key.
        val expr = catalog.expressionCallSites[site.name]
        if (site.name in attributeNameHelpers) return attributeNameIssues(site, spec, src, lex)
        if (if (expr != null) expr.arg == 0 else site.name !in keyOnlyHelpers) return emptyList()
        val positional = site.positional
        val namedKey = KEY_PARAMETERS.firstNotNullOfOrNull { site.named(it) }?.string
        val key = namedKey ?: positional.firstOrNull()?.string ?: return emptyList()
        // With an expression helper, one positional string is the object form (dataClass("{...}")); the key form has two.
        val objectForm = namedKey == null && expr != null && positional.size < 2 && site.named("expression") == null
        if (key.interpolations.isNotEmpty() || objectForm) return emptyList()
        val name = key.text
        // dataSignals("{fooBar: 1}", ...) is the object form: an expression, not a key.
        if (OPENS_OBJECT_OR_ARRAY.containsMatchIn(name)) return emptyList()
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
        val explicit = if (named != null) CASE_VALUE.find(named) else CASE_IN_LAMBDA.find(modifiersLambda(site, src, lex))
        val explicitCase = explicit?.groupValues?.get(1)?.lowercase()
        val capital = HAS_CAPITAL.containsMatchIn(name)
        val wire = if (capital) wireKey(name, spec.keyCase, explicitCase) else name + (explicitCase?.let { "__case.$it" } ?: "")
        // What the DSL writes after the key, as Casing.withModifiers is handed it: the case, then the rest.
        if (wire != null) {
            // The key itself, not what comes before the first __: in b___case.pascal that is b.
            val keyPart = if (capital) ClientCasing.kebab(name) else name
            val modifiers = wire.removePrefix(keyPart) + if (addsModifiers(site, spec, src, lex)) "__" else ""
            AttributeKeys.runsIntoModifiers(keyPart, modifiers)?.let { return listOf(refusedKey(spec, key, name, it)) }
        }
        val mixed = if (spec.name == "on" && explicitCase == null && !capital) catalog.mixedCaseDomEvent(name) else null
        if (mixed != null) {
            return listOf(
                Issue(
                    start = key.start,
                    end = key.end,
                    message = "${site.name}(\"$name\") listens for $name, which no browser fires: the event is $mixed.",
                    severity = Severity.WARNING,
                    code = IssueCode.KEY_CASE_WIRE,
                    link = Docs.attribute(spec),
                    fixes = listOf(Fix("Change to $mixed", key.contentStart, key.contentEnd, mixed)),
                ),
            )
        }
        if (!capital) return emptyList()
        val dom = if (spec.name == "on" && explicitCase == null) catalog.lowercaseDomEvent(name) else null
        if (dom != null) {
            return listOf(
                Issue(
                    start = key.start,
                    end = key.end,
                    message = "${site.name}(\"$name\") listens for $name, which no browser fires: the DOM event is $dom.",
                    severity = Severity.WARNING,
                    code = IssueCode.KEY_CASE_WIRE,
                    link = Docs.attribute(spec),
                    fixes = listOf(Fix("Change to $dom", key.contentStart, key.contentEnd, dom)),
                ),
            )
        }
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
                    code = IssueCode.KEY_CASE_WIRE,
                    link = Docs.attribute(spec),
                ),
            )
        }
        val what = if (explicitCase != null) "a $explicitCase-cased name" else keyReading(spec, ClientCasing.readKey(wire), wire)
        return listOf(
            Issue(
                start = key.start,
                end = key.end,
                message =
                    "Written on the wire as $prefix${spec.name}:$wire, which Datastar reads back as $what: " +
                        "the browser lowercases attribute names, so a key is kebab-case. " +
                        "In HTML or a template you write it that way yourself." +
                        rawKeyNote(spec, prefix, name),
                severity = Severity.HINT,
                code = IssueCode.KEY_CASE_WIRE,
                link = Docs.attribute(spec),
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
        lex: KotlinLex,
    ): Boolean {
        fun on(parameter: String) = (site.named(parameter) ?: site.positional.getOrNull(2))?.text?.trim() == "true"
        if (site.name == KEYED_OR_OBJECT && on("ifMissing")) return true
        if (site.name == "dataPersist" && on("session")) return true
        val names = spec.modifiers.map { it.name.lowercase() }.filter { it != "case" }
        return LAMBDA_ASSIGNMENT.findAll(modifiersLambda(site, src, lex).replace(KOTLIN_STRING, "\"\"")).any { m ->
            val property = m.groupValues[1].lowercase()
            val value = m.groupValues[2].trim()
            value != "false" && value != "null" && names.any { property.startsWith(it) }
        }
    }

    /** The lambda of modifiers of [site]: the trailing one, or one passed as an argument, by name or not. */
    private fun modifiersLambda(
        site: CallSite,
        src: String,
        lex: KotlinLex,
    ): String = modifiersLambdaAt(site, src, lex).second

    /** [modifiersLambda] and where it starts in [src]. */
    private fun modifiersLambdaAt(
        site: CallSite,
        src: String,
        lex: KotlinLex,
    ): Pair<Int, String> {
        trailingLambda(site, src, lex)?.let { return it.first to src.substring(it.first, it.last + 1) }
        val arg = site.named("modifiers") ?: site.positional.firstOrNull { it.text.trimStart().startsWith("{") } ?: return 0 to ""
        return arg.start to arg.text
    }

    /**
     * What the DSL refuses as it renders, from the literals the lambda of modifiers of [site] sets:
     * a flag of a duration that is not set, `half`, `full` and `threshold` together, a threshold
     * out of 0 to 100, an interval of no time. A value the code computes is not judged.
     */
    private fun modifierLambdaIssues(
        site: CallSite,
        src: String,
        lex: KotlinLex,
    ): List<Issue> {
        if (site.name !in catalog.attributesByKotlin) return emptyList()
        val (start, lambda) = modifiersLambdaAt(site, src, lex)
        if (lambda.isEmpty()) return emptyList()
        // Strings and comments blanked where they stand, so an offset in the lambda is one in the source.
        val code = CharArray(lambda.length) { i -> if (lex.mask[start + i] || lambda[i] == '\n') lambda[i] else ' ' }.concatToString()
        // The value of each property set to a literal, by its name, and where it is set.
        val set = LAMBDA_ASSIGNMENT.findAll(code).associateBy({ it.groupValues[1] }, { it })

        fun value(name: String) = set[name]?.groupValues?.get(2)?.trim()

        val out = ArrayList<Issue>()

        fun refuse(
            m: MatchResult,
            why: String,
        ) {
            val end = m.range.first + m.value.trimEnd().length
            out += Issue(start + m.range.first, start + end, "Streamlord throws at render: $why.", Severity.ERROR, IssueCode.MODIFIER_ARGS)
        }

        fun on(name: String) = set[name]?.takeIf { value(name) == "true" }
        for ((duration, flags) in ModifierRules.durationFlags) {
            if (value(duration) != null && value(duration) != "null") continue
            flags.firstNotNullOfOrNull(::on)?.let { refuse(it, ModifierRules.flagWithoutDuration(it.groupValues[1], duration)) }
        }
        val threshold = set["threshold"]?.takeIf { value("threshold") != "null" }
        val alternatives = listOfNotNull(on("half"), on("full"), threshold).sortedBy { it.range.first }
        if (alternatives.size > 1) refuse(alternatives[1], ModifierRules.ALTERNATIVES)
        val percent = value("threshold")?.toIntOrNull()
        if (threshold != null && percent != null && percent !in ModifierRules.thresholds) refuse(threshold, ModifierRules.THRESHOLD_RANGE)
        val interval = set["duration"]
        if (site.name == "dataOnInterval" && interval != null && ZERO_DURATION.matchEntire(value("duration")!!) != null) {
            refuse(interval, ModifierRules.intervalNotPositive(Duration.ZERO.toString()))
        }
        return out
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
            code = IssueCode.KEY_REFUSED,
            link = Docs.attribute(spec),
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
        lex: KotlinLex,
    ): List<Issue> {
        // The key form takes the name and an expression; one positional string is the object form.
        // A name given by name is the name, literal or not: no positional argument stands in for it.
        val named = site.named("name") ?: site.named("attribute")
        val first = site.positional.takeIf { it.size >= 2 || site.named("expression") != null }?.firstOrNull()
        val key = (named ?: first)?.string ?: return emptyList()
        val name = key.text
        if (key.interpolations.isNotEmpty() || OPENS_OBJECT_OR_ARRAY.containsMatchIn(name)) return emptyList()
        AttributeKeys.nameProblem(name)?.let { return listOf(refusedKey(spec, key, name, it)) }
        if (!HAS_CAPITAL.containsMatchIn(name)) {
            val modifiers = if (addsModifiers(site, spec, src, lex)) "__" else ""
            return listOfNotNull(AttributeKeys.runsIntoModifiers(name, modifiers)?.let { refusedKey(spec, key, name, it) })
        }
        val reason = AttributeKeys.capitalIn(name, site.name) ?: return emptyList()
        val plain = src.substring(key.contentStart, key.contentEnd) == name
        return listOf(
            refusedKey(spec, key, name, reason).copy(
                code = IssueCode.ATTRIBUTE_NAME_CASE,
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
                IssueCode.SCRIPT_CLOSE,
            ),
        )
    }
}

/** An option of FetchOptions set to an expression: its [name], where the name starts, and the literal that is the expression. */
internal data class ExpressionOption(
    val name: String,
    val nameStart: Int,
    val value: KotlinString,
)

/**
 * An `attributes["data-..."] = "..."`: the attribute's [name], where it starts in the source, and the literal assigned. The
 * diagnostics judge the value only when it is [whole], the full statement and not the first half of a concatenation.
 */
internal data class AttributesIndex(
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
