package io.github.markusaugust.streamlord.intellij.navigation

import com.intellij.openapi.application.QueryExecutorBase
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiReference
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.parentsOfType
import com.intellij.util.Processor
import io.github.markusaugust.streamlord.intellij.index.SignalIndex

/**
 * The references to a signal, from the places [SignalIndex] keeps: no file is read again. Each place
 * is checked against the references its file holds now, which also leaves out a call that resolves
 * to something other than Streamlord.
 */
class SignalReferencesSearcher : QueryExecutorBase<PsiReference, ReferencesSearch.SearchParameters>(true) {
    override fun processQuery(
        parameters: ReferencesSearch.SearchParameters,
        consumer: Processor<in PsiReference>,
    ) {
        val target = parameters.elementToSearch as? SignalTarget ?: return
        val project = parameters.project
        val index = SignalIndex.getInstance(project)
        val scope = parameters.effectiveSearchScope
        val manager = PsiManager.getInstance(project)
        val seen = HashSet<PsiReference>()
        // A defining place is found at the name as written, a read at the name after its dollar.
        val places =
            index.declarationsOf(target.name).map { it to it.start } + index.readsOf(target.name).map { it to it.end - it.name.length }
        for ((place, at) in places) {
            if (!scope.contains(place.file)) continue
            val file = manager.findFile(place.file) ?: continue
            val leaf = file.findElementAt(at) ?: continue
            for (host in leaf.parentsOfType<PsiElement>(withSelf = true)) {
                val base = host.textRange.startOffset
                val found =
                    host.references.filterIsInstance<SignalPsiReference>().filter {
                        it.rangeInElement.startOffset + base == at && it.isReferenceTo(target)
                    }
                if (found.isEmpty()) continue
                for (ref in found) if (seen.add(ref) && !consumer.process(ref)) return
                break
            }
        }
    }
}
