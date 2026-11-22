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
import io.github.markusaugust.streamlord.analysis.IssueCode
import io.github.markusaugust.streamlord.analysis.Severity
import io.github.markusaugust.streamlord.analysis.SourceLanguage
import io.github.markusaugust.streamlord.analysis.projectSignalIssues
import io.github.markusaugust.streamlord.intellij.analysis.StreamlordAnalysis
import io.github.markusaugust.streamlord.intellij.index.SignalIndex
import org.jetbrains.kotlin.psi.KtFile

/**
 * One inspection per topic of checks, all reading the same cached analysis of the file. The
 * Kotlin side judges Kotlin files, whether the string is handed to a Streamlord call or free-
 * standing; the HTML side judges HTML and template files.
 */
abstract class StreamlordInspection internal constructor(
    private val topic: Topic,
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

        fun range(issue: Issue) = TextRange(issue.start, minOf(maxOf(issue.end, issue.start + 1), length))
        return issues
            .filter { topicOf(it.code) == topic && it.start < length }
            .map { issue ->
                val range = range(issue)
                val chosen = profiled?.takes(issue.severity) == true
                val type = if (chosen) ProblemHighlightType.GENERIC_ERROR_OR_WARNING else highlightType(issue.severity)
                // IntelliJ draws no weaker problem whose range an error's holds, so the error says it too and offers its fixes.
                val covered =
                    if (type != ProblemHighlightType.GENERIC_ERROR) {
                        emptyList()
                    } else {
                        issues.filter { it !== issue && it.severity != Severity.ERROR && it.start < length && range.contains(range(it)) }
                    }
                manager.createProblemDescriptor(
                    file,
                    range,
                    described(issue) + covered.joinToString("") { " Also: ${described(it)}" },
                    type,
                    isOnTheFly,
                    *quickFixes(issue.fixes + covered.flatMap { it.fixes }),
                )
            }.toTypedArray()
    }

    /**
     * The level the user has given this inspection in the profile, beside the one plugin.xml declares, or null when it is
     * the declared one. One topic mixes severities (a syntax error beside a Pro hint), so by default each issue keeps the one
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

    /** The message and the page it links to. */
    private fun described(issue: Issue): String = issue.message + (issue.link?.let { " See $it" } ?: "")

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
 * The analysis's fixes, the first at the top of the Alt+Enter list where it is preferred or leads: the IDE
 * sorts one problem's other fixes by their titles, and would put "Mark … as markup" above the fix that marks the
 * values with `+`. Where the choice between other fixes is the author's, no fix goes above the others.
 */
private fun quickFixes(fixes: List<Fix>): Array<LocalQuickFix> =
    fixes
        .mapIndexed { i, fix ->
            when {
                i > 0 -> ReplaceTextFix(fix)
                fix.preferred -> PreferredReplaceTextFix(fix)
                fix.leads -> FirstReplaceTextFix(fix)
                else -> ReplaceTextFix(fix)
            }
        }.toTypedArray()

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

/** A fix the analysis lists first without preferring it: at the top of the list all the same. */
class FirstReplaceTextFix(
    fix: Fix,
) : ReplaceTextFix(fix),
    HighPriorityAction

/**
 * `$count` in a Kotlin string is a Kotlin template, not a signal: the interpolation trap. And a
 * Kotlin value in an HTML string, judged by where it lands as `interpolate` judges it.
 */
class KotlinInterpolationInspection : StreamlordInspection(Topic.INTERPOLATION)

/**
 * Datastar expression syntax, unknown actions, Pro actions, the arguments and options an action is
 * given, the types a datastar-fetch handler compares, kebab-case signals, what the client's own
 * rewrite of an expression does that JavaScript would not, `patch` where the attribute does not
 * hand it over, and a comma that a helper writing one value turns into the comma operator.
 */
class DatastarExpressionInspection : StreamlordInspection(Topic.EXPRESSION)

/**
 * `data-*` attribute names, keys, modifiers and their arguments, values, an indicator with nothing
 * to show or set up too late, signal names the client cannot hold, and the values it evaluates as filters or computeds.
 */
class DatastarAttributeInspection : StreamlordInspection(Topic.ATTRIBUTE)

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
class UnknownSignalInspection : StreamlordInspection(Topic.UNKNOWN_SIGNAL) {
    override fun issues(file: PsiFile): List<Issue>? {
        val analysis = StreamlordAnalysis.getInstance(file.project)
        val index = SignalIndex.getInstance(file.project)
        val refs =
            when {
                file is KtFile -> analysis.streamlordSignalReferences(file)
                StreamlordAnalysis.isMarkupSide(file) -> analysis.htmlSignalReferences(file)
                else -> return null
            }
        val language = if (file is KtFile) SourceLanguage.KOTLIN else SourceLanguage.HTML
        return projectSignalIssues(language, refs, index.projectSignals(file))
    }
}

