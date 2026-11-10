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
import com.intellij.psi.PsiFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The help a Datastar expression gets as it is typed, worked out off the UI thread. */
@Service(Service.Level.PROJECT)
class ExpressionHelp(
    private val project: Project,
    private val scope: CoroutineScope,
) {
    /**
     * Open the parameter info at the caret of [editor] when it stands in a Datastar expression that takes
     * actions. Asked as a character is typed, and answered once the character is in [file]: which
     * modules the file can use is a question for the indexes.
     */
    fun offerParameterInfo(
        editor: Editor,
        file: PsiFile,
    ) {
        scope.launch(ModalityState.current().asContextElement()) {
            val wanted =
                constrainedReadAction(ReadConstraint.withDocumentsCommitted(project)) {
                    !editor.isDisposed && file.isValid && DatastarCaret.expressionAt(file, editor.caretModel.offset)?.allowActions == true
                }
            if (!wanted) return@launch
            withContext(Dispatchers.EDT) {
                if (!editor.isDisposed) AutoPopupController.getInstance(project).autoPopupParameterInfo(editor, null)
            }
        }
    }

    companion object {
        fun getInstance(project: Project): ExpressionHelp = project.service()
    }
}
