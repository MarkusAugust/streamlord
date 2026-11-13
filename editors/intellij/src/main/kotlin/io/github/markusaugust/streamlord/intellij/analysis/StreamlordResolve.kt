package io.github.markusaugust.streamlord.intellij.analysis

import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import io.github.markusaugust.streamlord.analysis.CallSite
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNameReferenceExpression

/**
 * Names first, resolve as confirmation. The scanner finds a call by its name, which works before
 * the indexes are ready and in a file that does not compile. When the Kotlin plugin can resolve
 * the call and it is not Streamlord's, its name is handed to the analysis as one of
 * [Source.foreign][io.github.markusaugust.streamlord.analysis.Source.foreign]:
 * the strings of a `patchElements` of the user's own, or of the official SDK's, are then judged as
 * any string handed to an unknown function.
 */
object StreamlordResolve {
    const val PACKAGE = "io.github.markusaugust.streamlord"

    fun mayBeStreamlord(
        file: KtFile,
        site: CallSite,
    ): Boolean {
        val callee = calleeAt(file, site) ?: return true
        val target = callee.mainReference.resolve() ?: return true
        return isStreamlord(target)
    }

    /** The reference expression of the call whose name starts at the site's offset, if the PSI agrees with the scanner. */
    fun calleeAt(
        file: KtFile,
        site: CallSite,
    ): KtNameReferenceExpression? {
        val leaf = file.findElementAt(site.nameStart) ?: return null
        val ref = PsiTreeUtil.getParentOfType(leaf, KtNameReferenceExpression::class.java, false) ?: return null
        if (ref.textRange.startOffset != site.nameStart || ref.getReferencedName() != site.name) return null
        val call = ref.parent as? KtCallExpression ?: return null
        if (call.calleeExpression != ref) return null
        return ref
    }

    /**
     * Does the declaration live in a Streamlord package? Kotlin sources and decompiled Kotlin name their package; anything else is
     * judged by where its file sits, so the check needs no plugin beyond Kotlin's.
     */
    fun isStreamlord(target: PsiElement): Boolean = inPackage(target, PACKAGE) ?: true

    /** Does the declaration live in the official Datastar SDK, whose `patchSignals` defines signals as core's does? */
    fun isOfficialSdk(target: PsiElement): Boolean = inPackage(target, OFFICIAL_SDK) ?: false

    private const val OFFICIAL_SDK = "dev.datastar.kotlin"

    private fun inPackage(
        target: PsiElement,
        name: String,
    ): Boolean? {
        val file = target.containingFile ?: return null
        if (file is KtFile) return file.packageFqName.asString().startsWith(name)
        val path = file.virtualFile?.path?.replace('\\', '/') ?: return null
        return name.replace('.', '/') in path
    }
}