/**
 * Capitals in keys, which the browser lowercases, what the DSL writes on the wire, a key the DSL
 * refuses, a hyphen in a signal name, and a capital in the name of an attribute.
 */
class DatastarKeyCaseInspection : StreamlordInspection(Topic.KEY_CASE)

/** The markup of a patch: complete elements, ids, selectors and modes, scripts, and the arguments core refuses as it runs. */
class StreamlordMarkupInspection : StreamlordInspection(Topic.MARKUP)

/** The six inspections, as the settings list them: each code of the analysis belongs to one. */
internal enum class Topic { INTERPOLATION, EXPRESSION, ATTRIBUTE, UNKNOWN_SIGNAL, KEY_CASE, MARKUP }

/** The inspection that shows [code]. Without an `else`, so a code the analysis gains stops the build until it is placed. */
internal fun topicOf(code: IssueCode): Topic =
    when (code) {
        IssueCode.KOTLIN_INTERPOLATION,
        IssueCode.UNESCAPED_INTERPOLATION,
        IssueCode.UNSAFE_INTERPOLATION,
        IssueCode.INTERPOLATE_TEMPLATE,
        IssueCode.INTERPOLATE_PLACEHOLDERS,
        -> Topic.INTERPOLATION

        IssueCode.EXPRESSION_SYNTAX,
        IssueCode.EMPTY_EXPRESSION,
        IssueCode.ONE_VALUE,
        IssueCode.UNKNOWN_ACTION,
        IssueCode.ACTION_SPACE,
        IssueCode.PRO_ACTION,
        IssueCode.SIGNAL_KEBAB,
        IssueCode.LITERAL_REWRITTEN,
        IssueCode.SIGNAL_NOT_READ,
        IssueCode.STATEMENT_SPLIT,
        IssueCode.SCOPE_NAME,
        IssueCode.UNKNOWN_OPTION,
        IssueCode.OPTION_VALUE,
        IssueCode.ACTION_ARITY,
        IssueCode.ACTION_ARGUMENT,
        IssueCode.FETCH_EVENT_TYPE,
        -> Topic.EXPRESSION

        IssueCode.UNKNOWN_ATTRIBUTE,
        IssueCode.UNKNOWN_MODIFIER,
        IssueCode.MODIFIER_ARGS,
        IssueCode.MISSING_KEY,
        IssueCode.UNEXPECTED_KEY,
        IssueCode.WRONG_ELEMENT,
        IssueCode.SIGNAL_NAME_EXPECTED,
        IssueCode.UNEXPECTED_VALUE,
        IssueCode.MISSING_VALUE,
        IssueCode.KEY_AND_VALUE,
        IssueCode.MISSING_KEY_OR_VALUE,
        IssueCode.PRO_ATTRIBUTE,
        IssueCode.PREFIX_MISMATCH,
        IssueCode.INDICATOR_WITHOUT_ACTION,
        IssueCode.INDICATOR_ORDER,
        IssueCode.REPEATED_ATTRIBUTE,
        IssueCode.SIGNAL_NAME,
        IssueCode.COMPUTED_VALUE,
        IssueCode.FILTER_PATTERN,
        IssueCode.SIGNAL_NULL,
        IssueCode.MISSING_NONCE,
        IssueCode.INVALID_NONCE,
        -> Topic.ATTRIBUTE

        IssueCode.UNKNOWN_SIGNAL,
        IssueCode.SIGNAL_NEVER_DECLARED,
        -> Topic.UNKNOWN_SIGNAL

        IssueCode.KEY_CASE,
        IssueCode.KEY_CASE_WIRE,
        IssueCode.SIGNAL_NAME_HYPHEN,
        IssueCode.ATTRIBUTE_NAME_CASE,
        IssueCode.KEY_REFUSED,
        -> Topic.KEY_CASE

        IssueCode.MISSING_ID,
        IssueCode.MODE_NEEDS_SELECTOR,
        IssueCode.UNCLOSED,
        IssueCode.STRAY_CLOSE,
        IssueCode.TOP_LEVEL_TEXT,
        IssueCode.BLANK_SELECTOR,
        IssueCode.SELECTOR_NEWLINE,
        IssueCode.SCRIPT_CLOSE,
        IssueCode.EVENT_REFUSED,
        -> Topic.MARKUP
    }
