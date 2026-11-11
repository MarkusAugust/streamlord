package io.github.markusaugust.streamlord.editor

import io.github.markusaugust.streamlord.analysis.Analyzer
import io.github.markusaugust.streamlord.analysis.ExpressionAtCaret
import io.github.markusaugust.streamlord.analysis.KotlinString
import io.github.markusaugust.streamlord.analysis.USES_HTML_DSL
import io.github.markusaugust.streamlord.analysis.expressionAtCaret
import io.github.markusaugust.streamlord.analysis.findCallSites
import io.github.markusaugust.streamlord.analysis.isHtmlString
import io.github.markusaugust.streamlord.core.regex

/*
 * Which of Streamlord's modules a Kotlin file can use, as far as its imports prove it: what an
 * editor without the project's classpath has to go on. The editor features read a DSL call's
 * string as Datastar only where the file can call the DSL. Whether the project has Streamlord at
 * all no text can prove: a parent pom, a published catalog or a platform names it out of sight.
 */

private val OFFICIAL_SDK_IMPORT = regex("""^[ \t]*import[ \t]+dev\.datastar\.kotlin\.sdk\b""", RegexOption.MULTILINE)
private val STREAMLORD_IMPORT = regex("""^[ \t]*import[ \t]+io\.github\.markusaugust\.streamlord\.""", RegexOption.MULTILINE)
private val IMPORT_OR_PACKAGE = regex("import|package")
private val SELECTOR_ARGUMENTS = setOf("selector", "viewTransitionSelector")

/** [core] is null where the imports leave it open, and the analysis then takes it for there. */
internal class Modules(
    val core: Boolean?,
    val htmlDsl: Boolean,
)

/**
 * The modules the Kotlin file [src] can use. The DSL's functions are top-level functions of
 * `io.github.markusaugust.streamlord.html`, which a file calls only through an import of that
 * package or from inside it. Core's are members of a stream a file may be handed without any
 * import, so core is left open, except in a file that imports the official SDK and nothing of
 * Streamlord.
 */
internal fun Analyzer.modulesOf(src: String): Modules {
    if (OFFICIAL_SDK_IMPORT.containsMatchIn(src) && !STREAMLORD_IMPORT.containsMatchIn(src)) return Modules(core = false, htmlDsl = false)
    return Modules(core = null, htmlDsl = importsDsl(src))
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
): KotlinString? = htmlStringAt(src, offset)?.takeIf { modules.core != false || isHtmlString(src, it) }

/** Whether [offset] lies in an `attributes["data-..."] = "..."`, kotlinx.html's own form, which is Datastar in every project. */
private fun Analyzer.inAttributesIndex(
    src: String,
    offset: Int,
): Boolean = attributesIndexes(src).any { it.nameStart <= offset && offset <= (it.value?.end ?: it.nameStart) }

/**
 * The Datastar expression at [offset] in a Kotlin file with [modules]: in kotlinx.html's attributes
 * form or an HTML string whatever the file has, and in the string of a DSL call only where the file
 * can call the DSL.
 */
internal fun Analyzer.expressionAtCaret(
    src: String,
    offset: Int,
    prefix: String,
    modules: Modules,
): ExpressionAtCaret? {
    val expression = expressionAtCaret(src, offset, kotlin = true, prefix = prefix)
    if (expression == null || modules.htmlDsl) return expression
    return expression.takeIf { inAttributesIndex(src, offset) || htmlStringAt(src, offset, modules) != null }
}

/** The call sites whose names are Streamlord's in a file with [modules]: none without core, none of the DSL's without it. */
private fun Analyzer.streamlordSites(modules: Modules): Set<String> =
    when {
        modules.core == false -> emptySet()
        !modules.htmlDsl -> catalog.allCallSiteNames - dslNames
        else -> catalog.allCallSiteNames
    }

/** Whether [offset] is in the arguments of a call named as one of the DSL's in a file that cannot call the DSL. */
internal fun Analyzer.inForeignDslCall(
    src: String,
    offset: Int,
    modules: Modules,
): Boolean = !modules.htmlDsl && findCallSites(src, dslNames, lex(src)).any { it.openParen < offset && offset <= it.closeParen }

/**
 * What is written before [offset] of a selector the caret stands in, or null: `selector = "#|"` on
 * any Streamlord call, or the first argument of `removeElements`.
 */
internal fun Analyzer.selectorBefore(
    src: String,
    offset: Int,
    modules: Modules,
): String? {
    val sites = streamlordSites(modules)
    if (sites.isEmpty()) return null
    val site = findCallSites(src, sites, lex(src)).firstOrNull { it.openParen < offset && offset <= it.closeParen } ?: return null
    val arg =
        site.args.firstOrNull { a ->
            val s = a.string
            s != null && s.start < offset && offset <= s.contentEnd + (if (s.unterminated) 1 else 0)
        } ?: return null
    val selector = arg.named in SELECTOR_ARGUMENTS || (arg.named == null && site.name == "removeElements" && site.args.indexOf(arg) == 0)
    return if (selector) src.substring(arg.string!!.contentStart, offset) else null
}
