@file:OptIn(ExperimentalJsExport::class)

package io.github.markusaugust.streamlord.editor

import io.github.markusaugust.streamlord.analysis.AnalyzeOptions
import io.github.markusaugust.streamlord.analysis.Analyzer
import io.github.markusaugust.streamlord.analysis.Completion
import io.github.markusaugust.streamlord.analysis.SignalReference
import io.github.markusaugust.streamlord.analysis.SiteIssues
import io.github.markusaugust.streamlord.analysis.Source
import io.github.markusaugust.streamlord.analysis.SourceLanguage
import io.github.markusaugust.streamlord.analysis.Workspace
import io.github.markusaugust.streamlord.analysis.analyzeOptions
import io.github.markusaugust.streamlord.analysis.collectSelectors
import io.github.markusaugust.streamlord.analysis.collectSignalDefinitions
import io.github.markusaugust.streamlord.analysis.collectSignals
import io.github.markusaugust.streamlord.analysis.complete
import io.github.markusaugust.streamlord.analysis.constantNames
import io.github.markusaugust.streamlord.analysis.foreignHelpers
import io.github.markusaugust.streamlord.analysis.hover
import io.github.markusaugust.streamlord.analysis.modulesOf
import io.github.markusaugust.streamlord.analysis.otherDeclaredNames
import io.github.markusaugust.streamlord.analysis.signalDeclarations
import io.github.markusaugust.streamlord.analysis.signalSites
import io.github.markusaugust.streamlord.analysis.signatureHelp
import io.github.markusaugust.streamlord.analysis.siteAt
import io.github.markusaugust.streamlord.analysis.unknownSignalIssue
import io.github.markusaugust.streamlord.analysis.Fix as AnalysedFix
import io.github.markusaugust.streamlord.analysis.Issue as AnalysedIssue
import io.github.markusaugust.streamlord.analysis.SignalDeclaration as AnalysedDeclaration
import io.github.markusaugust.streamlord.analysis.SignalSite as AnalysedSite
import io.github.markusaugust.streamlord.analysis.rewriteAt as analysedRewriteAt

/*
 * The driving port of the analysis for an editor written in TypeScript: what the VS Code
 * extension's adapters call, and all they call. Text and plain values go in, plain objects come
 * out, and the TypeScript types of both are generated from this file and InspectorPort.kt.
 */

internal val analyzer = Analyzer()

// ---- Diagnostics -------------------------------------------------------------------------------

@JsExport
interface TextEdit {
    val start: Int
    val end: Int
    val text: String
}

@JsExport
interface Fix {
    val title: String
    val start: Int
    val end: Int

    /** Made when read, since most fixes are never applied. */
    val text: String
    val edits: Array<TextEdit>?

    /** False where the fix is offered and not preferred; absent otherwise. */
    val preferred: Boolean?
}

@JsExport
interface Issue {
    val start: Int
    val end: Int
    val message: String

    /** `error`, `warning`, `info` or `hint`. */
    val severity: String
    val code: String?
    val link: String?
    val fixes: Array<Fix>?
}

private fun plain(fix: AnalysedFix): Fix {
    val o =
        plain<Fix>(
            "title" to fix.title,
            "start" to fix.start,
            "end" to fix.end,
            "edits" to
                fix.edits
                    .takeIf { it.isNotEmpty() }
                    ?.map {
                        plain<TextEdit>(
                            "start" to it.start,
                            "end" to it.end,
                            "text" to it.text,
                        )
                    }?.toTypedArray(),
            "preferred" to if (fix.preferred) null else false,
        )
    val text: dynamic = js("({ enumerable: true })")
    text.get = { fix.text }
    js("Object").defineProperty(o, "text", text)
    return o
}

private fun plain(issue: AnalysedIssue): Issue =
    plain(
        "start" to issue.start,
        "end" to issue.end,
        "message" to issue.message,
        "severity" to issue.severity.name.lowercase(),
        "code" to issue.code,
        "link" to issue.link,
        "fixes" to
            issue.fixes
                .takeIf { it.isNotEmpty() }
                ?.map(::plain)
                ?.toTypedArray(),
    )

