package io.github.markusaugust.streamlord.intellij.analysis

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import io.github.markusaugust.streamlord.analysis.ValueAnswer
import io.github.markusaugust.streamlord.analysis.ValueQuestion
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.analysis.api.types.KaFlexibleType
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtEnumEntry
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtPsiUtil
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtStringTemplateEntryWithExpression

/**
 * What the Kotlin plugin answers of each value the analysis asks about, which only resolving tells: the type the Analysis
 * API gives it, whether it is a constant, and the declaration it names. The analysis judges the value by these.
 * Plain values out, so nothing of an analysis session outlives the call.
 */
object ValueAnswers {
    /** A declaration longer than this is not sent: the analysis reads each it is given as a file. */
    private const val LONGEST_DECLARATION = 20_000

    /** The answer to each value the analysis asks about in [file], by the range it asks by. */
    fun of(
        file: KtFile,
        questions: List<ValueQuestion>,
    ): Map<TextRange, ValueAnswer> {
        val asked = questions.mapNotNull { q -> expressionAt(file, q.start, q.end)?.let { q to it } }
        if (asked.isEmpty()) return emptyMap()
        return analyze(file) {
            asked.associate { (q, e) ->
                TextRange(q.start, q.end) to ValueAnswer(written(e.expressionType), e.evaluate() != null || enumEntry(e), declaration(e))
            }
        }
    }

    /**
     * The expression that stands at exactly [start] to [end]: a template's own, or what is written there, without the
     * parentheses around all of it. Null where the analysis asks by a range that is no expression's, as it does for a value
     * it could not read whole.
     */
    private fun expressionAt(
        file: KtFile,
        start: Int,
        end: Int,
    ): KtExpression? {
        var element: PsiElement? = file.findElementAt(start)
        while (element != null && element !is KtFile) {
            val range = element.textRange
            if (range.startOffset != start || range.endOffset > end) return null
            if (range.endOffset == end) {
                if (element is KtStringTemplateEntryWithExpression) return element.expression
                if (element is KtExpression) return KtPsiUtil.safeDeparenthesize(element)
            }
            element = element.parent
        }
        return null
    }

    /**
     * The type as [ValueAnswer.type] has it: `kotlin.collections.List<kotlin.String>?`. A value of a type of Java's may be
     * null, and is written so; a type argument of Java's is written as the class it names. Null for a type that is no
     * class, and `*` for such a type argument, a star among them.
     */
    private fun KaSession.written(
        type: KaType?,
        argument: Boolean = false,
    ): String? {
        if (type == null) return null
        val cls = ((type as? KaFlexibleType)?.lowerBound ?: type) as? KaClassType ?: return null
        val arguments = cls.typeArguments.takeIf { it.isNotEmpty() }?.joinToString(",", "<", ">") { written(it.type, true) ?: "*" }
        val nullable = if (type is KaFlexibleType) !argument else type.isMarkedNullable
        return cls.classId.asFqNameString() + arguments.orEmpty() + if (nullable) "?" else ""
    }

    /** `Tab.HOME`, or its `name`: an entry of a Kotlin enum, which K2 folds into no constant. */
    private fun enumEntry(expr: KtExpression): Boolean {
        val e = KtPsiUtil.safeDeparenthesize(expr)
        val entry = if (e is KtQualifiedExpression && e.selectorExpression?.text == "name") e.receiverExpression else e
        return reference(entry)?.takeIf { entry !is KtCallExpression }?.mainReference?.resolve() is KtEnumEntry
    }

    /** The name an expression's value comes from: `x`, `a.x`, `f()`, `a.f()`. */
    private fun reference(expr: KtExpression?): KtNameReferenceExpression? =
        when (val e = expr?.let { KtPsiUtil.safeDeparenthesize(it) }) {
            is KtNameReferenceExpression -> e
            is KtCallExpression -> e.calleeExpression as? KtNameReferenceExpression
            is KtQualifiedExpression -> reference(e.selectorExpression)
            else -> null
        }

    /** The `val` or the function of Kotlin source that the whole of [expr] names, as its file writes it. */
    private fun declaration(expr: KtExpression): String? {
        val target = reference(expr)?.mainReference?.resolve() ?: return null
        val declares = target is KtProperty || target is KtNamedFunction || (target is KtParameter && target.hasValOrVar())
        if (!declares || (target.containingFile as? KtFile)?.isCompiled != false) return null
        return target.text.takeIf { it.length <= LONGEST_DECLARATION }
    }
}
