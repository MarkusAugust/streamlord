package io.github.markusaugust.streamlord.intellij.inspections

import com.intellij.codeInsight.daemon.HighlightDisplayKey
import com.intellij.codeInsight.intention.HighPriorityAction
import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.Document
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
        val profiled = profileLevel(file)
        return issues
            .filter { it.code in codes && it.start < length }
            .map { issue ->
                val range = TextRange(issue.start, minOf(maxOf(issue.end, issue.start + 1), length))
                manager.createProblemDescriptor(
                    file,
                    range,
                    issue.message + (issue.link?.let { " See $it" } ?: ""),
                    if (profiled?.takes(issue.severity) ==
                        true
                    ) {
                        ProblemHighlightType.GENERIC_ERROR_OR_WARNING
                    } else {
                        highlightType(issue.severity)
                    },
                    isOnTheFly,
                    *quickFixes(issue.fixes),
                )
            }.toTypedArray()
    }

    /**
     * The level the user has given this inspection in the profile, beside the one plugin.xml declares, or null when it is
     * the declared one. One family mixes severities (a syntax error beside a Pro hint), so by default each issue keeps the one
     * the analysis gave it.
     */
    private fun profileLevel(file: PsiFile): ProfileLevel? {
        val key = HighlightDisplayKey.find(shortName) ?: return null
        val profile = InspectionProjectProfileManager.getInstance(file.project).currentProfile
        // The wrapper knows the level plugin.xml declares; the tool itself only knows the platform's default.
        val declared = profile.getInspectionTool(shortName, file)?.defaultLevel ?: return null
        val level = profile.getErrorLevel(key, file)
        return if (level == declared) null else ProfileLevel(declared.severity, level.severity)
    }

    /**
     * A level set in the profile applies to the issues at the declared level or above, as the profile promises, and lowers
     * any issue it is below: an inspection declared an error and set to a warning makes its errors warnings, but never its
     * hints warnings too.
     */
    private class ProfileLevel(
        val declared: HighlightSeverity,
        val chosen: HighlightSeverity,
    ) {
        fun takes(severity: Severity): Boolean {
            val own = highlightSeverity(severity)
            return own >= declared || chosen < own
        }
    }

    private fun highlightType(severity: Severity): ProblemHighlightType =
        when (severity) {
            Severity.ERROR -> ProblemHighlightType.GENERIC_ERROR

            Severity.WARNING -> ProblemHighlightType.WARNING

            // Hints are handled for you but never hidden: a weak warning shows the message on hover.
            Severity.INFO, Severity.HINT -> ProblemHighlightType.WEAK_WARNING
        }
}

private fun highlightSeverity(severity: Severity): HighlightSeverity =
    when (severity) {
        Severity.ERROR -> HighlightSeverity.ERROR
        Severity.WARNING -> HighlightSeverity.WARNING
        Severity.INFO, Severity.HINT -> HighlightSeverity.WEAK_WARNING
    }

/**
 * The analysis's fixes, the first at the top of the Alt+Enter list unless it is marked not preferred: where the choice is the
 * author's, such as escaping a value or marking it as markup, no fix goes above the others.
 */
private fun quickFixes(fixes: List<Fix>): Array<LocalQuickFix> =
    fixes.mapIndexed { i, fix -> if (i == 0 && fix.preferred) PreferredReplaceTextFix(fix) else ReplaceTextFix(fix) }.toTypedArray()

/**
 * The fix and its further edits (an import) made in [document], from the end backwards so no offset
 * moves; nothing is changed, and false is returned, where the text is no longer the one the fix was made for.
 */
internal fun Fix.applyTo(document: Document): Boolean {
    val all = replacements
    if (all.any { it.end > document.textLength }) return false
    for (e in all) document.replaceString(e.start, e.end, e.text)
    return true
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
        if (fix.applyTo(document)) PsiDocumentManager.getInstance(project).commitDocument(document)
    }
}

