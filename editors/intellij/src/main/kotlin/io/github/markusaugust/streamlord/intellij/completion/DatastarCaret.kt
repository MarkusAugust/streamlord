package io.github.markusaugust.streamlord.intellij.completion

import com.intellij.lang.html.HTMLLanguage
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.psi.PsiFile
import io.github.markusaugust.streamlord.analysis.ExpressionAtCaret
import io.github.markusaugust.streamlord.analysis.expressionAtCaret
import io.github.markusaugust.streamlord.intellij.analysis.StreamlordAnalysis
import io.github.markusaugust.streamlord.intellij.analysis.StreamlordCalls
import org.jetbrains.kotlin.psi.KtFile

/**
 * The Datastar expression a caret stands in, in any file the plugin reads, by the analysis's
 * [expressionAtCaret]: this picks the text it reads, the file's own for markup and Kotlin, the
 * host's for HTML injected into a Kotlin string.
 */
object DatastarCaret {
    /** The expression at [offset] in [file]. */
    fun expressionAt(
        file: PsiFile,
        offset: Int,
    ): ExpressionAtCaret? {
        val analysis = StreamlordAnalysis.getInstance(file.project)
        if (file is KtFile) return inKotlin(analysis, file, file.text, offset)
        // HTML injected into a Kotlin string is read on the Kotlin side, where the string is decoded: the
        // injected text keeps Kotlin's escapes and leaves out what a template writes.
        val manager = InjectedLanguageManager.getInstance(file.project)
        if (manager.isInjectedFragment(file)) {
            val host = manager.getInjectionHost(file)?.containingFile
            if (host is KtFile) {
                return inKotlin(analysis, host, host.text, manager.injectedToHost(file, offset))
            }
        }
        // Markup: an HTML file, a template read as text, or a template whose plugin gives it an HTML view beside its own
        // language (FreeMarker, Thymeleaf), where parameter info and hover are handed the template's file and not the view.
        val markup =
            file.language.isKindOf(HTMLLanguage.INSTANCE) ||
                StreamlordAnalysis.isTemplateText(file) ||
                file.viewProvider.allFiles.any { it.language.isKindOf(HTMLLanguage.INSTANCE) }
        if (!markup) return null
        return analysis.analyzer.expressionAtCaret(file.text, offset, false, analysis.prefix)
    }

    /**
     * The expression in Kotlin: in kotlinx.html's `attributes["data-..."] =` or an HTML string whatever the project has, and in
     * the string of a DSL call where the module has the DSL and the call is Streamlord's.
     */
    private fun inKotlin(
        analysis: StreamlordAnalysis,
        file: KtFile,
        text: String,
        offset: Int,
    ): ExpressionAtCaret? {
        val analyzer = analysis.analyzer
        val datastarAnyway =
            analyzer.attributesIndexes(text).any { it.nameStart <= offset && offset <= (it.value?.end ?: it.nameStart) } ||
                StreamlordCalls.of(file).htmlStringAt(analysis, text, offset) != null
        if (!datastarAnyway) {
            if (!StreamlordCalls.of(file).dsl) return null
            if (analysis.isForeignCallAt(file, offset, analyzer.catalog.expressionCallSites.keys)) return null
        }
        return analyzer.expressionAtCaret(text, offset, true, analysis.prefix)
    }
}
