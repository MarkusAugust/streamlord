package io.github.markusaugust.streamlord.intellij.navigation

import com.intellij.codeInsight.TargetElementUtil
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiReference
import com.intellij.psi.impl.source.resolve.reference.impl.PsiMultiReference
import com.intellij.psi.impl.source.tree.injected.InjectedLanguageEditorUtil
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.search.SearchScope
import com.intellij.refactoring.listeners.RefactoringElementListener
import com.intellij.refactoring.rename.PsiElementRenameHandler
import com.intellij.refactoring.rename.RenameHandler
import com.intellij.refactoring.rename.RenamePsiElementProcessor
import com.intellij.usageView.UsageInfo
import com.intellij.util.containers.MultiMap
import io.github.markusaugust.streamlord.analysis.SignalDeclaration
import io.github.markusaugust.streamlord.intellij.index.SignalIndex
import org.jetbrains.kotlin.psi.KtFile

/**
 * Rename on a signal: on a read or a defining place, in HTML or in a Kotlin string. The platform's
 * own handler gives up on a reference with more than one target, which a signal defined on a page
 * and patched by a handler has, so this one takes the first: the rename renames them all.
 */
class SignalRenameHandler : RenameHandler {
    // Asked on every update of the action, so it resolves nothing: a signal reference at the caret is enough.
    override fun isAvailableOnDataContext(dataContext: DataContext): Boolean = referenceAt(dataContext) != null

    override fun invoke(
        project: Project,
        editor: Editor?,
        file: PsiFile?,
        dataContext: DataContext,
    ) {
        val target = targetAt(dataContext) ?: return
        val editorAt = editor?.let(InjectedLanguageEditorUtil::getTopLevelEditor)
        PsiElementRenameHandler.rename(target, project, target, editorAt, PsiElementRenameHandler.DEFAULT_NAME.getData(dataContext))
    }

    override fun invoke(
        project: Project,
        elements: Array<out PsiElement>,
        dataContext: DataContext,
    ) {
        val target = elements.singleOrNull() as? SignalTarget ?: targetAt(dataContext) ?: return
        val editor = CommonDataKeys.EDITOR.getData(dataContext)
        PsiElementRenameHandler.rename(target, project, target, editor, PsiElementRenameHandler.DEFAULT_NAME.getData(dataContext))
    }

    private fun targetAt(dataContext: DataContext): SignalTarget? =
        referenceAt(dataContext)?.let { SignalTarget.of(it.element.containingFile, it.signal).firstOrNull() }

    private fun referenceAt(dataContext: DataContext): SignalPsiReference? {
        val editor = CommonDataKeys.EDITOR.getData(dataContext) ?: return null
        // In HTML injected into a Kotlin string the reference is the string's.
        for (e in listOf(editor, InjectedLanguageEditorUtil.getTopLevelEditor(editor)).distinct()) {
            val file = e.project?.let { PsiDocumentManager.getInstance(it).getPsiFile(e.document) }
            file?.let { SignalSites.textReferenceAt(it, e.caretModel.offset, start = false) }?.let { return it }
            // A Kotlin string may hold other plugins' references at the same place, which the platform joins into one.
            val found = TargetElementUtil.findReference(e, e.caretModel.offset)
            val reference =
                (found as? PsiMultiReference)?.references?.firstNotNullOfOrNull { it as? SignalPsiReference }
                    ?: found as? SignalPsiReference
                    ?: continue
            return reference
        }
        return null
    }
}

/**
 * Renames a signal at every place the project defines or reads it, each written as its place
 * wants: an attribute key kebab-case under its modifiers, an object key as the last part of the
 * path, a read in the part of its path that names the signal. A place that cannot hold the new name is
 * a conflict, shown before anything is written.
 */
class SignalRenameProcessor : RenamePsiElementProcessor() {
    override fun canProcessElement(element: PsiElement): Boolean = element is SignalTarget

    override fun isInplaceRenameSupported(): Boolean = false

