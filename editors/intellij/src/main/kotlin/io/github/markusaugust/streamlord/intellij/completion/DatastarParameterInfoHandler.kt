package io.github.markusaugust.streamlord.intellij.completion

import com.intellij.lang.parameterInfo.CreateParameterInfoContext
import com.intellij.lang.parameterInfo.ParameterInfoContext
import com.intellij.lang.parameterInfo.ParameterInfoHandler
import com.intellij.lang.parameterInfo.ParameterInfoUIContext
import com.intellij.lang.parameterInfo.UpdateParameterInfoContext
import com.intellij.openapi.project.DumbAware
import com.intellij.psi.PsiElement
import io.github.markusaugust.streamlord.analysis.SignatureHelp
import io.github.markusaugust.streamlord.analysis.signatureHelp
import io.github.markusaugust.streamlord.intellij.analysis.StreamlordAnalysis

/**
 * The parameters of a Datastar action while the caret is in its call (Ctrl+P), in markup and in
 * Kotlin strings alike. Registered first for Kotlin, it answers only inside a Datastar expression,
 * where it stands in for the Kotlin plugin's help with the call around the string.
 */
class DatastarParameterInfoHandler :
    ParameterInfoHandler<PsiElement, SignatureHelp>,
    DumbAware {
    /** The help at the context's caret, or null outside an action call. */
    private fun helpAt(context: ParameterInfoContext): SignatureHelp? {
        val asked = DatastarCaret.at(context.file, context.offset) ?: return null
        return StreamlordAnalysis.getInstance(context.project).analyzer.signatureHelp(asked.source, asked.offset)
    }

    /** The element the hint is anchored to: the leaf at the caret, or the file at its very end. */
    private fun owner(context: ParameterInfoContext): PsiElement = context.file.findElementAt(maxOf(0, context.offset - 1)) ?: context.file

    override fun findElementForParameterInfo(context: CreateParameterInfoContext): PsiElement? {
        context.itemsToShow = arrayOf(helpAt(context) ?: return null)
        return owner(context)
    }

    override fun showParameterInfo(
        element: PsiElement,
        context: CreateParameterInfoContext,
    ) {
        context.showHint(element, context.offset, this)
    }

    override fun findElementForUpdatingParameterInfo(context: UpdateParameterInfoContext): PsiElement? =
        if (helpAt(context) == null) null else owner(context)

    override fun updateParameterInfo(
        parameterOwner: PsiElement,
        context: UpdateParameterInfoContext,
    ) {
        context.setCurrentParameter((helpAt(context) ?: return context.removeHint()).active)
    }

    override fun updateUI(
        p: SignatureHelp,
        context: ParameterInfoUIContext,
    ) {
        val current = p.parameters.getOrNull(context.currentParameterIndex.coerceIn(0, maxOf(0, p.parameters.size - 1)))
        context.setupUIComponentPresentation(
            p.label,
            current?.first ?: -1,
            current?.let { it.last + 1 } ?: -1,
            false,
            false,
            false,
            context.defaultParameterColor,
        )
    }
}
