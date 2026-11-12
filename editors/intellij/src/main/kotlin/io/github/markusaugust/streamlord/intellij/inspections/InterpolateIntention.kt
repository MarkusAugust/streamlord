package io.github.markusaugust.streamlord.intellij.inspections

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import io.github.markusaugust.streamlord.analysis.Fix
import io.github.markusaugust.streamlord.analysis.rewriteAt
import io.github.markusaugust.streamlord.intellij.analysis.StreamlordAnalysis
import org.jetbrains.kotlin.psi.KtFile

/**
 * The rewrite of an HTML string to a call of `interpolate`, as a context action anywhere in a
 * string whose values `interpolate` keeps. The analysis says where and what; on a warning that
 * offers the rewrite as a quick fix it says nothing, so the list holds it once.
 */
class InterpolateIntention : IntentionAction {
    private var title = FAMILY

    override fun getText(): String = title

    override fun getFamilyName(): String = FAMILY

    override fun startInWriteAction(): Boolean = true

    override fun isAvailable(
        project: Project,
        editor: Editor?,
        file: PsiFile?,
    ): Boolean {
        title = rewrite(editor, file)?.title ?: return false
        return true
    }

    override fun invoke(
        project: Project,
        editor: Editor?,
        file: PsiFile?,
    ) {
        val fix = rewrite(editor, file) ?: return
        val document = editor!!.document
        for (e in fix.replacements) document.replaceString(e.start, e.end, e.text)
        PsiDocumentManager.getInstance(project).commitDocument(document)
    }

    private fun rewrite(
        editor: Editor?,
        file: PsiFile?,
    ): Fix? {
        if (editor == null || file !is KtFile) return null
        return rewriteAt(StreamlordAnalysis.getInstance(file.project).kotlinIssues(file), editor.caretModel.offset)
    }

    private companion object {
        const val FAMILY = "Build it with interpolate(...)"
    }
}
