package io.github.markusaugust.streamlord.intellij.inspections

import com.intellij.modcommand.ActionContext
import com.intellij.modcommand.ModCommand
import com.intellij.modcommand.ModCommandAction
import com.intellij.modcommand.Presentation
import io.github.markusaugust.streamlord.analysis.Fix
import io.github.markusaugust.streamlord.analysis.rewriteAt
import io.github.markusaugust.streamlord.intellij.analysis.StreamlordAnalysis
import org.jetbrains.kotlin.psi.KtFile

/**
 * The rewrite of an HTML string to a call of `interpolate`, as a context action in a string that
 * can take it. The analysis says where and what, for the caret or the selection; on a warning
 * that offers the rewrite as a quick fix it says nothing, so the list holds it once. The platform
 * asks for the presentation and the command in the background, so nothing is analysed on the UI
 * thread, and the action holds no state.
 */
class InterpolateIntention : ModCommandAction {
    override fun getFamilyName(): String = "Build this string with interpolate(...)"

    override fun getPresentation(context: ActionContext): Presentation? = rewrite(context)?.let { Presentation.of(it.title) }

    override fun perform(context: ActionContext): ModCommand {
        val fix = rewrite(context) ?: return ModCommand.nop()
        return ModCommand.psiUpdate(context.file) { copy -> fix.applyTo(copy.fileDocument) }
    }

    private fun rewrite(context: ActionContext): Fix? {
        val file = context.file as? KtFile ?: return null
        val selection = context.selection
        val (start, end) = if (selection.isEmpty) context.offset to context.offset else selection.startOffset to selection.endOffset
        return rewriteAt(StreamlordAnalysis.getInstance(file.project).kotlinIssues(file), start, end)
    }
}
