@file:OptIn(ExperimentalJsExport::class)

package io.github.markusaugust.streamlord.editor

import io.github.markusaugust.streamlord.analysis.Analyzer
import io.github.markusaugust.streamlord.analysis.Completion
import io.github.markusaugust.streamlord.analysis.DeclaredNames
import io.github.markusaugust.streamlord.analysis.Modules
import io.github.markusaugust.streamlord.analysis.ProjectFiles
import io.github.markusaugust.streamlord.analysis.SignalFacts
import io.github.markusaugust.streamlord.analysis.SignalProperty
import io.github.markusaugust.streamlord.analysis.SignalReference
import io.github.markusaugust.streamlord.analysis.SiteIssues
import io.github.markusaugust.streamlord.analysis.Source
import io.github.markusaugust.streamlord.analysis.SourceLanguage
import io.github.markusaugust.streamlord.analysis.Workspace
import io.github.markusaugust.streamlord.analysis.collectSelectors
import io.github.markusaugust.streamlord.analysis.collectSignalDefinitions
import io.github.markusaugust.streamlord.analysis.collectSignalFacts
import io.github.markusaugust.streamlord.analysis.collectSignals
import io.github.markusaugust.streamlord.analysis.complete
import io.github.markusaugust.streamlord.analysis.declaredNames
import io.github.markusaugust.streamlord.analysis.hover
import io.github.markusaugust.streamlord.analysis.projectConstants
import io.github.markusaugust.streamlord.analysis.signalDeclarations
import io.github.markusaugust.streamlord.analysis.signalDrift
import io.github.markusaugust.streamlord.analysis.signalSites
import io.github.markusaugust.streamlord.analysis.signatureHelp
import io.github.markusaugust.streamlord.analysis.siteAt
import io.github.markusaugust.streamlord.analysis.unknownSignalIssue
import io.github.markusaugust.streamlord.core.catalog.CatalogNames
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

/** How VS Code describes a file: by its text alone, which sees no classpath, so a Kotlin file's imports say which modules it has. */
private fun source(
    src: String,
    kotlin: Boolean,
    prefix: String = CatalogNames.PREFIX,
    constants: Array<String> = emptyArray(),
): Source =
    Source(
        src,
        if (kotlin) SourceLanguage.KOTLIN else SourceLanguage.HTML,
        prefix,
        Modules.FROM_IMPORTS,
        constants.toHashSet(),
        prefixSetting = PREFIX_SETTING,
    )

/** The prefix setting by the id VS Code's settings are searched by. */
private const val PREFIX_SETTING = "streamlord.attributePrefix"

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
 * a signal that is none of them is reported too; with [others], what the workspace's other files
 * say about signals, so is a signal the server reads that no page declares.
 */
@JsExport
fun diagnose(
    src: String,
    kotlin: Boolean,
    prefix: String,
    constants: Array<String>,
    defined: Array<String>?,
    others: Array<DriftFacts>?,
): Array<Issue> {
    val file = source(src, kotlin, prefix, constants)
    val found = if (kotlin) sites(file).flatMap { it.issues } else analyzer.analyzeHtml(file)
    val unknown =
        if (defined ==
            null
        ) {
            emptyList()
        } else {
            analyzer.signalReferences(file).mapNotNull { unknownSignalIssue(it, setOf(defined)) }
        }
    val drift = if (others == null || !kotlin) emptyList() else drift(src, others)
    return (found + unknown + drift).map(::plain).toTypedArray()
}

/** The reads of signals in the Kotlin file [src] that neither it nor [others] declares. */
private fun drift(
    src: String,
    others: Array<DriftFacts>,
): List<AnalysedIssue> {
    // A file with no signals class and no lookup can report nothing, whatever the others say.
    val own = collectSignalFacts(source(src, true))
    if (own.classes.isEmpty() && own.lookups.isEmpty()) return emptyList()
    // Only this file's issues are asked for, so another file's reads and offsets play no part.
    val facts = HashMap<String, SignalFacts>()
    others.forEachIndexed { i, o ->
        val classes = o.classes.associate { c -> c.name to c.properties.map { SignalProperty(it, 0, 0) } }
        facts["$i"] =
            SignalFacts(
                o.declared.toSet(),
                o.readInto.toSet(),
                classes,
                emptyList(),
                o.encoded.toSet(),
                o.patched.toSet(),
                o.packageName,
                o.imports.toSet(),
            )
    }
    facts[THIS_FILE] = own
    return signalDrift(facts).issues[THIS_FILE].orEmpty()
}

/** The key of the file being diagnosed among the others, which are keyed by their index. */
private const val THIS_FILE = "this"

