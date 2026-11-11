@file:OptIn(ExperimentalJsExport::class)

package io.github.markusaugust.streamlord.editor

import io.github.markusaugust.streamlord.analysis.AnalyzeOptions
import io.github.markusaugust.streamlord.analysis.Analyzer
import io.github.markusaugust.streamlord.analysis.SignalReference
import io.github.markusaugust.streamlord.analysis.SourceLanguage
import io.github.markusaugust.streamlord.analysis.actionDoc
import io.github.markusaugust.streamlord.analysis.attributeDoc
import io.github.markusaugust.streamlord.analysis.collectSelectors
import io.github.markusaugust.streamlord.analysis.collectSignalDefinitions
import io.github.markusaugust.streamlord.analysis.collectSignals
import io.github.markusaugust.streamlord.analysis.constantNames
import io.github.markusaugust.streamlord.analysis.expressionAtCaret
import io.github.markusaugust.streamlord.analysis.fetchOptionDoc
import io.github.markusaugust.streamlord.analysis.otherDeclaredNames
import io.github.markusaugust.streamlord.analysis.signalDeclarations
import io.github.markusaugust.streamlord.analysis.signalSites
import io.github.markusaugust.streamlord.analysis.siteAt
import io.github.markusaugust.streamlord.analysis.tagBeforeCaret
import io.github.markusaugust.streamlord.analysis.unknownSignalIssue
import io.github.markusaugust.streamlord.analysis.ActionCall as AnalysedCall
import io.github.markusaugust.streamlord.analysis.ActionSpec as AnalysedAction
import io.github.markusaugust.streamlord.analysis.AttributeSpec as AnalysedAttribute
import io.github.markusaugust.streamlord.analysis.ExpressionAtCaret as AnalysedExpression
import io.github.markusaugust.streamlord.analysis.Fix as AnalysedFix
import io.github.markusaugust.streamlord.analysis.Issue as AnalysedIssue
import io.github.markusaugust.streamlord.analysis.SignalDeclaration as AnalysedDeclaration
import io.github.markusaugust.streamlord.analysis.SignalSite as AnalysedSite
import io.github.markusaugust.streamlord.analysis.actionCallAt as analysedCallAt
import io.github.markusaugust.streamlord.analysis.actionPrefixAt as analysedActionPrefixAt
import io.github.markusaugust.streamlord.analysis.signalPrefixAt as analysedSignalPrefixAt

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
    if (!kotlin) return AnalyzeOptions(prefix = prefix)
    val modules = analyzer.modulesOf(src)
    return AnalyzeOptions(prefix = prefix, htmlDsl = modules.htmlDsl, core = modules.core)
}

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
    val found = if (kotlin) analyzer.analyzeKotlin(src, opts) else analyzer.analyzeHtml(src, opts)
    val unknown = if (defined == null) emptyList() else reads(src, kotlin, opts).mapNotNull { unknownSignalIssue(it, setOf(defined)) }
    return (found + unknown).map(::plain).toTypedArray()
}

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

/**
 * The calls of a file that are somebody else's and define no signal: in a Kotlin file without the
 * DSL's import, its short helpers, `set`, `toggle` and `signal`, whose names other APIs use too.
 */
private fun foreignHelpers(
    src: String,
    kotlin: Boolean,
): Set<String> = if (kotlin && !analyzer.modulesOf(src).htmlDsl) analyzer.catalog.signalNameHelpers.keys else emptySet()

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

// ---- Where a caret stands, and what the catalog says: what completion, hover and signature help read

@JsExport
interface ExpressionAtCaret {
    /** The expression before the caret, as Datastar is handed it. */
    val before: String

    /** Whether actions belong there: an expression, not a signal name. */
    val allowActions: Boolean

    /** Whether `patch` is in scope: data-on-signal-patch only. */
    val patchInScope: Boolean
}

@JsExport
interface OptionKey {
    val typed: String
    val written: Array<String>
}