private fun language(kotlin: Boolean): SourceLanguage = if (kotlin) SourceLanguage.KOTLIN else SourceLanguage.HTML

/** How a file is read: a Kotlin file with the modules its imports show, markup with the prefix alone. */
private fun options(
    src: String,
    kotlin: Boolean,
    prefix: String,
): AnalyzeOptions {
    if (!kotlin) return AnalyzeOptions(prefix = prefix, prefixSetting = PREFIX_SETTING)
    return analyzer.analyzeOptions(source(src, true, prefix)).copy(prefixSetting = PREFIX_SETTING)
}

/** The prefix setting by the id VS Code's settings are searched by. */
private const val PREFIX_SETTING = "streamlord.attributePrefix"

private fun reads(
    src: String,
    kotlin: Boolean,
    opts: AnalyzeOptions,
): List<SignalReference> = if (kotlin) analyzer.signalReferencesInKotlin(src, opts) else analyzer.signalReferencesInHtml(src, opts)

// The adapters hand the same array until the workspace changes, so its set is made once.
private var lastNames: Array<String>? = null
private var lastSet: Set<String> = emptySet()

private fun setOf(names: Array<String>): Set<String> {
    if (names !== lastNames) {
        lastSet = names.toHashSet()
        lastNames = names
    }
    return lastSet
}

/**
 * What is wrong in [src], a Kotlin file when [kotlin] and markup otherwise. [constants] are the
 * `const val` names of the workspace. With [defined], the signals the workspace defines, a read of
 * a signal that is none of them is reported too.
 */
@JsExport
fun diagnose(
    src: String,
    kotlin: Boolean,
    prefix: String,
    constants: Array<String>,
    defined: Array<String>?,
): Array<Issue> {
    val opts = options(src, kotlin, prefix).copy(constants = constants.toHashSet())
    val found = if (kotlin) sites(src, opts).flatMap { it.issues } else analyzer.analyzeHtml(src, opts)
    val unknown = if (defined == null) emptyList() else reads(src, kotlin, opts).mapNotNull { unknownSignalIssue(it, setOf(defined)) }
    return (found + unknown).map(::plain).toTypedArray()
}

// The Kotlin file analysed last: the lightbulb asks about the text the diagnostics were just made for.
private var lastSites: Triple<String, AnalyzeOptions, List<SiteIssues>>? = null

private fun sites(
    src: String,
    opts: AnalyzeOptions,
): List<SiteIssues> {
    lastSites?.takeIf { it.first == src && it.second == opts }?.let { return it.third }
    return analyzer.analyzeKotlinSites(src, opts).also { lastSites = Triple(src, opts, it) }
}

/**
 * The rewrite of an HTML string of the Kotlin file [src] to a call of `interpolate`, to offer as a
 * context action for the caret or selection from [start] to [end]: null where that lies in no one
 * string that can take it, and where an issue it touches offers the rewrite already.
 */
@JsExport
fun rewriteAt(
    src: String,
    start: Int,
    end: Int,
    prefix: String,
    constants: Array<String>,
): Fix? = analysedRewriteAt(sites(src, options(src, true, prefix).copy(constants = constants.toHashSet())), start, end)?.let(::plain)

// ---- The workspace's signals -------------------------------------------------------------------

/** What one file gives the workspace: the signals it names and defines, its ids and classes, and what its Kotlin lets other files see. */
@JsExport
interface FileSignals {
    val signals: Array<String>
    val definitions: Array<String>
    val ids: Array<String>
    val classes: Array<String>
    val constants: Array<String>
    val others: Array<String>
}

/** The calls of [src] that are somebody else's and define no signal, by the modules its imports show. */
private fun foreignHelpers(
    src: String,
    kotlin: Boolean,
): Set<String> = foreignHelpers(htmlDsl = !kotlin || analyzer.modulesOf(source(src, true, "")).htmlDsl)

