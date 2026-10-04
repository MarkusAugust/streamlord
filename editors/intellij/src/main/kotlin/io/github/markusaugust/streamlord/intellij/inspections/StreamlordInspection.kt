package io.github.markusaugust.streamlord.intellij.inspections

import com.intellij.codeInsight.intention.HighPriorityAction
import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import io.github.markusaugust.streamlord.analysis.Fix
import io.github.markusaugust.streamlord.analysis.Issue
import io.github.markusaugust.streamlord.analysis.Severity
import io.github.markusaugust.streamlord.intellij.analysis.StreamlordAnalysis
import org.jetbrains.kotlin.psi.KtFile

/**
 * One inspection per family of checks, all reading the same cached analysis of the file. The
 * Kotlin side judges Kotlin files, whether the string is handed to a Streamlord call or free-
 * standing; the HTML side judges HTML and template files.
 */
abstract class StreamlordInspection(
    private val codes: Set<String>,
) : LocalInspectionTool() {
    override fun checkFile(
        file: PsiFile,
        manager: InspectionManager,
        isOnTheFly: Boolean,
    ): Array<ProblemDescriptor> {
        val analysis = StreamlordAnalysis.getInstance(file.project)
        val issues =
            when {
                file is KtFile -> analysis.kotlinIssues(file).flatMap { it.issues }
                StreamlordAnalysis.isMarkupSide(file) -> analysis.htmlIssues(file)
                else -> return ProblemDescriptor.EMPTY_ARRAY
            }
        val length = file.textLength
        return issues
            .filter { it.code in codes && it.start < length }
            .map { issue ->
                val range = TextRange(issue.start, minOf(maxOf(issue.end, issue.start + 1), length))
                manager.createProblemDescriptor(
                    file,
                    range,
                    issue.message + (issue.link?.let { " See $it" } ?: ""),
                    highlightType(issue.severity),
                    isOnTheFly,
                    *issue.fixes.mapIndexed { i, fix -> if (i == 0) PreferredReplaceTextFix(fix) else ReplaceTextFix(fix) }.toTypedArray(),
                )
            }.toTypedArray()
    }

    private fun highlightType(severity: Severity): ProblemHighlightType =
        when (severity) {
            Severity.ERROR -> ProblemHighlightType.GENERIC_ERROR

            Severity.WARNING -> ProblemHighlightType.WARNING

            // Hints are handled for you but never hidden: a weak warning shows the message on hover.
            Severity.INFO, Severity.HINT -> ProblemHighlightType.WEAK_WARNING
        }
}

/** A quick fix that replaces a range of the file with text, as the analysis spelled it out. */
open class ReplaceTextFix(
    private val fix: Fix,
) : LocalQuickFix {
    override fun getFamilyName(): String = fix.title

    override fun applyFix(
        project: Project,
        descriptor: ProblemDescriptor,
    ) {
        val file = descriptor.psiElement?.containingFile ?: return
        val document = PsiDocumentManager.getInstance(project).getDocument(file) ?: return
        if (fix.end > document.textLength) return
        document.replaceString(fix.start, fix.end, fix.text)
        PsiDocumentManager.getInstance(project).commitDocument(document)
    }
}

/** The fix the analysis lists first is the one it means: it goes to the top of the Alt+Enter list, above the IDE's own. */
class PreferredReplaceTextFix(
    fix: Fix,
) : ReplaceTextFix(fix),
    HighPriorityAction

/** `$count` in a Kotlin string is a Kotlin template, not a signal: the interpolation trap. */
class KotlinInterpolationInspection : StreamlordInspection(setOf("kotlin-interpolation"))

/** Datastar expression syntax, unknown actions, Pro actions, kebab-case signals. */
class DatastarExpressionInspection :
    StreamlordInspection(setOf("expression-syntax", "empty-expression", "unknown-action", "action-space", "pro-action", "signal-kebab"))

/** `data-*` attribute names, keys, modifiers and their arguments, values. */
class DatastarAttributeInspection :
    StreamlordInspection(
        setOf(
            "unknown-attribute",
            "unknown-modifier",
            "modifier-args",
            "missing-key",
            "unexpected-key",
            "wrong-element",
            "signal-name-expected",
            "unexpected-value",
            "missing-value",
            "key-and-value",
            "missing-key-or-value",
            "pro-attribute",
            "prefix-mismatch",
        ),
    )

/** Capitals in keys, which the browser lowercases, and what the DSL writes on the wire. */
class DatastarKeyCaseInspection : StreamlordInspection(setOf("key-case", "key-case-wire"))

/** The markup of a patch: complete elements, ids, selectors and modes, scripts. */
class StreamlordMarkupInspection :
    StreamlordInspection(
        setOf(
            "missing-id",
            "mode-needs-selector",
            "unclosed",
            "stray-close",
            "top-level-text",
            "blank-selector",
            "selector-newline",
            "script-close",
        ),
    )

/** Every code an inspection claims, so a test can prove the analysis leaves none unclaimed. */
object InspectionCodes {
    val all: Set<String> =
        setOf("kotlin-interpolation") +
            setOf("expression-syntax", "empty-expression", "unknown-action", "action-space", "pro-action", "signal-kebab") +
            setOf(
                "unknown-attribute",
                "unknown-modifier",
                "modifier-args",
                "missing-key",
                "unexpected-key",
                "wrong-element",
                "signal-name-expected",
                "unexpected-value",
                "missing-value",
                "key-and-value",
                "missing-key-or-value",
                "pro-attribute",
                "prefix-mismatch",
            ) +
            setOf("key-case", "key-case-wire") +
            setOf(
                "missing-id",
                "mode-needs-selector",
                "unclosed",
                "stray-close",
                "top-level-text",
                "blank-selector",
                "selector-newline",
                "script-close",
            )
}