// The Kotlin file analysed last: the lightbulb asks about the text the diagnostics were just made for.
private var lastSites: Pair<Source, List<SiteIssues>>? = null

private fun sites(file: Source): List<SiteIssues> {
    lastSites?.takeIf { it.first == file }?.let { return it.second }
    return analyzer.analyzeKotlinSites(file).also { lastSites = file to it }
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
): Fix? = analysedRewriteAt(sites(source(src, true, prefix, constants)), start, end)?.let(::plain)

// ---- The workspace's signals -------------------------------------------------------------------

/** Which files of the workspace are read: as the analysis and the IntelliJ plugin read them. */
@JsExport
interface WorkspaceFiles {
    /** The folders left unread, with every hidden one. */
    val skippedFolders: Array<String>

    /** HTML's own extensions and the template files', read as markup whatever language an extension gives them. */
    val markupExtensions: Array<String>
}

@JsExport
fun workspaceFiles(): WorkspaceFiles =
    plain(
        "skippedFolders" to ProjectFiles.SKIPPED_FOLDERS.plain(),
        "markupExtensions" to (ProjectFiles.HTML_EXTENSIONS + ProjectFiles.TEMPLATE_EXTENSIONS).plain(),
    )

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

/** A `@Serializable` class of a file and the names of its properties, which are signals once something reads signals into it. */
@JsExport
interface SignalClass {
    val name: String
    val properties: Array<String>
}

/**
 * What one file tells another about the signals the server reads: the signals it declares, the
 * classes it reads signals into ([readInto]), declares a page from ([encoded]) and patches
 * ([patched]), and its own `@Serializable` classes. [reads] are the names it may read, its lookups and every property of
 * its classes: a name declared elsewhere or no more changes its verdict only when it is one. Its
 * [packageName] and [imports] say which class a name in [readInto] or [encoded] is.
 */
@JsExport
interface DriftFacts {
    val declared: Array<String>
    val readInto: Array<String>
    val encoded: Array<String>
    val patched: Array<String>
    val classes: Array<SignalClass>
    val reads: Array<String>
    val packageName: String
    val imports: Array<String>
}

/** What [src] tells the other files about the signals the server reads. */
@JsExport
fun driftFactsOf(
    src: String,
    kotlin: Boolean,
    prefix: String,
): DriftFacts = plain(collectSignalFacts(source(src, kotlin, prefix)))

private fun plain(facts: SignalFacts): DriftFacts =
    plain(
        "reads" to
            (
                facts.lookups.map { it.name } +
                    facts.classes.values
                        .flatten()
                        .map { it.name }
            ).toSet().plain(),
        "declared" to facts.declared.plain(),
        "readInto" to facts.signalTypes.plain(),
        "encoded" to facts.encodedTypes.plain(),
        "patched" to facts.patchedTypes.plain(),
        "classes" to
            facts.classes
                .map { (name, properties) -> plain<SignalClass>("name" to name, "properties" to properties.map { it.name }.plain()) }
                .toTypedArray(),
        "packageName" to facts.packageName,
        "imports" to facts.imports.plain(),
    )

@JsExport
fun signalsOf(
    src: String,
    kotlin: Boolean,
    prefix: String,
): FileSignals {
    val file = source(src, kotlin, prefix)
    val selectors = collectSelectors(src)
    val names = if (kotlin) declaredNames(src) else null
    return plain(
        "signals" to collectSignals(file).plain(),
        "definitions" to collectSignalDefinitions(file).plain(),
        "ids" to selectors.ids.plain(),
        "classes" to selectors.classes.plain(),
        "constants" to names?.constants.orEmpty().plain(),
        "others" to names?.others.orEmpty().plain(),
    )
}

/** The constants of the workspace: each file's, less every name some file declares as anything else. */
@JsExport
fun constantsAcross(files: Array<FileSignals>): Array<String> =
    projectConstants(files.map { DeclaredNames(it.constants.toSet(), it.others.toSet()) }).plain()

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
): SignalPlaces {
    val file = source(src, kotlin, prefix)
    return plain(
        "declarations" to signalDeclarations(file).map(::plain).toTypedArray(),
        "reads" to
            analyzer
                .signalReferences(file)
                .map { plain<SignalRead>("start" to it.start, "end" to it.end, "name" to it.name) }
                .toTypedArray(),
    )
}

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

    /** Where the text the item replaces starts; it ends at the caret, or, where the editor replaces, at [replaceTo]. */
    val replaceFrom: Int

    /** Where the word the caret stands in ends: what the item replaces when the editor replaces rather than inserts. */
    val replaceTo: Int
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

/** The attribute prefix Datastar reads when none is set. */
@JsExport
fun defaultPrefix(): String = CatalogNames.PREFIX

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
        "replaceTo" to item.replaceTo,
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