    /** The references to the signal, and every place that defines a signal under it, which moves with it. */
    override fun findReferences(
        element: PsiElement,
        searchScope: SearchScope,
        searchInCommentsAndStrings: Boolean,
    ): Collection<PsiReference> {
        val out = ArrayList<PsiReference>()
        // The rename asks in the target's own scope, which is empty so that no word search runs: a signal's is the project.
        val scope = searchScope as? LocalSearchScope ?: GlobalSearchScope.projectScope(element.project)
        SignalReferencesSearcher.references(element.project, (element as SignalTarget).name, scope, under = true) {
            out += it
            true
        }
        return out
    }

    override fun findExistingNameConflicts(
        element: PsiElement,
        newName: String,
        conflicts: MultiMap<PsiElement, String>,
    ) {
        val old = (element as SignalTarget).name
        // A definition in somebody else's call, which go to declaration leaves out, is no conflict either.
        if (newName != old && SignalTarget.of(element.containingFile, newName).isNotEmpty()) {
            conflicts.putValue(element, "The project already defines the signal '$newName'.")
        }
        val project = element.project
        SignalReferencesSearcher.references(project, old, GlobalSearchScope.projectScope(project), under = true) { ref ->
            if (ref.renameText(old, newName) == null) {
                val written =
                    ref.element.containingFile.text
                        .substring(ref.absoluteRange.startOffset, ref.absoluteRange.endOffset)
                conflicts.putValue(
                    ref.element,
                    "'$written' in ${ref.element.containingFile.name} cannot be written so that it names '$newName'.",
                )
            }
            true
        }
        unreached(element, old, conflicts)
    }

    /**
     * The places the index knows that hold no reference a rename can rewrite, each a conflict, so
     * that a rename never stops halfway without saying so: a template IntelliJ reads as plain text,
     * and a property of a signals class, which Kotlin's own rename renames. A Kotlin place without
     * one is a call to something other than Streamlord, which the rename leaves alone.
     */
    private fun unreached(
        target: SignalTarget,
        old: String,
        conflicts: MultiMap<PsiElement, String>,
    ) {
        val project = target.project
        val index = SignalIndex.getInstance(project)
        val manager = PsiManager.getInstance(project)
        for (place in index.declarationsOf(old, inside = true) + index.readsOf(old)) {
            val file = SignalSites.psiOf(manager, place.file) ?: continue
            val sites = SignalSites.of(file).filter { it.start >= place.start && it.end <= place.end }
            if (sites.any { SignalReferencesSearcher.referenceAt(file, it.start) != null }) continue
            val written = file.text.substring(place.start, place.end)
            if (file !is KtFile) {
                conflicts.putValue(file, "'$written' in ${file.name} is not renamed: the plugin finds no reference there to rewrite.")
            } else if (index.declarationsIn(file).any { it.start == place.start && it.kind == SignalDeclaration.Kind.PROPERTY }) {
                conflicts.putValue(file, "'$written' in ${file.name} is a property of a signals class: rename it with Kotlin's rename.")
            }
        }
    }

    override fun renameElement(
        element: PsiElement,
        newName: String,
        usages: Array<out UsageInfo>,
        listener: RefactoringElementListener?,
    ) {
        val old = (element as SignalTarget).name
        val documents = PsiDocumentManager.getInstance(element.project)
        val edits =
            usages.mapNotNull { it.reference as? SignalPsiReference }.mapNotNull { ref ->
                ref.renameText(old, newName)?.let {
                    ref to
                        it
                }
            }
        // Each file from its end, so that an edit leaves the offsets before it as they were.
        for ((file, inFile) in edits.groupBy { it.first.element.containingFile }) {
            val document = documents.getDocument(file) ?: continue
            for ((ref, text) in inFile.sortedByDescending { it.first.absoluteRange.startOffset }) {
                document.replaceString(ref.absoluteRange.startOffset, ref.absoluteRange.endOffset, text)
            }
            documents.commitDocument(document)
        }
        listener?.elementRenamed(element)
    }
}
