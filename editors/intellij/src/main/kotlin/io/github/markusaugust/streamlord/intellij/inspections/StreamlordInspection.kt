package io.github.markusaugust.streamlord.intellij.inspections

import com.intellij.codeInsight.daemon.HighlightDisplayKey
import com.intellij.codeInsight.intention.HighPriorityAction
import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.profile.codeInspection.InspectionProjectProfileManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import io.github.markusaugust.streamlord.analysis.Fix
import io.github.markusaugust.streamlord.analysis.Issue
import io.github.markusaugust.streamlord.analysis.Severity
import io.github.markusaugust.streamlord.analysis.unknownSignalIssue
import io.github.markusaugust.streamlord.intellij.analysis.StreamlordAnalysis
import io.github.markusaugust.streamlord.intellij.index.SignalIndex
import org.jetbrains.kotlin.psi.KtFile

/**
 * One inspection per family of checks, all reading the same cached analysis of the file. The
 * Kotlin side judges Kotlin files, whether the string is handed to a Streamlord call or free-
 * standing; the HTML side judges HTML and template files.
 */
abstract class StreamlordInspection(
    private val codes: Set<String>,
) : LocalInspectionTool() {
    /** The issues of the file, or null when the file is neither Kotlin nor markup. */
    protected open fun issues(file: PsiFile): List<Issue>? {
        val analysis = StreamlordAnalysis.getInstance(file.project)
        return when {
            file is KtFile -> analysis.kotlinIssues(file).flatMap { it.issues }
            StreamlordAnalysis.isMarkupSide(file) -> analysis.htmlIssues(file)
            else -> null
        }
    }

    override fun checkFile(
        file: PsiFile,
        manager: InspectionManager,
        isOnTheFly: Boolean,
    ): Array<ProblemDescriptor> {
        val issues = issues(file) ?: return ProblemDescriptor.EMPTY_ARRAY
        val length = file.textLength
        val profiled = severitySetInProfile(file)
        return issues
            .filter { it.code in codes && it.start < length }
            .map { issue ->
                val range = TextRange(issue.start, minOf(maxOf(issue.end, issue.start + 1), length))
                manager.createProblemDescriptor(
                    file,
                    range,
                    issue.message + (issue.link?.let { " See $it" } ?: ""),
                    if (profiled) ProblemHighlightType.GENERIC_ERROR_OR_WARNING else highlightType(issue.severity),
                    isOnTheFly,
                    *issue.fixes.mapIndexed { i, fix -> if (i == 0) PreferredReplaceTextFix(fix) else ReplaceTextFix(fix) }.toTypedArray(),
                )
            }.toTypedArray()
    }

    /**
     * Has the user given this inspection a severity in the profile? One family mixes severities
     * (a syntax error beside a Pro hint), so by default each issue keeps the one the analysis
     * gave it; a level set in the profile applies to the whole family, as the profile promises.
     */
    private fun severitySetInProfile(file: PsiFile): Boolean {
        val key = HighlightDisplayKey.find(shortName) ?: return false
        val profile = InspectionProjectProfileManager.getInstance(file.project).currentProfile
        // The wrapper knows the level plugin.xml declares; the tool itself only knows the platform's default.
        val declared = profile.getInspectionTool(shortName, file)?.defaultLevel ?: return false
        return profile.getErrorLevel(key, file) != declared
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

/**
 * Datastar expression syntax, unknown actions, Pro actions, kebab-case signals, and what the
 * client's own rewrite of an expression does that JavaScript would not.
 */
class DatastarExpressionInspection :
    StreamlordInspection(
        setOf(
            "expression-syntax",
            "empty-expression",
            "unknown-action",
            "action-space",
            "pro-action",
            "signal-kebab",
            "literal-rewritten",
            "signal-not-read",
            "statement-split",
        ),
    )

/**
 * `data-*` attribute names, keys, modifiers and their arguments, values, an indicator with nothing
 * to show, signal names the client cannot hold, and the values it evaluates as filters or computeds.
 */
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
            "indicator-without-action",
            "signal-name",
            "computed-value",
            "filter-pattern",
        ),
    )

/**
 * A `$name` no file in the project defines: a misspelt signal, or one renamed on one side only.
 * The analysis finds what each expression reads; the project index says what is defined, from
 * every Kotlin and markup file. Not run while the IDE indexes, when the index is not complete.
 */
class UnknownSignalInspection : StreamlordInspection(setOf("unknown-signal")) {
    override fun issues(file: PsiFile): List<Issue>? {
        val analysis = StreamlordAnalysis.getInstance(file.project)
        val refs =
            when {
                file is KtFile -> analysis.kotlinSignalReferences(file)
                StreamlordAnalysis.isMarkupSide(file) -> analysis.htmlSignalReferences(file)
                else -> return null
            }
        if (refs.isEmpty()) return emptyList()
        val defined = SignalIndex.getInstance(file.project).definitionsSeenFrom(file)
        return refs.mapNotNull { unknownSignalIssue(it, defined) }
    }
}

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
            setOf("unknown-signal") +
            setOf(
                "expression-syntax",
                "empty-expression",
                "unknown-action",
                "action-space",
                "pro-action",
                "signal-kebab",
                "literal-rewritten",
                "signal-not-read",
                "statement-split",
            ) +
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
                "indicator-without-action",
                "signal-name",
                "computed-value",
                "filter-pattern",
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
