package io.github.markusaugust.streamlord.intellij.completion

import com.intellij.lang.parameterInfo.CreateParameterInfoContext
import com.intellij.lang.parameterInfo.ParameterInfoContext
import com.intellij.lang.parameterInfo.ParameterInfoHandler
import com.intellij.lang.parameterInfo.ParameterInfoUIContext
import com.intellij.lang.parameterInfo.UpdateParameterInfoContext
import com.intellij.openapi.project.DumbAware
import com.intellij.psi.PsiElement
import io.github.markusaugust.streamlord.analysis.ActionCall
import io.github.markusaugust.streamlord.analysis.ActionSpec
import io.github.markusaugust.streamlord.analysis.actionCallAt
import io.github.markusaugust.streamlord.intellij.analysis.StreamlordAnalysis

/**
 * The parameters of a Datastar action while the caret is in its call (Ctrl+P), in markup and in
 * Kotlin strings alike. Registered first for Kotlin, it answers only inside a Datastar expression,
 * where it stands in for the Kotlin plugin's help with the call around the string.
 */
class DatastarParameterInfoHandler :
    ParameterInfoHandler<PsiElement, ActionSpec>,
    DumbAware {
    /** The action call at the context's caret, with its spec, or null outside one. */
    private fun callAt(context: ParameterInfoContext): Pair<ActionCall, ActionSpec>? {
        val expression = DatastarCaret.expressionAt(context.file, context.offset) ?: return null
        if (!expression.allowActions) return null
        val call = actionCallAt(expression.before, expression.before.length) ?: return null
        val action = StreamlordAnalysis.getInstance(context.project).catalog.actionsByName[call.action] ?: return null
        return call to action
    }

    /** The element the hint is anchored to: the leaf at the caret, or the file at its very end. */
    private fun owner(context: ParameterInfoContext): PsiElement = context.file.findElementAt(maxOf(0, context.offset - 1)) ?: context.file

    override fun findElementForParameterInfo(context: CreateParameterInfoContext): PsiElement? {
        val (_, action) = callAt(context) ?: return null
        context.itemsToShow = arrayOf(action)
        return owner(context)
    }

    override fun showParameterInfo(
        element: PsiElement,
        context: CreateParameterInfoContext,
    ) {
        context.showHint(element, context.offset, this)
    }

    override fun findElementForUpdatingParameterInfo(context: UpdateParameterInfoContext): PsiElement? =
        if (callAt(context) == null) null else owner(context)

    override fun updateParameterInfo(
        parameterOwner: PsiElement,
        context: UpdateParameterInfoContext,
    ) {
        val (call, _) = callAt(context) ?: return context.removeHint()
        context.setCurrentParameter(call.argument)
    }

    override fun updateUI(
        p: ActionSpec,
        context: ParameterInfoUIContext,
    ) {
        val parameters = p.parameters
        val current = context.currentParameterIndex.coerceIn(0, maxOf(0, parameters.size - 1))
        // The parameters follow one another in the signature, after its parenthesis.
        var from = p.signature.indexOf('(') + 1
        var start = -1
        var end = -1
        parameters.forEachIndexed { i, name ->
            val at = p.signature.indexOf(name, from)
            if (i == current && at >= 0) {
                start = at
                end = at + name.length
            }
            if (at >= 0) from = at + name.length
        }
        context.setupUIComponentPresentation(p.signature, start, end, false, false, false, context.defaultParameterColor)
    }
}
