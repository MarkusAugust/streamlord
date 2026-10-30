package io.github.markusaugust.streamlord.intellij.analysis

import com.intellij.openapi.util.TextRange
import com.intellij.psi.util.PsiTreeUtil
import io.github.markusaugust.streamlord.analysis.HoleFact
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.types.KaFlexibleType
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.idea.base.plugin.KotlinPluginModeProvider
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.psi.KtAnnotated
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtCallableDeclaration
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtIfExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtPsiUtil
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtReturnExpression
import org.jetbrains.kotlin.psi.KtSafeQualifiedExpression
import org.jetbrains.kotlin.psi.KtStringTemplateEntryWithExpression
import org.jetbrains.kotlin.psi.KtStringTemplateExpression

/**
 * What the Kotlin plugin knows of each value joined into a string, for the analysis to judge the
 * hole by: a constant, markup, a number or a boolean, or a string. In K2 mode the types come from
 * the Analysis API; in K1 mode only what the declarations spell out. Plain values out, so nothing
 * of an analysis session outlives the call.
 */
object HoleFacts {
    private val HTML_CLASS = ClassId.fromString("io/github/markusaugust/streamlord/core/domain/Html")

    /** A literal that opens with a tag, `<$tag` among them; `|` for a margin `trimMargin` removes. */
    private val HTML_OPENING = Regex("""^[\s|]*<(?:[A-Za-z$]|!--|!doctype)""", RegexOption.IGNORE_CASE)
    private val NUMBER_TYPE = Regex("""(?:kotlin\.)?(?:Int|Long|Short|Byte|Double|Float|UInt|ULong|UShort|UByte)""")
    private val BOOLEAN_TYPE = Regex("""(?:kotlin\.)?Boolean""")
    private val STRING_TYPE = Regex("""(?:kotlin\.)?(?:String|CharSequence)\??""")
    private val HTML_TYPE = Regex("""(?:io\.github\.markusaugust\.streamlord\.core\.domain\.)?Html\??""")
    private val TRIMS = setOf("trimIndent", "trimMargin", "trim")
    private val LISTS = setOf("listOf", "listOfNotNull", "arrayOf", "setOf", "sequenceOf")

    /** How many calls deep a function's body is read for the markup it returns. */
    private const val CALL_DEPTH = 3

    /** The fact of every value in a template or a `+` chain with a literal, by the range the analysis gives its hole. */
    fun of(file: KtFile): Map<TextRange, HoleFact> {
        val holes = holes(file)
        if (holes.isEmpty()) return emptyMap()
        val typed = if (KotlinPluginModeProvider.isK2Mode()) typedK2(file, holes) else typedK1(holes)
        val facts = HashMap<TextRange, HoleFact>()
        for ((range, expr) in holes) {
            val fact = typed[range]
            facts[range] =
                when {
                    fact == HoleFact.CONSTANT || fact == HoleFact.MARKUP -> fact
                    isMarkup(expr, CALL_DEPTH) -> HoleFact.MARKUP
                    else -> fact ?: continue
                }
        }
        return facts
    }

    /** `$x` and `${...}` by the entry's range, and each operand of `+` that is not a literal by its own. */
    private fun holes(file: KtFile): List<Pair<TextRange, KtExpression>> {
        val out = ArrayList<Pair<TextRange, KtExpression>>()
        for (entry in PsiTreeUtil.collectElementsOfType(file, KtStringTemplateEntryWithExpression::class.java)) {
            val expr = entry.expression ?: continue
            out += entry.textRange to expr
        }
        for (plus in PsiTreeUtil.collectElementsOfType(file, KtBinaryExpression::class.java)) {
            if (!isPlus(plus) || isPlus(plus.parent)) continue
            val operands = ArrayList<KtExpression>()
            flatten(plus, operands)
            if (operands.none { it is KtStringTemplateExpression }) continue
            for (o in operands) if (o !is KtStringTemplateExpression) out += o.textRange to o
        }
        return out
    }

    private fun isPlus(e: Any?): Boolean = e is KtBinaryExpression && e.operationToken == KtTokens.PLUS

    private fun flatten(
        e: KtExpression?,
        out: MutableList<KtExpression>,
    ) {
        if (e == null) return
        if (e is KtBinaryExpression && isPlus(e)) {
            flatten(e.left, out)
            flatten(e.right, out)
        } else {
            out += e
        }
    }

    private fun typedK2(
        file: KtFile,
        holes: List<Pair<TextRange, KtExpression>>,
    ): Map<TextRange, HoleFact> =
        analyze(file) {
            val out = HashMap<TextRange, HoleFact>()
            for ((range, expr) in holes) {
                val fact = if (expr.evaluate() != null) HoleFact.CONSTANT else typeFact(expr.expressionType)
                if (fact != null) out[range] = fact
            }
            out
        }

    private fun KaSession.typeFact(type: KaType?): HoleFact? {
        if (type == null) return null
        // A Java String may be null, and is a string all the same; a Java Integer is not a number that is safe.
        val lower = (type as? KaFlexibleType)?.lowerBound ?: type
        val definite = type !is KaFlexibleType && !type.isMarkedNullable
        return when {
            lower.isStringType || lower.isCharSequenceType -> HoleFact.STRING

            lower.isClassType(HTML_CLASS) -> HoleFact.MARKUP

            !definite -> null

            type.isIntType || type.isLongType || type.isShortType || type.isByteType || type.isDoubleType || type.isFloatType ||
                type.isUIntType || type.isULongType || type.isUShortType || type.isUByteType -> HoleFact.NUMBER

            type.isBooleanType -> HoleFact.BOOLEAN

            else -> null
        }
    }