@JsExport
interface ActionCall {
    val action: String
    val argument: Int
    val option: OptionKey?
}

private fun plain(e: AnalysedExpression): ExpressionAtCaret =
    plain("before" to e.before, "allowActions" to e.allowActions, "patchInScope" to e.patchInScope)

private fun plain(call: AnalysedCall): ActionCall =
    plain(
        "action" to call.action,
        "argument" to call.argument,
        "option" to call.option?.let { plain<OptionKey>("typed" to it.typed, "written" to it.written.plain()) },
    )

/** The Datastar expression [offset] stands in, or null: in a Kotlin file a DSL call's string only where the file can call the DSL. */
@JsExport
fun expressionAtCaret(
    src: String,
    offset: Int,
    kotlin: Boolean,
    prefix: String,
): ExpressionAtCaret? {
    val found =
        if (kotlin) {
            analyzer.expressionAtCaret(
                src,
                offset,
                prefix,
                analyzer.modulesOf(src),
            )
        } else {
            analyzer.expressionAtCaret(src, offset, false, prefix)
        }
    return found?.let(::plain)
}

/** The innermost `@name(` still open at [offset] in the expression [text], or null. */
@JsExport
fun actionCallAt(
    text: String,
    offset: Int,
): ActionCall? = analysedCallAt(text, offset)?.let(::plain)

/** What is written of a signal's name before [offset], after its `$`, or null. */
@JsExport
fun signalPrefixAt(
    text: String,
    offset: Int,
): String? = analysedSignalPrefixAt(text, offset)

/** What is written of an action's name before [offset], after its `@`, or null. */
@JsExport
fun actionPrefixAt(
    text: String,
    offset: Int,
): String? = analysedActionPrefixAt(text, offset)

/** The markup of the tag [offset] stands in, from its `<`, or null: in a Kotlin file inside an HTML string alone. */
@JsExport
fun tagBeforeCaret(
    src: String,
    offset: Int,
    kotlin: Boolean,
    prefix: String,
): String? {
    if (!kotlin) return tagBeforeCaret(src, offset, prefix)
    val html = analyzer.htmlStringAt(src, offset, analyzer.modulesOf(src)) ?: return null
    return if (offset > html.contentStart && offset <= html.contentEnd) tagBeforeCaret(html, offset, prefix) else null
}

/** What is written before [offset] of the selector a Streamlord call in a Kotlin file is handed there, or null. */
@JsExport
fun selectorBefore(
    src: String,
    offset: Int,
): String? = analyzer.selectorBefore(src, offset, analyzer.modulesOf(src))

/** Whether [offset] is in a string of a Kotlin file that holds HTML. */
@JsExport
fun inHtmlString(
    src: String,
    offset: Int,
): Boolean = analyzer.htmlStringAt(src, offset, analyzer.modulesOf(src)) != null

/** Whether the Kotlin file [src] can call the DSL of streamlord-html. */
@JsExport
fun canCallDsl(src: String): Boolean = analyzer.modulesOf(src).htmlDsl

/** Whether [offset] is in a call named as one of the DSL's in a Kotlin file that cannot call the DSL. */
@JsExport
fun inForeignDslCall(
    src: String,
    offset: Int,
): Boolean = analyzer.inForeignDslCall(src, offset, analyzer.modulesOf(src))

@JsExport
interface Modifier {
    val name: String

    /** `flag`, `duration`, `enum`, `int`, `ident` or `idents`. */
    val type: String
    val values: Array<String>
    val flags: Array<String>
}

@JsExport
interface Attribute {
    val name: String
    val pro: Boolean
    val forms: Array<String>
    val keyRequired: Boolean

    /** `expression`, `signal`, `filter`, `text` or `none`. */
    val valueKind: String
    val kotlin: Array<String>
    val modifiers: Array<Modifier>
    val onlyOn: Array<String>?
}

