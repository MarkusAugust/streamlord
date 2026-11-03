package io.github.markusaugust.streamlord.intellij.navigation

import com.intellij.openapi.application.QueryExecutorBase
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiPlainText
import com.intellij.psi.PsiReference
import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.search.SearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.parentsOfType
import com.intellij.util.Processor
import io.github.markusaugust.streamlord.intellij.index.SignalIndex

/**
 * The references to a signal, from the files [SignalIndex] says name it: no word search, and no
 * file the index leaves out. Each file's references are its [SignalSites], which leave out a call
 * that resolves to something other than Streamlord.
 */
class SignalReferencesSearcher : QueryExecutorBase<PsiReference, ReferencesSearch.SearchParameters>(true) {
    override fun processQuery(
        parameters: ReferencesSearch.SearchParameters,
        consumer: Processor<in PsiReference>,
    ) {
        val target = parameters.elementToSearch as? SignalTarget ?: return
        // The target's own scope is empty, so that the platform's word search finds nothing: the scope asked for is this one.
        references(parameters.project, target.name, parameters.scopeDeterminedByUser, under = false) { consumer.process(it) }
    }

    companion object {
        /**
         * Every reference in [scope] to the signal [name], and with [under] every place that defines a
         * signal under it, which a rename moves with it. Stops when [consumer] says so.
         */
        fun references(
            project: Project,
            name: String,
            scope: SearchScope,
            under: Boolean,
            consumer: (SignalPsiReference) -> Boolean,
        ) {
            for (file in filesNaming(project, name, scope)) {
                for (site in SignalSites.of(file)) {
                    val names = site.signal == name || (under && site.declaration != null && site.signal.startsWith("$name."))
                    if (!names) continue
                    val ref = referenceAt(file, site.start) ?: continue
                    if (!consumer(ref)) return
                }
            }
        }

        /** The files in [scope] that may name [name]: those of a local scope, or those the index finds it in. */
        fun filesNaming(
            project: Project,
            name: String,
            scope: SearchScope,
        ): List<PsiFile> {
            if (scope is LocalSearchScope) return scope.scope.mapNotNull { it.containingFile }.distinct()
            val index = SignalIndex.getInstance(project)
            val manager = PsiManager.getInstance(project)
            return (index.declarationsOf(name, inside = true) + index.readsOf(name))
                .map { it.file }
                .distinct()
                .filter { scope.contains(it) }
                .mapNotNull { SignalSites.psiOf(manager, it) }
        }

        /** The signal reference that starts at [at] in [file], on the innermost element that holds one. */
        fun referenceAt(
            file: PsiFile,
            at: Int,
        ): SignalPsiReference? {
            val leaf = file.findElementAt(at) ?: return null
            // Plain text holds no contributed references, so a template read as text gets them made from its sites.
            if (leaf is PsiPlainText) return SignalSites.textReferenceAt(file, at, start = true)
            for (host in leaf.parentsOfType<PsiElement>(withSelf = true)) {
                val base = host.textRange.startOffset
                host.references
                    .firstNotNullOfOrNull { ref ->
                        (ref as? SignalPsiReference)?.takeIf { it.rangeInElement.startOffset + base == at }
                    }?.let { return it }
            }
            return null
        }
    }
}
