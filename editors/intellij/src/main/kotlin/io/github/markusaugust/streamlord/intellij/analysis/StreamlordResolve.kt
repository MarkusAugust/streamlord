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
 * the call and it is not Streamlord's, the diagnostics are held back: a `patchElements` of the
 * user's own is theirs to judge.
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
    fun isStreamlord(target: PsiElement): Boolean {
        val file = target.containingFile ?: return true
        if (file is KtFile) return file.packageFqName.asString().startsWith(PACKAGE)
        val path = file.virtualFile?.path?.replace('\\', '/') ?: return true
        return PACKAGE.replace('.', '/') in path
    }
}
