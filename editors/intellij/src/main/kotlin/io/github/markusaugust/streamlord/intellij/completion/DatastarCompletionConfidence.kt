package io.github.markusaugust.streamlord.intellij.completion

import com.intellij.codeInsight.completion.CompletionConfidence
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.util.ThreeState
import io.github.markusaugust.streamlord.analysis.offers
import io.github.markusaugust.streamlord.intellij.analysis.StreamlordAnalysis
import org.jetbrains.kotlin.psi.KtFile

/**
 * The platform holds the automatic completion popup back inside string literals. Where the
 * analysis has something to offer, a signal, an action or a selector, and in Kotlin an attribute
 * name, the popup is let through; everywhere else the usual rule stands.
 */
class DatastarCompletionConfidence : CompletionConfidence() {
    override fun shouldSkipAutopopup(
        editor: Editor,
        contextElement: PsiElement,
        psiFile: PsiFile,
        offset: Int,
    ): ThreeState {
        val asked = DatastarCaret.at(psiFile, offset) ?: return ThreeState.UNSURE
        // In a markup file a name in a tag is the HTML plugin's, or the official Datastar plugin's, to pop up for.
        val offered = StreamlordAnalysis.getInstance(psiFile.project).analyzer.offers(asked.source, asked.offset, names = psiFile is KtFile)
        return if (offered) ThreeState.NO else ThreeState.UNSURE
    }
}