    /** K1: a `const val` the reference resolves to, or the type its declaration writes out. */
    private fun typedK1(holes: List<Pair<TextRange, KtExpression>>): Map<TextRange, HoleFact> {
        val out = HashMap<TextRange, HoleFact>()
        for ((range, expr) in holes) {
            // `a?.b` may be null whatever `b` declares.
            if (KtPsiUtil.safeDeparenthesize(expr) is KtSafeQualifiedExpression) continue
            val target = reference(expr)?.mainReference?.resolve() as? KtCallableDeclaration ?: continue
            val fact =
                if (target is KtProperty && target.hasModifier(KtTokens.CONST_KEYWORD)) {
                    HoleFact.CONSTANT
                } else {
                    val type = target.typeReference?.text?.filterNot { it.isWhitespace() } ?: continue
                    when {
                        NUMBER_TYPE.matches(type) -> HoleFact.NUMBER
                        BOOLEAN_TYPE.matches(type) -> HoleFact.BOOLEAN
                        STRING_TYPE.matches(type) -> HoleFact.STRING
                        HTML_TYPE.matches(type) -> HoleFact.MARKUP
                        else -> continue
                    }
                }
            out[range] = fact
        }
        return out
    }

    /** The name an expression's value comes from: `x`, `a.x`, `f()`, `a.f()`. */
    private fun reference(expr: KtExpression?): KtNameReferenceExpression? =
        when (val e = expr?.let { KtPsiUtil.safeDeparenthesize(it) }) {
            is KtNameReferenceExpression -> e
            is KtCallExpression -> e.calleeExpression as? KtNameReferenceExpression
            is KtQualifiedExpression -> reference(e.selectorExpression)
            else -> null
        }

    /**
     * Markup the author built: an HTML literal, `interpolate(...)`, a value or function marked
     * `@Language("HTML")`, a function that returns such, `joinToString` over such, or a `+` chain
     * that starts with one.
     */
    private fun isMarkup(
        expr: KtExpression?,
        depth: Int,
    ): Boolean {
        val e = expr?.let { KtPsiUtil.safeDeparenthesize(it) } ?: return false
        return when (e) {
            is KtStringTemplateExpression -> {
                HTML_OPENING.containsMatchIn(e.text.trimStart('$', '"'))
            }

            is KtIfExpression -> {
                isMarkup(e.then, depth) && isMarkup(e.`else`, depth)
            }

            is KtBinaryExpression -> {
                isPlus(e) && isMarkup(e.left, depth)
            }

            is KtQualifiedExpression -> {
                val selector = e.selectorExpression
                when ((selector as? KtCallExpression)?.calleeExpression?.text) {
                    in TRIMS -> isMarkup(e.receiverExpression, depth)
                    "joinToString" -> joined(e.receiverExpression, selector, depth)
                    else -> declaredMarkup(reference(selector), depth)
                }
            }

            is KtCallExpression -> {
                e.calleeExpression?.text == "interpolate" || declaredMarkup(reference(e), depth)
            }

            is KtNameReferenceExpression -> {
                declaredMarkup(e, depth)
            }

            else -> {
                false
            }
        }
    }

    /** `items.joinToString { "<li>...</li>" }`, or `listOf(a, b).joinToString()` where every element is markup. */
    private fun joined(
        receiver: KtExpression,
        call: KtCallExpression,
        depth: Int,
    ): Boolean {
        val lambda = call.lambdaArguments.firstOrNull()?.getLambdaExpression()
        if (lambda != null) return isMarkup(lambda.bodyExpression?.statements?.lastOrNull(), depth)
        val list = KtPsiUtil.safeDeparenthesize(receiver) as? KtCallExpression ?: return false
        if (list.calleeExpression?.text !in LISTS) return false
        val elements = list.valueArguments.map { it.getArgumentExpression() }
        return elements.isNotEmpty() && elements.all { isMarkup(it, depth) }
    }

    /** A value or function marked `@Language("HTML")`, or a function whose every return is markup. */
    private fun declaredMarkup(
        ref: KtNameReferenceExpression?,
        depth: Int,
    ): Boolean {
        if (ref == null || depth == 0) return false
        val target = ref.mainReference.resolve() ?: return false
        if (target is KtAnnotated && languageHtml(target)) return true
        if (target !is KtNamedFunction) return false
        if (!target.hasBlockBody()) return isMarkup(target.bodyExpression, depth - 1)
        val returned =
            PsiTreeUtil
                .collectElementsOfType(target.bodyExpression, KtReturnExpression::class.java)
                // Its own returns: not a labelled one, not one of a function declared inside it.
                .filter { it.getTargetLabel() == null && PsiTreeUtil.getParentOfType(it, KtNamedFunction::class.java) == target }
                .map { it.returnedExpression }
        return returned.isNotEmpty() && returned.all { isMarkup(it, depth - 1) }
    }

    private fun languageHtml(target: KtAnnotated): Boolean =
        target.annotationEntries.any { a ->
            a.shortName?.asString() == "Language" &&
                a.valueArguments
                    .firstOrNull()
                    ?.getArgumentExpression()
                    ?.text
                    ?.trim('"')
                    .equals("HTML", ignoreCase = true)
        }
}
