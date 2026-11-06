package io.github.markusaugust.streamlord.intellij.navigation

import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiPlainText
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
                // Which modules have the DSL decides which definitions count.
                ProjectRootManager.getInstance(file.project),
                DumbService.getInstance(file.project).modificationTracker,
            )
        }

    private fun compute(file: PsiFile): List<SignalSite> {
        val analysis = StreamlordAnalysis.getInstance(file.project)
        val reads =
            when {
                file is KtFile -> {
                    analysis.streamlordSignalReferences(file)
                }

                StreamlordAnalysis.isHtmlSide(file) && !InjectedLanguageManager.getInstance(file.project).isInjectedFragment(file) -> {
                    analysis.htmlSignalReferences(file)
                }

                // A template IntelliJ gives no HTML tree is read as text, as the inspections read it.
                StreamlordAnalysis.isTemplateText(file) -> {
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
        // A name the project defines only where go to declaration finds no place, in somebody else's `set` say, is no
        // link: a reference that resolves to nothing would say "Cannot find declaration".
        val targeted = HashMap<String, Boolean>()

        fun linked(path: String) = path in defined && targeted.getOrPut(path) { SignalTarget.of(file, path).isNotEmpty() }
        val out = ArrayList<SignalSite>()
        for (read in reads) {
            var at = read.end - read.name.length
            // `$count = 1` assigns the signal, so it is the place that defines it and no read besides.
            if (declarations.any { it.start <= at && read.end <= it.end }) continue
            // `$user.name.length` reads `user` in its first part and `user.name` in its second, where the project defines them.
            var path = ""
            for (part in read.name.split('.')) {
                path = if (path.isEmpty()) part else "$path.$part"
                if (linked(path)) out += SignalSite(at, at + part.length, path, null, host(at))
                at += part.length + 1
            }
        }
        for (d in declarations) out += SignalSite(d.start, d.end, d.name, d, host(d.start))
        return out
    }

    /**
     * The reference of a template read as text at [at]: starting there with [start], or else
     * holding it, as a caret does. Plain text holds no contributed references, so it is made from
     * the file's sites on its one text element.
     */
    fun textReferenceAt(
        file: PsiFile,
        at: Int,
        start: Boolean,
    ): SignalPsiReference? {
        if (!StreamlordAnalysis.isTemplateText(file)) return null
        // A caret just after a name that ends the file stands past the last element.
        val text = (file.findElementAt(at) ?: file.findElementAt(at - 1)) as? PsiPlainText ?: return null
        val site = of(file).firstOrNull { if (start) it.start == at else at in it.start..it.end } ?: return null
        return SignalPsiReference(text, site.start, site.end, site.signal, site.declaration)
    }

    /**
     * The tree of [vf] that holds its signal references: the HTML view of a template that has one,
     * which the base language's tree of the template does not hold, or else the file's own.
     */
    fun psiOf(
        manager: PsiManager,
        vf: VirtualFile,
    ): PsiFile? {
        val provider = manager.findViewProvider(vf) ?: return null
        val base = provider.getPsi(provider.baseLanguage)
        return provider.allFiles.firstOrNull { it !== base && StreamlordAnalysis.isHtmlSide(it) } ?: base
    }

    /** A `data*` helper names the attribute it writes, so it defines its signal whatever it resolves to, a helper of the project's own among them. */
    private val ATTRIBUTE_HELPERS: Set<String> by lazy { Catalog.default.attributes.flatMapTo(HashSet()) { it.kotlin } }

    /** The DSL's short helpers, whose names other APIs use too: Streamlord's only when they resolve there. */
    private val SHORT_HELPERS: Set<String> by lazy { Catalog.default.signalNameHelpers.keys }

    /** Core's calls, `patchSignals` and its kin: Streamlord's or the official SDK's, not those of a class of the project's own. */
    private val CORE_CALLS: Set<String> by lazy { Catalog.default.signalCalls.keys - ATTRIBUTE_HELPERS }

    /**
     * Is the definition at [at] in [file] Streamlord's? It is unless the nearest call around it that
     * defines signals resolves to something that does not, `headers { set("count", 1) }` say. A key
     * in an HTML string or a property of a signals class has no such call, and a call that does not
     * resolve counts.
     */
    fun byStreamlord(
        file: KtFile,
        at: Int,
    ): Boolean {
        val leaf = file.findElementAt(at) ?: return true
        for (call in leaf.parentsOfType<KtCallExpression>()) {
            val callee = call.calleeExpression as? KtNameReferenceExpression ?: continue
            val name = callee.getReferencedName()
            if (name in ATTRIBUTE_HELPERS) return true
            if (name !in SHORT_HELPERS && name !in CORE_CALLS) continue
            val target = callee.mainReference.resolve() ?: return true
            return StreamlordResolve.isStreamlord(target) || (name in CORE_CALLS && StreamlordResolve.isOfficialSdk(target))
        }
        return true
    }
}