@JsExport
fun signalsOf(
    src: String,
    kotlin: Boolean,
): FileSignals {
    val foreign = foreignHelpers(src, kotlin)
    val selectors = collectSelectors(src)
    return plain(
        "signals" to collectSignals(src, language(kotlin), foreign).plain(),
        "definitions" to collectSignalDefinitions(src, language(kotlin), foreign).plain(),
        "ids" to selectors.ids.plain(),
        "classes" to selectors.classes.plain(),
        "constants" to (if (kotlin) constantNames(src) else emptySet()).plain(),
        "others" to (if (kotlin) otherDeclaredNames(src) else emptySet()).plain(),
    )
}

/** The constants of the workspace: each file's, less every name some file declares as anything else. */
@JsExport
fun constantsAcross(files: Array<FileSignals>): Array<String> {
    val out = HashSet<String>()
    for (file in files) out += file.constants
    for (file in files) out -= file.others.toSet()
    return out.plain()
}

// ---- Navigation and rename ---------------------------------------------------------------------

@JsExport
interface SignalDeclaration {
    val start: Int
    val end: Int
    val name: String

    /** How a new name is written there: `name`, `key`, `objectKey` or `property`. */
    val kind: String
}

@JsExport
interface SignalRead {
    val start: Int
    val end: Int
    val name: String
}

/** Where a file defines and reads signals. */
@JsExport
interface SignalPlaces {
    val declarations: Array<SignalDeclaration>
    val reads: Array<SignalRead>
}

/** One place that names a signal: a part of a read's path, or with [declaration] a place that defines it. */
@JsExport
interface SignalSite {
    val start: Int
    val end: Int
    val signal: String
    val declaration: SignalDeclaration?
}

private val KINDS =
    mapOf(
        AnalysedDeclaration.Kind.NAME to "name",
        AnalysedDeclaration.Kind.KEY to "key",
        AnalysedDeclaration.Kind.OBJECT_KEY to "objectKey",
        AnalysedDeclaration.Kind.PROPERTY to "property",
    )

private fun plain(d: AnalysedDeclaration): SignalDeclaration =
    plain(
        "start" to d.start,
        "end" to d.end,
        "name" to d.name,
        "kind" to KINDS[d.kind],
    )

private fun analysed(d: SignalDeclaration): AnalysedDeclaration =
    AnalysedDeclaration(d.start, d.end, d.name, KINDS.entries.first { it.value == d.kind }.key)

private fun plain(site: AnalysedSite): SignalSite =
    plain("start" to site.start, "end" to site.end, "signal" to site.signal, "declaration" to site.declaration?.let(::plain))

private fun analysed(site: SignalSite): AnalysedSite = AnalysedSite(site.start, site.end, site.signal, site.declaration?.let(::analysed))

private fun sites(
    places: SignalPlaces,
    defined: Array<String>,
): List<AnalysedSite> =
    signalSites(places.reads.map { SignalReference(it.start, it.end, it.name) }, places.declarations.map(::analysed), setOf(defined))

@JsExport
fun signalPlaces(
    src: String,
    kotlin: Boolean,
    prefix: String,
): SignalPlaces =
    plain(
        "declarations" to signalDeclarations(src, language(kotlin), foreignHelpers(src, kotlin)).map(::plain).toTypedArray(),
        "reads" to
            reads(src, kotlin, options(src, kotlin, prefix))
                .map { plain<SignalRead>("start" to it.start, "end" to it.end, "name" to it.name) }
                .toTypedArray(),
    )

/** The place at [offset] that names a signal of [defined], the signals the workspace defines, or null. */
@JsExport
fun signalSiteAt(
    places: SignalPlaces,
    defined: Array<String>,
    offset: Int,
): SignalSite? = siteAt(sites(places, defined), offset)?.let(::plain)

/** The places that name the signal [name], and with [under] those that define a signal under it. */
@JsExport
fun signalSitesNaming(
    places: SignalPlaces,
    defined: Array<String>,
    name: String,
    under: Boolean,
): Array<SignalSite> = sites(places, defined).filter { it.names(name, under) }.map(::plain).toTypedArray()

