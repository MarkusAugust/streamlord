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

internal class Modules(
    val core: Boolean,
    val htmlDsl: Boolean,
    /** Whether a call found by its name is somebody else's, as [Source.foreign] says. */
    val foreign: (name: String, nameStart: Int) -> Boolean,
)

/**
 * The modules [source] can use. Where the editor cannot tell, a Kotlin file has the DSL through an
 * import of `io.github.markusaugust.streamlord.html` or from inside that package, and core unless
 * it imports the official SDK and nothing of Streamlord: core's functions are members of a stream
 * a file may be handed without any import. Markup has both.
 */
internal fun Analyzer.modulesOf(source: Source): Modules {
    val text = source.text
    if (source.language != SourceLanguage.KOTLIN) return Modules(source.core ?: true, source.htmlDsl ?: true, source.foreign)
    val core = source.core ?: !(OFFICIAL_SDK_IMPORT.containsMatchIn(text) && !STREAMLORD_IMPORT.containsMatchIn(text))
    return Modules(core, core && (source.htmlDsl ?: importsDsl(text)), source.foreign)
}

/** Whether [src] imports the DSL, or is in its package, in its code: a line in a comment or a raw string imports nothing. */
private fun Analyzer.importsDsl(src: String): Boolean {
    // A file read from disk may start with a byte order mark, which would hide an import on its first line.
    val text = src.removePrefix("﻿")
    if (!USES_HTML_DSL.containsMatchIn(text)) return false
    val mask = lex(text).mask
    return USES_HTML_DSL.findAll(text).any { m -> mask[m.range.first + IMPORT_OR_PACKAGE.find(m.value)!!.range.first] }
}

/** The HTML string at [offset]: one that looks like HTML, or the markup handed to a Streamlord call where the file has core. */
internal fun Analyzer.htmlStringAt(
    src: String,
    offset: Int,
    modules: Modules,
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
    modules: Modules,
): ExpressionAtCaret? {
    val expression = expressionAtCaret(src, offset, kotlin = true, prefix = prefix) ?: return null
    if (inAttributesIndex(src, offset) || htmlStringAt(src, offset, modules) != null) return expression
    if (!modules.htmlDsl) return null
    val call = callAround(src, offset, catalog.expressionCallSites.keys)
    return expression.takeUnless { call != null && modules.foreign(call.name, call.nameStart) }
}

/** Whether [offset] is in the arguments of a call named as one of the DSL's that is not the DSL's: the file cannot call it, or it is somebody else's. */
internal fun Analyzer.inForeignDslCall(
    src: String,
    offset: Int,
    modules: Modules,
): Boolean {
    val call = callAround(src, offset, dslNames) ?: return false
    return !modules.htmlDsl || modules.foreign(call.name, call.nameStart)
}

/**
 * What is written before [offset] of a selector the caret stands in, or null: `selector = "#|"` on
 * any Streamlord call, or the first argument of `removeElements`.
 */
internal fun Analyzer.selectorBefore(
    src: String,
    offset: Int,
    modules: Modules,
): String? {
    val sites =
        when {
            !modules.core -> return null
            !modules.htmlDsl -> catalog.allCallSiteNames - dslNames
            else -> catalog.allCallSiteNames
        }
    val site = findCallSites(src, sites, lex(src)).firstOrNull { it.openParen < offset && offset <= it.closeParen } ?: return null
    val arg =
        site.args.firstOrNull { a ->
            val s = a.string
            s != null && s.start < offset && offset <= s.contentEnd + (if (s.unterminated) 1 else 0)
        } ?: return null
    val selector = arg.named in SELECTOR_ARGUMENTS || (arg.named == null && site.name == "removeElements" && site.args.indexOf(arg) == 0)
    return if (selector) src.substring(arg.string!!.contentStart, offset) else null
}
