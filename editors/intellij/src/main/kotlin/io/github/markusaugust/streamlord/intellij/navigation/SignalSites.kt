package io.github.markusaugust.streamlord.intellij.navigation

import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.psi.PsiFile
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.util.parentOfType
import com.intellij.psi.util.parentsOfType
import io.github.markusaugust.streamlord.analysis.Catalog
import io.github.markusaugust.streamlord.analysis.SignalDeclaration
import io.github.markusaugust.streamlord.intellij.analysis.StreamlordAnalysis
import io.github.markusaugust.streamlord.intellij.analysis.StreamlordResolve
import io.github.markusaugust.streamlord.intellij.index.SignalIndex
import io.github.markusaugust.streamlord.intellij.settings.StreamlordSettings
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtStringTemplateExpression

/**
 * A place in a file that names a signal: one part of a read's path, `name` in `$user.name` for
 * `user.name`, or the name a defining place writes, with [declaration] saying how a new one is
 * written there. [host] is where the innermost Kotlin string around it starts, which holds its
 * reference, or -1. The rules are those of `signalSites.ts` in the VS Code extension.
 */
class SignalSite(
    val start: Int,
    val end: Int,
    val signal: String,
    val declaration: SignalDeclaration?,
    val host: Int,
)

object SignalSites {
    /**
     * The sites of [file], worked out once for each change in the project, since which parts of a
     * read the project defines can change with any file. A read in a call that resolves to
     * something other than Streamlord is left out, and so is a definition in one.
     */
    fun of(file: PsiFile): List<SignalSite> =
        CachedValuesManager.getCachedValue(file) {
            CachedValueProvider.Result.create(
                compute(file),
                PsiModificationTracker.getInstance(file.project),
                StreamlordSettings.getInstance(file.project).tracker,
            )
        }

    private fun compute(file: PsiFile): List<SignalSite> {
        val analysis = StreamlordAnalysis.getInstance(file.project)
        val reads =
            when {
                file is KtFile -> {
                    analysis.kotlinSignalReferences(file)
                }

                StreamlordAnalysis.isHtmlSide(file) && !InjectedLanguageManager.getInstance(file.project).isInjectedFragment(file) -> {
                    analysis.htmlSignalReferences(file)
                }

                else -> {
                    return emptyList()
                }
            }
        val index = SignalIndex.getInstance(file.project)
        val declarations = index.declarationsIn(file).filter { file !is KtFile || byStreamlord(file, it.start) }
        val defined = index.definitionsSeenFrom(file)

        fun host(at: Int): Int =
            if (file is KtFile) {
                file
                    .findElementAt(at)
                    ?.parentOfType<KtStringTemplateExpression>()
                    ?.textRange
                    ?.startOffset ?: -1
            } else {
                -1
            }
        val out = ArrayList<SignalSite>()
        for (read in reads) {
            var at = read.end - read.name.length
            // `$count = 1` assigns the signal, so it is the place that defines it and no read besides.
            if (declarations.any { it.start <= at && read.end <= it.end }) continue
            // `$user.name.length` reads `user` in its first part and `user.name` in its second, where the project defines them.
            var path = ""
            for (part in read.name.split('.')) {
                path = if (path.isEmpty()) part else "$path.$part"
                if (path in defined) out += SignalSite(at, at + part.length, path, null, host(at))
                at += part.length + 1
            }
        }
        for (d in declarations) out += SignalSite(d.start, d.end, d.name, d, host(d.start))
        return out
    }

    /** The calls that define a signal by a name in a string, which are someone else's when they resolve elsewhere. */
    private val DEFINING_CALLS: Set<String> by lazy {
        Catalog.default.signalCalls.keys +
            setOf("set", "setExpr", "toggle", "dataBind", "dataIndicator", "dataRef", "dataComputed", "dataMatchMedia")
    }

    /**
     * Is the definition at [at] in [file] Streamlord's? It is unless the nearest call around it that
     * defines signals resolves to something else, `redis.set("count", 1)` say. A key in an HTML
     * string or a property of a signals class has no such call.
     */
    fun byStreamlord(
        file: KtFile,
        at: Int,
    ): Boolean {
        val leaf = file.findElementAt(at) ?: return true
        for (call in leaf.parentsOfType<KtCallExpression>()) {
            val callee = call.calleeExpression as? KtNameReferenceExpression ?: continue
            if (callee.getReferencedName() !in DEFINING_CALLS) continue
            val target = callee.mainReference.resolve() ?: return true
            return StreamlordResolve.isStreamlord(target)
        }
        return true
    }
}
