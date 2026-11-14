package io.github.markusaugust.streamlord.analysis

import io.github.markusaugust.streamlord.core.client.regex

/*
 * Which of Streamlord's modules a file can use, as the editor knows it or, where it cannot tell,
 * as the imports of a Kotlin file prove it. Completion and hover read a DSL call's string as
 * Datastar only where the file can call the DSL. Whether the project has Streamlord at all no
 * text can prove: a parent pom, a published catalog or a platform names it out of sight.
 */

private val OFFICIAL_SDK_IMPORT = regex("""^[ \t]*import[ \t]+dev\.datastar\.kotlin\.sdk\b""", RegexOption.MULTILINE)
private val STREAMLORD_IMPORT = regex("""^[ \t]*import[ \t]+io\.github\.markusaugust\.streamlord\.""", RegexOption.MULTILINE)
private val IMPORT_OR_PACKAGE = regex("import|package")
private val SELECTOR_ARGUMENTS = setOf("selector", "viewTransitionSelector")

/**
 * What the host knows of which of Streamlord's modules a file's module has, as it picks it: [known]
 * where it can see the classpath, [UNKNOWN] where it cannot tell now (an IDE that indexes, or no
 * file), which reads the DSL's calls as the DSL's and its short helpers only where the file names
 * the DSL, and [FROM_IMPORTS] where it never sees a classpath, which reads a Kotlin file's imports.
 */
public class Modules private constructor(
    internal val core: Boolean?,
    internal val htmlDsl: Boolean?,
    internal val coreHtml: Boolean,
    internal val fromImports: Boolean,
) {
    public companion object {
        public val UNKNOWN: Modules = Modules(null, null, true, false)
        public val FROM_IMPORTS: Modules = Modules(null, null, true, true)
        private val KNOWN = List(8) { Modules(it and 4 != 0, it and 2 != 0, it and 1 != 0, false) }

        /** Whether the module has streamlord-core, streamlord-html, and a core with `Html`, which came in 0.16.0. */
        public fun known(
            core: Boolean,
            htmlDsl: Boolean,
            coreHtml: Boolean = true,
        ): Modules = KNOWN[(if (core) 4 else 0) + (if (htmlDsl) 2 else 0) + (if (coreHtml) 1 else 0)]
    }
}

/** Which of Streamlord's modules a file can use, settled once from its [Source] by [settle], and carried to every rule. */
internal class FileModules(
    val core: Boolean,
    /** Null where nothing says. Without core there is no streamlord-html either. */
    val htmlDsl: Boolean?,
    val coreHtml: Boolean,
    /** Whether a call found by its name is somebody else's, as [Source.foreign] says. */
    val foreign: (name: String, nameStart: Int) -> Boolean,
) {
    /** Whether the file may call the DSL: so it may unless something says it cannot. */
    val dsl: Boolean get() = htmlDsl != false

    /** The calls the analysis reads Datastar in that are Streamlord's here: none without core, none of the DSL's without it. */
    fun callNames(analyzer: Analyzer): Set<String> =
        when {
            !core -> emptySet()
            !dsl -> analyzer.callSiteNames - analyzer.dslNames
            else -> analyzer.callSiteNames
        }

    companion object {
        val DEFAULT = FileModules(true, null, true, NOT_FOREIGN)
    }
}

/**
 * The modules [source] can use. Read from the imports, a Kotlin file has the DSL through an import
 * of `io.github.markusaugust.streamlord.html` or from inside that package, and core unless it
 * imports the official SDK and nothing of Streamlord: core's functions are members of a stream a
 * file may be handed without any import. Markup read so is read as when nothing is known.
 */
internal fun settle(
    source: Source,
    lex: (String) -> KotlinLex = ::lexKotlin,
): FileModules {
    val m = source.modules
    val text = source.text
    val core =
        when {
            m.core != null -> {
                m.core
            }

            m.fromImports && source.language == SourceLanguage.KOTLIN -> {
                !(OFFICIAL_SDK_IMPORT.containsMatchIn(text) && !STREAMLORD_IMPORT.containsMatchIn(text))
            }

            else -> {
                true
            }
        }
    val htmlDsl =
        when {
            !core -> false
            m.htmlDsl != null -> m.htmlDsl
            m.fromImports && source.language == SourceLanguage.KOTLIN -> importsDsl(text, lex)
            else -> null
        }
    return FileModules(core, htmlDsl, m.coreHtml, source.foreign)
}

internal fun Analyzer.settle(source: Source): FileModules = settle(source, ::lex)

/**
 * The calls of a Kotlin file that are somebody else's and define no signal there: where the file
 * cannot call the DSL, its short helpers, `set`, `toggle` and `signal`, whose names other APIs use too.
 */
internal fun foreignHelpers(source: Source): Set<String> =
    if (source.language != SourceLanguage.KOTLIN || settle(source).dsl) emptySet() else Catalog.default.signalNameHelpers.keys

