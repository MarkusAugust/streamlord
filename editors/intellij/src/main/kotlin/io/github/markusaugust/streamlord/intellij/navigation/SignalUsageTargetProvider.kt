package io.github.markusaugust.streamlord.intellij.navigation

import com.intellij.find.findUsages.PsiElement2UsageTargetAdapter
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiFile
import com.intellij.usages.UsageTarget
import com.intellij.usages.UsageTargetProvider

/**
 * Find usages from a signal in a template IntelliJ reads as plain text, whose one text element holds
 * no reference for the platform to start from: the signal at the caret, read or defined there.
 */
class SignalUsageTargetProvider : UsageTargetProvider {
    override fun getTargets(
        editor: Editor,
        file: PsiFile,
    ): Array<UsageTarget>? {
        val reference = SignalSites.textReferenceAt(file, editor.caretModel.offset, start = false) ?: return null
        val target = SignalTarget.of(file, reference.signal).firstOrNull() ?: return null
        return arrayOf(PsiElement2UsageTargetAdapter(target, true))
    }
}
