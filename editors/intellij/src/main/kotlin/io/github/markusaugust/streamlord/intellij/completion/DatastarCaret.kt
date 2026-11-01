package io.github.markusaugust.streamlord.intellij.completion

import com.intellij.lang.html.HTMLLanguage
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.psi.PsiFile
import io.github.markusaugust.streamlord.analysis.ExpressionAtCaret
import io.github.markusaugust.streamlord.analysis.expressionAtCaret
import io.github.markusaugust.streamlord.intellij.analysis.StreamlordAnalysis
import org.jetbrains.kotlin.psi.KtFile

/**
 * The Datastar expression a caret stands in, in any file the plugin reads, by the analysis's
 * [expressionAtCaret]: this picks the text it reads, the file's own for markup and Kotlin, the
 * host's for HTML injected into a Kotlin string.
 */
object DatastarCaret {
    fun expressionAt(
        file: PsiFile,
        offset: Int,
    ): ExpressionAtCaret? {
        val analysis = StreamlordAnalysis.getInstance(file.project)
        if (file is KtFile) return analysis.analyzer.expressionAtCaret(file.text, offset, true, analysis.prefix)
        // HTML injected into a Kotlin string is read on the Kotlin side, where the string is decoded: the
        // injected text keeps Kotlin's escapes and leaves out what a template writes.
        val manager = InjectedLanguageManager.getInstance(file.project)
        if (manager.isInjectedFragment(file)) {
            val host = manager.getInjectionHost(file)?.containingFile
            if (host is KtFile) {
                return analysis.analyzer.expressionAtCaret(
                    host.text,
                    manager.injectedToHost(file, offset),
                    true,
                    analysis.prefix,
                )
            }
        }
        if (!file.language.isKindOf(HTMLLanguage.INSTANCE) && !StreamlordAnalysis.isTemplateText(file)) return null
        return analysis.analyzer.expressionAtCaret(file.text, offset, false, analysis.prefix)
    }
}
