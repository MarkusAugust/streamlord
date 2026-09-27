package io.github.markusaugust.streamlord.intellij.completion

import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.editorActions.TypedHandlerDelegate
import com.intellij.lang.html.HTMLLanguage
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import io.github.markusaugust.streamlord.intellij.analysis.StreamlordAnalysis
import org.jetbrains.kotlin.psi.KtFile

/** `$`, `@`, `#` and `.` open the completion popup where the contributor has something to say. */
class DatastarTypedHandler : TypedHandlerDelegate() {
    override fun checkAutoPopup(
        charTyped: Char,
        project: Project,
        editor: Editor,
        file: PsiFile,
    ): Result {
        if (charTyped !in TRIGGERS) return Result.CONTINUE
        if (file !is KtFile && !file.language.isKindOf(HTMLLanguage.INSTANCE) &&
            !StreamlordAnalysis.isTemplateText(file)
        ) {
            return Result.CONTINUE
        }
        AutoPopupController.getInstance(project).scheduleAutoPopup(editor)
        return Result.CONTINUE
    }

    private companion object {
        val TRIGGERS = setOf('$', '@', '#', '_', ':')
    }
}
