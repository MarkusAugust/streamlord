package io.github.markusaugust.streamlord.intellij.navigation

import com.intellij.find.findUsages.FindUsagesHandler
import com.intellij.find.findUsages.FindUsagesHandlerFactory
import com.intellij.psi.PsiElement

/**
 * Find usages of a signal: every place the project defines it and every expression that reads it.
 * Started from the official Datastar plugin's element for a place, which has no handler of its own,
 * it is the signal defined there that is searched.
 */
class SignalFindUsagesHandlerFactory : FindUsagesHandlerFactory() {
    override fun canFindUsages(element: PsiElement): Boolean = element is SignalTarget || SignalTarget.standingFor(element) != null

    override fun createFindUsagesHandler(
        element: PsiElement,
        forHighlightUsages: Boolean,
    ): FindUsagesHandler = object : FindUsagesHandler(element as? SignalTarget ?: SignalTarget.standingFor(element) ?: element) {}
}