/** Whether [src] imports the DSL, or is in its package, in its code: a line in a comment or a raw string imports nothing. */
private fun importsDsl(
    src: String,
    lex: (String) -> KotlinLex,
): Boolean {
    // A file read from disk may start with a byte order mark, which would hide an import on its first line.
    val text = src.removePrefix("\uFEFF")
    if (!USES_HTML_DSL.containsMatchIn(text)) return false
    val mask = lex(text).mask
    return USES_HTML_DSL.findAll(text).any { m -> mask[m.range.first + IMPORT_OR_PACKAGE.find(m.value)!!.range.first] }
}

/** The HTML string at [offset] of [source]: one that looks like HTML, or the markup handed to a Streamlord call where the file has core. */
public fun Analyzer.htmlStringAt(
    source: Source,
    offset: Int,
): KotlinString? = htmlStringAt(source.text, offset, settle(source))

/** Whether [source] may call the DSL, as its modules settle it: what colouring and spelling read a DSL call's string by. */
public fun Analyzer.readsDsl(source: Source): Boolean = settle(source).dsl

internal fun Analyzer.htmlStringAt(
    src: String,
    offset: Int,
    modules: FileModules,
): KotlinString? = htmlStringAt(src, offset)?.takeIf { modules.core || isHtmlString(src, it) }

/**
 * The markup of the `+` chain whose literals [offset] stands in, read as one string, when it opens
 * with a tag: `"<div class=\"a\"" + " data-on:click__"`.
 */
internal fun Analyzer.chainAt(
    src: String,
    offset: Int,
): KotlinString? {
    val lex = lex(src)
    val at = lex.strings.indexOfFirst { it.start < offset && offset <= it.end }
    if (at < 0) return null
    // The chain starts at the earliest literal whose chain still reaches the caret.
    var found: KotlinString? = null
    for (i in at downTo maxOf(0, at - 50)) {
        val chain = chainFrom(src, lex.strings[i], lex.mask)?.takeIf { it.start < offset && offset <= it.end } ?: break
        found = chain
    }
    return found?.takeIf { textOpensWithTag(it.text) }
}

/** Whether [offset] lies in an `attributes["data-..."] = "..."`, kotlinx.html's own form, which is Datastar in every project. */
private fun Analyzer.inAttributesIndex(
    src: String,
    offset: Int,
): Boolean = attributesIndexes(src).any { it.nameStart <= offset && offset <= (it.value?.end ?: it.nameStart) }

/** The innermost call named one of [names] whose parentheses hold [offset]. */
private fun Analyzer.callAround(
    src: String,
    offset: Int,
    names: Set<String>,
): CallSite? = findCallSites(src, names, lex(src)).lastOrNull { it.openParen < offset && offset <= it.closeParen }

/**
 * The Datastar expression at [offset] in a Kotlin file with [modules]: in kotlinx.html's attributes
 * form or an HTML string whatever the file has, and in the string of a DSL call only where the file
 * can call the DSL and the call is not somebody else's.
 */
internal fun Analyzer.expressionAtCaret(
    src: String,
    offset: Int,
    prefix: String,
    modules: FileModules,
): ExpressionAtCaret? {
    val expression = expressionAtCaret(src, offset, kotlin = true, prefix = prefix) ?: return null
    if (inAttributesIndex(src, offset) || htmlStringAt(src, offset, modules) != null) return expression
    if (!modules.dsl) return null
    val call = callAround(src, offset, catalog.expressionCallSites.keys)
    return expression.takeUnless { call != null && modules.foreign(call.name, call.nameStart) }
}

/** Whether [offset] is in the arguments of a call named as one of the DSL's that is not the DSL's: the file cannot call it, or it is somebody else's. */
internal fun Analyzer.inForeignDslCall(
    src: String,
    offset: Int,
    modules: FileModules,
): Boolean {
    val call = callAround(src, offset, dslNames) ?: return false
    return !modules.dsl || modules.foreign(call.name, call.nameStart)
}

/**
 * What is written before [offset] of a selector the caret stands in, or null: `selector = "#|"` on
 * any Streamlord call, or the first argument of `removeElements`.
 */
internal fun Analyzer.selectorBefore(
    src: String,
    offset: Int,
    modules: FileModules,
): String? {
    val sites = modules.callNames(this).takeIf { it.isNotEmpty() } ?: return null
    val site = findCallSites(src, sites, lex(src)).firstOrNull { it.openParen < offset && offset <= it.closeParen } ?: return null
    val arg =
        site.args.firstOrNull { a ->
            val s = a.string
            s != null && s.start < offset && offset <= s.contentEnd + (if (s.unterminated) 1 else 0)
        } ?: return null
    val selector = arg.named in SELECTOR_ARGUMENTS || (arg.named == null && site.name == "removeElements" && site.args.indexOf(arg) == 0)
    return if (selector) src.substring(arg.string!!.contentStart, offset) else null
}
