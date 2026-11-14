package io.github.markusaugust.streamlord.intellij.completion

import com.intellij.lang.parameterInfo.CreateParameterInfoContext
import com.intellij.lang.parameterInfo.ParameterInfoContext
import com.intellij.lang.parameterInfo.ParameterInfoHandler
import com.intellij.lang.parameterInfo.ParameterInfoUIContext
import com.intellij.lang.parameterInfo.UpdateParameterInfoContext
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiElement
import io.github.markusaugust.streamlord.analysis.SignatureHelp
import io.github.markusaugust.streamlord.analysis.markdownToHtml
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

    /** The signature with the parameter at the caret in bold, and under it what that parameter is. */
    override fun updateUI(
        p: SignatureHelp,
        context: ParameterInfoUIContext,
    ) {
        val n = context.currentParameterIndex.coerceIn(0, maxOf(0, p.parameters.size - 1))
        val current = p.parameters.getOrNull(n)
        if (current == null) {
            context.setupUIComponentPresentation(p.label, -1, -1, false, false, false, context.defaultParameterColor)
            return
        }
        val label = p.label
        val signature =
            escape(label.substring(0, current.first)) + "<b>" + escape(label.substring(current)) + "</b>" +
                escape(label.substring(current.last + 1))
        context.setupRawUIComponentPresentation(signature + p.parameterDocs.getOrNull(n)?.let(::markdownToHtml).orEmpty())
    }

    private fun escape(text: String) = StringUtil.escapeXmlEntities(text)
}