@JsExport
interface Action {
    val name: String
    val pro: Boolean
    val kind: String
    val signature: String
    val kotlin: String
    val doc: String
    val parameters: Array<String>
}

@JsExport
interface FetchOption {
    val name: String
    val type: String
}

@JsExport
interface DatastarEvent {
    val name: String
    val doc: String
}

@JsExport
interface DomEvent {
    val name: String
    val mdn: String?
}

@JsExport
interface Catalog {
    val prefix: String
    val attributes: Array<Attribute>
    val actions: Array<Action>
    val fetchOptions: Array<FetchOption>
    val compiledParameters: Array<String>
    val datastarEvents: Array<DatastarEvent>
    val domEvents: Array<DomEvent>
}

private fun plain(a: AnalysedAttribute): Attribute =
    plain(
        "name" to a.name,
        "pro" to a.pro,
        "forms" to a.forms.plain(),
        "keyRequired" to a.keyRequired,
        "valueKind" to a.valueKind.name.lowercase(),
        "kotlin" to a.kotlin.plain(),
        "modifiers" to
            a.modifiers
                .map {
                    plain<Modifier>(
                        "name" to it.name,
                        "type" to it.type.name.lowercase(),
                        "values" to it.values.plain(),
                        "flags" to it.flags.plain(),
                    )
                }.toTypedArray(),
        "onlyOn" to a.onlyOn?.plain(),
    )

private fun plain(a: AnalysedAction): Action =
    plain(
        "name" to a.name,
        "pro" to a.pro,
        "kind" to a.kind,
        "signature" to a.signature,
        "kotlin" to a.kotlin,
        "doc" to a.doc,
        "parameters" to a.parameters.plain(),
    )

private val attributes: Map<String, Attribute> by lazy { analyzer.catalog.attributes.associate { it.name to plain(it) } }

private val plainCatalog: Catalog by lazy {
    val c = analyzer.catalog
    plain(
        "prefix" to AnalyzeOptions().prefix,
        "attributes" to attributes.values.toTypedArray(),
        "actions" to c.actions.map(::plain).toTypedArray(),
        "fetchOptions" to c.fetchOptions.map { plain<FetchOption>("name" to it.name, "type" to it.type) }.toTypedArray(),
        "compiledParameters" to c.compiledParameters.plain(),
        "datastarEvents" to c.datastarEvents.map { plain<DatastarEvent>("name" to it.name, "doc" to it.doc) }.toTypedArray(),
        "domEvents" to c.domEvents.map { plain<DomEvent>("name" to it.name, "mdn" to it.mdn) }.toTypedArray(),
    )
}

/** The attributes, actions, options and events of the Datastar version Streamlord speaks. */
@JsExport
fun catalog(): Catalog = plainCatalog

/** The attribute a name as written stands for, `data-on:click__once` for `on`, or null. */
@JsExport
fun attributeNamed(
    written: String,
    prefix: String,
): Attribute? =
    analyzer.catalog
        .parseAttributeName(written, prefix)
        ?.spec
        ?.let { attributes[it.name] }

/** The attribute the DSL function [helper] writes, or null. */
@JsExport
fun attributeOfHelper(helper: String): Attribute? = analyzer.catalog.attributesByKotlin[helper]?.let { attributes[it.name] }

/** What the attribute [name] is and does, in Markdown; [withKotlin] names its DSL helpers too. */
@JsExport
fun attributeDoc(
    name: String,
    prefix: String,
    withKotlin: Boolean,
): String? = analyzer.catalog.attributesByName[name]?.let { attributeDoc(it, prefix, withKotlin) }

@JsExport
fun actionDoc(
    name: String,
    withKotlin: Boolean,
): String? = analyzer.catalog.actionsByName[name]?.let { actionDoc(it, withKotlin) }

@JsExport
fun fetchOptionDoc(name: String): String? =
    analyzer.catalog.fetchOptions
        .firstOrNull { it.name == name }
        ?.let(::fetchOptionDoc)
