package io.github.markusaugust.streamlord.intellij.navigation

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement

/**
 * Go to declaration in a template IntelliJ reads as plain text, whose one text element holds no
 * references: from a `$name` there to every place the project defines the signal.
 */
class SignalGotoDeclarationHandler : GotoDeclarationHandler {
    override fun getGotoDeclarationTargets(
        sourceElement: PsiElement?,
        offset: Int,
        editor: Editor,
    ): Array<PsiElement>? {
        val file = sourceElement?.containingFile ?: return null
        val reference = SignalSites.textReferenceAt(file, offset, start = false) ?: return null
        return reference
            .multiResolve(false)
            .mapNotNull { it.element }
            .toTypedArray<PsiElement>()
            .takeIf { it.isNotEmpty() }
    }
}
