package io.github.markusaugust.streamlord.intellij.completion

import com.intellij.codeInsight.AutoPopupController
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadConstraint
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.application.constrainedReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.impl.source.tree.injected.InjectedLanguageEditorUtil
import com.intellij.util.concurrency.annotations.RequiresEdt
import io.github.markusaugust.streamlord.analysis.signatureHelp
import io.github.markusaugust.streamlord.intellij.analysis.StreamlordAnalysis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The help a Datastar expression gets as it is typed, worked out off the UI thread. */
@Service(Service.Level.PROJECT)
class ExpressionHelp(
    private val project: Project,
    private val scope: CoroutineScope,
) {
    /** The question under way; a character typed after it asks again, for the caret where it then stands. */
    private var asking: Job? = null

    /**
     * Open the parameter info at the caret of [editor] when it stands in the arguments of a Datastar
     * action. Asked as a character is typed, and answered once the character is in the file: which
     * modules the file can use is a question for the indexes.
     */
    @RequiresEdt
    fun offerParameterInfo(editor: Editor) {
        asking?.cancel()
        val wanted = parameterInfoWanted(editor)
        asking =
            scope.launch(ModalityState.current().asContextElement()) {
                if (!wanted.await()) return@launch
                withContext(Dispatchers.EDT) {
                    if (!editor.isDisposed) AutoPopupController.getInstance(project).autoPopupParameterInfo(editor, null)
                }
            }
    }

    /**
     * Whether the caret of [editor] stands in the arguments of a Datastar action, read once the
     * document is committed. The file is the editor's as it is then: one injected into a string may
     * have been made anew by what was typed.
     */
    internal fun parameterInfoWanted(editor: Editor): Deferred<Boolean> {
        val host = InjectedLanguageEditorUtil.getTopLevelEditor(editor)
        return scope.async {
            constrainedReadAction(ReadConstraint.withDocumentsCommitted(project)) {
                if (host.isDisposed) return@constrainedReadAction false
                val file = PsiDocumentManager.getInstance(project).getPsiFile(host.document) ?: return@constrainedReadAction false
                val asked = DatastarCaret.at(file, host.caretModel.offset) ?: return@constrainedReadAction false
                StreamlordAnalysis.getInstance(project).analyzer.signatureHelp(asked.source, asked.offset) != null
            }
        }
    }

    companion object {
        fun getInstance(project: Project): ExpressionHelp = project.service()
    }
}
