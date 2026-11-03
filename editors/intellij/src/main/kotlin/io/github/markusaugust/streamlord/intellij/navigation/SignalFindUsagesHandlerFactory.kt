package io.github.markusaugust.streamlord.intellij.navigation

import com.intellij.find.findUsages.FindUsagesHandler
import com.intellij.find.findUsages.FindUsagesHandlerFactory
import com.intellij.psi.PsiElement

/** Find usages of a signal: every place the project defines it and every expression that reads it. */
class SignalFindUsagesHandlerFactory : FindUsagesHandlerFactory() {
    override fun canFindUsages(element: PsiElement): Boolean = element is SignalTarget

    override fun createFindUsagesHandler(
        element: PsiElement,
        forHighlightUsages: Boolean,
    ): FindUsagesHandler = object : FindUsagesHandler(element) {}
}
