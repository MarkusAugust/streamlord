package io.github.markusaugust.streamlord.intellij.completion

import com.intellij.lang.html.HTMLLanguage
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.psi.PsiDocumentManager
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
    /**
     * The expression at [offset] in [file]. With [fromDocument] the editor's text is read rather than
     * the PSI's, which a typed handler finds behind it: the document is committed only after.
     */
    fun expressionAt(
        file: PsiFile,
        offset: Int,
        fromDocument: Boolean = false,
    ): ExpressionAtCaret? {
        val analysis = StreamlordAnalysis.getInstance(file.project)
        val documents = PsiDocumentManager.getInstance(file.project)

        fun textOf(f: PsiFile): String = (if (fromDocument) documents.getDocument(f)?.text else null) ?: f.text
        if (file is KtFile) return inKotlin(analysis, file, textOf(file), offset)
        // HTML injected into a Kotlin string is read on the Kotlin side, where the string is decoded: the
        // injected text keeps Kotlin's escapes and leaves out what a template writes.
        val manager = InjectedLanguageManager.getInstance(file.project)
        if (manager.isInjectedFragment(file)) {
            val host = manager.getInjectionHost(file)?.containingFile
            if (host is KtFile) {
                return inKotlin(analysis, host, textOf(host), manager.injectedToHost(file, offset))
            }
        }
        // Markup: an HTML file, a template read as text, or a template whose plugin gives it an HTML view beside its own
        // language (FreeMarker, Thymeleaf), where parameter info and hover are handed the template's file and not the view.
        val markup =
            file.language.isKindOf(HTMLLanguage.INSTANCE) ||
                StreamlordAnalysis.isTemplateText(file) ||
                file.viewProvider.allFiles.any { it.language.isKindOf(HTMLLanguage.INSTANCE) }
        if (!markup) return null
        return analysis.analyzer.expressionAtCaret(textOf(file), offset, false, analysis.prefix)
    }

    /** The expression in Kotlin: in an HTML string, or in the string of a DSL call where the module has the DSL. */
    private fun inKotlin(
        analysis: StreamlordAnalysis,
        file: KtFile,
        text: String,
        offset: Int,
    ): ExpressionAtCaret? {
        val calls = StreamlordCalls.of(file)
        // The analysis reads an HTML string first and a DSL call's string after; without the DSL only the first is Datastar.
        if (!calls.dsl && calls.htmlStringAt(analysis, text, offset) == null) return null
        return analysis.analyzer.expressionAtCaret(text, offset, true, analysis.prefix)
    }
}
