package io.github.markusaugust.streamlord.intellij.completion

import com.intellij.lang.html.HTMLLanguage
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.psi.PsiFile
import io.github.markusaugust.streamlord.analysis.KotlinString
import io.github.markusaugust.streamlord.analysis.ValueKind
import io.github.markusaugust.streamlord.analysis.findCallSites
import io.github.markusaugust.streamlord.analysis.valueAtCaret
import io.github.markusaugust.streamlord.intellij.analysis.StreamlordAnalysis
import org.jetbrains.kotlin.psi.KtFile

/** The Datastar expression before a caret, as Datastar is handed it, and what belongs there. */
data class ExpressionAtCaret(
    val before: String,
    /** Whether actions belong there: an expression, not a signal name. */
    val allowActions: Boolean,
    /** Whether `patch` is in scope: data-on-signal-patch only. */
    val patchInScope: Boolean,
)

/**
 * The Datastar expression a caret stands in, in any file the plugin reads: an attribute value in
 * markup, an HTML string in Kotlin, or the string handed to a DSL call, as `caret.ts` reads it for
 * VS Code. Kotlin's escapes and character references are decoded.
 */
object DatastarCaret {
    fun expressionAt(
        file: PsiFile,
        offset: Int,
    ): ExpressionAtCaret? {
        val analysis = StreamlordAnalysis.getInstance(file.project)
        val src = file.text
        if (offset !in 0..src.length) return null
        if (file is KtFile) return inKotlin(analysis, src, offset)
        // HTML injected into a Kotlin string is read on the Kotlin side, where the string is decoded: the
        // injected text keeps Kotlin's escapes and leaves out what a template writes.
        val manager = InjectedLanguageManager.getInstance(file.project)
        if (manager.isInjectedFragment(file)) {
            val host = manager.getInjectionHost(file)?.containingFile
            if (host is KtFile) return inKotlin(analysis, host.text, manager.injectedToHost(file, offset))
        }
        if (!file.language.isKindOf(HTMLLanguage.INSTANCE) && !StreamlordAnalysis.isTemplateText(file)) return null
        return inMarkup(analysis, src, offset)
    }

    private fun inMarkup(
        analysis: StreamlordAnalysis,
        markup: String,
        caret: Int,
    ): ExpressionAtCaret? {
        val at = valueAtCaret(markup, caret, analysis.prefix) ?: return null
        val spec = analysis.catalog.parseAttributeName(at.attribute.lowercase(), analysis.prefix)?.spec ?: return null
        if (spec.valueKind != ValueKind.EXPRESSION && spec.valueKind != ValueKind.SIGNAL) return null
        return ExpressionAtCaret(at.before, spec.valueKind == ValueKind.EXPRESSION, spec.name == "on-signal-patch")
    }

    private fun inKotlin(
        analysis: StreamlordAnalysis,
        src: String,
        offset: Int,
    ): ExpressionAtCaret? {
        val analyzer = analysis.analyzer
        val catalog = analysis.catalog
        analyzer.htmlStringAt(src, offset)?.let { html ->
            if (offset <= html.contentStart || offset > html.contentEnd) return null
            return inMarkup(analysis, html.text, decodedIndex(html, offset))
        }
        val site =
            findCallSites(src, catalog.expressionCallSites.keys, analyzer.lex(src)).firstOrNull {
                it.openParen < offset &&
                    offset <= it.closeParen
            }
                ?: return null
        val spec = catalog.expressionCallSites[site.name] ?: return null
        val s =
            site.selectString(spec) ?: site.args.mapNotNull { it.string }.firstOrNull { it.start < offset && offset <= it.end }
                ?: return null
        if (offset <= s.start || offset > s.contentEnd + (if (s.unterminated) 1 else 0)) return null
        // dataOnSignalPatch also writes the filter, so the call is looked up among the attribute's own names.
        val patch = catalog.attributesByName["on-signal-patch"]?.kotlin?.contains(site.name) == true
        return ExpressionAtCaret(s.text.substring(0, decodedIndex(s, offset)), allowActions = true, patchInScope = patch)
    }

    /** The decoded index of a source offset in a Kotlin string: the last character that starts at or before it. */
    private fun decodedIndex(
        s: KotlinString,
        offset: Int,
    ): Int {
        var i = 0
        while (i + 1 < s.map.size && s.map[i + 1] <= offset) i++
        return minOf(i, s.text.length)
    }
}
