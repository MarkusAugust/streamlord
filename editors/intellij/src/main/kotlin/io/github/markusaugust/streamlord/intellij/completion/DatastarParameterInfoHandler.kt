package io.github.markusaugust.streamlord.intellij.completion

import com.intellij.lang.parameterInfo.CreateParameterInfoContext
import com.intellij.lang.parameterInfo.ParameterInfoHandler
import com.intellij.lang.parameterInfo.ParameterInfoUIContext
import com.intellij.lang.parameterInfo.UpdateParameterInfoContext
import com.intellij.openapi.project.DumbAware
import com.intellij.psi.PsiElement
import io.github.markusaugust.streamlord.analysis.ActionSpec

/** The parameters of a Datastar action while the caret is in its call (Ctrl+P), in markup and in Kotlin strings alike. */
class DatastarParameterInfoHandler :
    ParameterInfoHandler<PsiElement, ActionSpec>,
    DumbAware {
    override fun findElementForParameterInfo(context: CreateParameterInfoContext): PsiElement? = null

    override fun showParameterInfo(
        element: PsiElement,
        context: CreateParameterInfoContext,
    ) = Unit

    override fun findElementForUpdatingParameterInfo(context: UpdateParameterInfoContext): PsiElement? = null

    override fun updateParameterInfo(
        parameterOwner: PsiElement,
        context: UpdateParameterInfoContext,
    ) = Unit

    override fun updateUI(
        p: ActionSpec,
        context: ParameterInfoUIContext,
    ) = Unit
}
