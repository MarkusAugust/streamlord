package io.github.markusaugust.streamlord.intellij.navigation

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.impl.source.resolve.reference.impl.PsiMultiReference

/**
 * Go to declaration from a signal read where the platform's own lookup falls short. In a template
 * IntelliJ reads as plain text, whose one text element holds no references. And where another
 * plugin's reference stands at the same place, the official Datastar plugin's for a signal of the
 * same HTML file, which the platform would list beside this plugin's own target for that place:
 * there the targets are this plugin's alone, which hold every place the project defines it.
 */
class SignalGotoDeclarationHandler : GotoDeclarationHandler {
    override fun getGotoDeclarationTargets(
        sourceElement: PsiElement?,
        offset: Int,
        editor: Editor,
    ): Array<PsiElement>? {
        val file = sourceElement?.containingFile ?: return null
        val signal =
            SignalSites.textReferenceAt(file, offset, start = false)?.signal
                ?: run {
                    val found = file.findReferenceAt(offset) as? PsiMultiReference ?: return null
                    if (found.references.all { it is SignalPsiReference }) return null
                    found.references
                        .filterIsInstance<SignalPsiReference>()
                        .firstOrNull { !it.defines }
                        ?.signal
                }
                ?: return null
        return SignalTarget.of(file, signal).toTypedArray<PsiElement>().takeIf { it.isNotEmpty() }
    }
}
