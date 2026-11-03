package io.github.markusaugust.streamlord.intellij.navigation

import com.intellij.codeInsight.TargetElementUtil
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.impl.source.resolve.reference.impl.PsiMultiReference
import com.intellij.psi.impl.source.tree.injected.InjectedLanguageEditorUtil
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.refactoring.listeners.RefactoringElementListener
import com.intellij.refactoring.rename.PsiElementRenameHandler
import com.intellij.refactoring.rename.RenameHandler
import com.intellij.refactoring.rename.RenamePsiElementProcessor
import com.intellij.usageView.UsageInfo
import com.intellij.util.containers.MultiMap
import io.github.markusaugust.streamlord.intellij.index.SignalIndex

/**
 * Rename on a signal: on a read or a defining place, in HTML or in a Kotlin string. The platform's
 * own handler gives up on a reference with more than one target, which a signal defined on a page
 * and patched by a handler has, so this one takes the first: the rename renames them all.
 */
class SignalRenameHandler : RenameHandler {
    override fun isAvailableOnDataContext(dataContext: DataContext): Boolean = targetAt(dataContext) != null

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

    private fun targetAt(dataContext: DataContext): SignalTarget? {
        val editor = CommonDataKeys.EDITOR.getData(dataContext) ?: return null
        // In HTML injected into a Kotlin string the reference is the string's.
        for (e in listOf(editor, InjectedLanguageEditorUtil.getTopLevelEditor(editor)).distinct()) {
            // A Kotlin string may hold other plugins' references at the same place, which the platform joins into one.
            val found = TargetElementUtil.findReference(e, e.caretModel.offset)
            val reference =
                (found as? PsiMultiReference)?.references?.firstNotNullOfOrNull { it as? SignalPsiReference }
                    ?: found as? SignalPsiReference
                    ?: continue
            return reference.multiResolve(false).firstNotNullOfOrNull { it.element as? SignalTarget }
        }
        return null
    }
}

/**
 * Renames a signal at every place the project defines or reads it, each written as its place
 * wants: an attribute key kebab-case under its modifiers, an object key as the last part of the
 * path, a read into the signal with the rest of its path. A place that cannot hold the new name is
 * a conflict, shown before anything is written.
 */
class SignalRenameProcessor : RenamePsiElementProcessor() {
    override fun canProcessElement(element: PsiElement): Boolean = element is SignalTarget

    override fun isInplaceRenameSupported(): Boolean = false

    override fun findExistingNameConflicts(
        element: PsiElement,
        newName: String,
        conflicts: MultiMap<PsiElement, String>,
    ) {
        val old = (element as SignalTarget).name
        if (newName != old && SignalIndex.getInstance(element.project).declarationsOf(newName).isNotEmpty()) {
            conflicts.putValue(element, "The project already defines the signal '$newName'.")
        }
        for (ref in ReferencesSearch.search(element).findAll()) {
            if (ref !is SignalPsiReference || ref.renameText(old, newName) != null) continue
            val written =
                ref.element.containingFile.text
                    .substring(ref.absoluteRange.startOffset, ref.absoluteRange.endOffset)
            conflicts.putValue(
                ref.element,
                "'$written' in ${ref.element.containingFile.name} cannot be written so that it names '$newName'.",
            )
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