/** The fix the analysis lists first and prefers is the one it means: it goes to the top of the Alt+Enter list, above the IDE's own. */
class PreferredReplaceTextFix(
    fix: Fix,
) : ReplaceTextFix(fix),
    HighPriorityAction

/**
 * `$count` in a Kotlin string is a Kotlin template, not a signal: the interpolation trap. And a
 * Kotlin value in an HTML string, judged by where it lands as `interpolate` judges it.
 */
class KotlinInterpolationInspection : StreamlordInspection(INTERPOLATION_CODES)

private val INTERPOLATION_CODES =
    setOf("kotlin-interpolation", "unescaped-interpolation", "unsafe-interpolation", "interpolate-template", "interpolate-placeholders")

/**
 * Datastar expression syntax, unknown actions, Pro actions, the arguments and options an action is
 * given, the types a datastar-fetch handler compares, kebab-case signals, what the client's own
 * rewrite of an expression does that JavaScript would not, and `patch` where the attribute does not
 * hand it over.
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
            "scope-name",
            "unknown-option",
            "option-value",
            "action-arity",
            "action-argument",
            "fetch-event-type",
        ),
    )

/**
 * `data-*` attribute names, keys, modifiers and their arguments, values, an indicator with nothing
 * to show or set up too late, signal names the client cannot hold, and the values it evaluates as filters or computeds.
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
            "indicator-order",
            "signal-name",
            "computed-value",
            "filter-pattern",
            "signal-null",
            "missing-nonce",
            "invalid-nonce",
        ),
    )

/**
 * A `$name` no file in the project defines: a misspelt signal, or one renamed on one side only.
 * The analysis finds what each expression reads; the project index says what is defined, from
 * every Kotlin and markup file. Not run while the IDE indexes, when the index is not complete. An
 * expression handed to a call that resolves to something other than Streamlord is not read.
 *
 * The other direction goes with it, as VS Code's `diagnostics.unknownSignals` turns both on: a
 * signal a Kotlin file reads through a `@Serializable` class or a `Signals` lookup that no page
 * declares, `signal-never-declared`. A local inspection, though it reads the other files, since
 * it reports in this one only ("operate on one file at a time",
 * https://plugins.jetbrains.com/docs/intellij/code-inspections.html); the index holds the rest.
 */
class UnknownSignalInspection : StreamlordInspection(UNKNOWN_SIGNAL_CODES) {
    override fun issues(file: PsiFile): List<Issue>? {
        val analysis = StreamlordAnalysis.getInstance(file.project)
        val index = SignalIndex.getInstance(file.project)
        val refs =
            when {
                file is KtFile -> analysis.streamlordSignalReferences(file)
                StreamlordAnalysis.isMarkupSide(file) -> analysis.htmlSignalReferences(file)
                else -> return null
            }
        val drift = if (file is KtFile) index.driftIn(file) else emptyList()
        if (refs.isEmpty()) return drift
        val defined = index.definitionsSeenFrom(file)
        return refs.mapNotNull { unknownSignalIssue(it, defined) } + drift
    }
}

private val UNKNOWN_SIGNAL_CODES = setOf("unknown-signal", "signal-never-declared")

/**
 * Capitals in keys, which the browser lowercases, what the DSL writes on the wire, a key the DSL
 * refuses, a hyphen in a signal name, and a capital in the name of an attribute.
 */
class DatastarKeyCaseInspection :
    StreamlordInspection(setOf("key-case", "key-case-wire", "signal-name-hyphen", "attribute-name-case", "key-refused"))

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
        INTERPOLATION_CODES +
            UNKNOWN_SIGNAL_CODES +
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
                "scope-name",
                "unknown-option",
                "option-value",
                "action-arity",
                "action-argument",
                "fetch-event-type",
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
                "indicator-order",
                "signal-name",
                "computed-value",
                "filter-pattern",
                "signal-null",
                "missing-nonce",
                "invalid-nonce",
            ) +
            setOf("key-case", "key-case-wire", "signal-name-hyphen", "attribute-name-case", "key-refused") +
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
