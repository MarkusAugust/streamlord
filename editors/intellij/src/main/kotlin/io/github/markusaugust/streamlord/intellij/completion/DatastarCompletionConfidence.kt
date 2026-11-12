package io.github.markusaugust.streamlord.intellij.completion

import com.intellij.codeInsight.completion.CompletionConfidence
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.util.ThreeState
import io.github.markusaugust.streamlord.analysis.complete
import io.github.markusaugust.streamlord.intellij.analysis.StreamlordAnalysis

/**
 * The platform holds the automatic completion popup back inside string literals. Where the
 * analysis has something to offer, a signal, an action, a selector or a name, the popup is let
 * through; everywhere else the usual rule stands.
 */
class DatastarCompletionConfidence : CompletionConfidence() {
    override fun shouldSkipAutopopup(
        editor: Editor,
        contextElement: PsiElement,
        psiFile: PsiFile,
        offset: Int,
    ): ThreeState {
        val asked = DatastarCaret.at(psiFile, offset) ?: return ThreeState.UNSURE
        val found = StreamlordAnalysis.getInstance(psiFile.project).analyzer.complete(asked.source, asked.offset)
        return if (found.alone || found.items.isNotEmpty()) ThreeState.NO else ThreeState.UNSURE
    }
}