/** Why the signal at [site] cannot be renamed from there, or null. */
@JsExport
fun renameRefusal(site: SignalSite): String? = analysed(site).renameRefusal()

/** What goes over [site] in [text] when the signal [old] is renamed [newName], or null when nothing written there can name it. */
@JsExport
fun renameText(
    text: String,
    site: SignalSite,
    old: String,
    newName: String,
): String? = analysed(site).renameText(text, old, newName)

// ---- Completion, hover and signature help -----------------------------------------------------

@JsExport
interface SnippetPlace {
    val start: Int
    val end: Int
    val choices: Array<String>
}

@JsExport
interface SnippetText {
    val text: String

    /** In the order the caret visits them. */
    val places: Array<SnippetPlace>
}

@JsExport
interface CompletionItem {
    val label: String

    /** `signal`, `action`, `option`, `scope`, `attribute`, `event`, `modifier`, `value`, `id` or `class`. */
    val kind: String

    /** Where the text the item replaces starts; it ends at the caret. */
    val replaceFrom: Int
    val insert: String?
    val snippet: SnippetText?
    val detail: String?
    val tail: String?

    /** Markdown. */
    val doc: String?
    val filter: String?
}

@JsExport
interface HoverText {
    val start: Int
    val end: Int
    val markdown: String
}

@JsExport
interface Signature {
    val label: String
    val doc: String

    /** Where each parameter stands in [label], as `[start, end]`. */
    val parameters: Array<Array<Int>>
    val active: Int
}

private fun source(
    src: String,
    kotlin: Boolean,
    prefix: String,
): Source = Source(src, language(kotlin), prefix)

/** The attribute prefix Datastar reads when none is set. */
@JsExport
fun defaultPrefix(): String = AnalyzeOptions().prefix

/**
 * The items to offer at [offset] of [src], in the order shown: [signals], [ids] and [classes] are
 * those the workspace defines, and [attributeNames] is false where the editor offers those already.
 */
@JsExport
fun complete(
    src: String,
    offset: Int,
    kotlin: Boolean,
    prefix: String,
    signals: Array<String>,
    ids: Array<String>,
    classes: Array<String>,
    attributeNames: Boolean,
): Array<CompletionItem> {
    val workspace = Workspace(signals.asList(), ids.asList(), classes.asList(), called = "workspace")
    return analyzer
        .complete(source(src, kotlin, prefix), offset, workspace, attributeNames)
        .items
        .map(::plain)
        .toTypedArray()
}

private fun plain(item: Completion): CompletionItem {
    val snippet =
        item.snippet?.let { s ->
            val places = s.places.map { plain<SnippetPlace>("start" to it.start, "end" to it.end, "choices" to it.choices.plain()) }
            plain<SnippetText>("text" to s.text, "places" to places.toTypedArray())
        }
    return plain(
        "label" to item.label,
        "kind" to item.kind.name.lowercase(),
        "replaceFrom" to item.replaceFrom,
        "insert" to item.insert,
        "snippet" to snippet,
        "detail" to item.detail,
        "tail" to item.tail,
        "doc" to item.doc,
        "filter" to item.filter,
    )
}

/** The documentation of what stands at [offset] of [src], or null. */
@JsExport
fun hover(
    src: String,
    offset: Int,
    kotlin: Boolean,
    prefix: String,
): HoverText? {
    val found = analyzer.hover(source(src, kotlin, prefix), offset) ?: return null
    return plain("start" to found.start, "end" to found.end, "markdown" to found.markdown)
}

/** The parameters of the action call [offset] of [src] stands in, or null. */
@JsExport
fun signatureHelp(
    src: String,
    offset: Int,
    kotlin: Boolean,
    prefix: String,
): Signature? {
    val help = analyzer.signatureHelp(source(src, kotlin, prefix), offset) ?: return null
    val parameters = help.parameters.map { arrayOf(it.first, it.last + 1) }.toTypedArray()
    return plain("label" to help.label, "doc" to help.doc, "parameters" to parameters, "active" to help.active)
}
