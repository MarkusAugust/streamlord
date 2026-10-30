package io.github.markusaugust.streamlord.intellij.analysis

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import io.github.markusaugust.streamlord.analysis.Catalog
import io.github.markusaugust.streamlord.analysis.HoleFact
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.types.KaFlexibleType
import org.jetbrains.kotlin.analysis.api.types.KaType
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
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtLiteralStringTemplateEntry
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtPsiUtil
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtReturnExpression
import org.jetbrains.kotlin.psi.KtSafeQualifiedExpression
import org.jetbrains.kotlin.psi.KtStringTemplateEntryWithExpression
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.KtValueArgument
import org.jetbrains.kotlin.psi.KtWhenExpression

/**
 * What the Kotlin plugin knows of each value joined into an HTML string, for the analysis to
 * judge the hole by: a constant, markup, a number or a boolean, or a string. In K2 mode the types
 * come from the Analysis API; in K1 mode only what the declarations spell out. The analysis takes
 * these over what the text shows, so markup is only a hole whose whole value is markup. Plain
 * values out, so nothing of an analysis session outlives the call.
 */
object HoleFacts {
    private val HTML_CLASS = ClassId.fromString("io/github/markusaugust/streamlord/core/domain/Html")
    private const val HTML_PACKAGE = "io.github.markusaugust.streamlord.html"
    private const val KOTLINX_STREAM = "kotlinx.html.stream"

    /** A literal that opens with a tag, `<$tag` among them; `|` for a margin `trimMargin` removes. */
    private val HTML_OPENING = Regex("""^[\s|]*<(?:[A-Za-z$]|!--|!doctype)""", RegexOption.IGNORE_CASE)
    private val NUMBER_TYPE = Regex("""(?:kotlin\.)?(?:Int|Long|Short|Byte|Double|Float|UInt|ULong|UShort|UByte)""")
    private val BOOLEAN_TYPE = Regex("""(?:kotlin\.)?Boolean""")
    private val STRING_TYPE = Regex("""(?:kotlin\.)?(?:String|CharSequence)\??""")
    private val HTML_TYPE = Regex("""(?:io\.github\.markusaugust\.streamlord\.core\.domain\.)?Html\??""")
    private val TRIMS = setOf("trimIndent", "trimMargin")
    private val LISTS = setOf("listOf", "listOfNotNull", "arrayOf", "setOf", "sequenceOf")
    private val MARKUP_CALLS = setOf("interpolate", "Trusted")

    /** The expression helpers by name, for a call the Kotlin plugin cannot resolve. */
    private val HELPERS: Set<String> = Catalog.default.signalNameHelpers.keys

    /** How many calls deep a function's body is read for the markup it returns. */
    private const val CALL_DEPTH = 3

    /** The fact of every value in an HTML template or `+` chain, by the range the analysis gives its hole. */
    fun of(file: KtFile): Map<TextRange, HoleFact> {
        val holes = holes(file)
        if (holes.isEmpty()) return emptyMap()
        // The property the Kotlin plugin's own plugin.xml switches K1 on with; KotlinPluginModeProvider,
        // which reads it too, is scheduled for removal in 2026.2.
        val k1 = System.getProperty("idea.kotlin.plugin.use.k1").toBoolean()
        val typed = if (k1) typedK1(holes) else typedK2(file, holes)
        val facts = HashMap<TextRange, HoleFact>()
        for ((range, expr) in holes) {
            val fact = typed[range]
            facts[range] =
                when {
                    fact == HoleFact.CONSTANT || fact == HoleFact.MARKUP -> fact
                    isHelper(expr) -> HoleFact.CONSTANT
                    isMarkup(expr, CALL_DEPTH) -> HoleFact.MARKUP
                    else -> fact ?: continue
                }
        }
        return facts
    }

    /**
     * `$x` and `${...}` by the entry's range, and each operand of `+` that is not a literal by its
     * own, in the strings that may be HTML: a literal with a `<`, or one handed straight to a call.
     */
    private fun holes(file: KtFile): List<Pair<TextRange, KtExpression>> {
        val out = ArrayList<Pair<TextRange, KtExpression>>()
        val chained = HashSet<KtStringTemplateExpression>()
        for (plus in PsiTreeUtil.collectElementsOfType(file, KtBinaryExpression::class.java)) {
            if (!isPlus(plus) || isPlus(plus.parent)) continue
            val operands = ArrayList<KtExpression>()
            flatten(plus, operands)
            val literals = operands.filterIsInstance<KtStringTemplateExpression>()
            if (literals.isEmpty() || (literals.none(::hasTag) && plus.parent !is KtValueArgument)) continue
            chained += literals
            for (o in operands) if (o !is KtStringTemplateExpression) out += o.textRange to o
        }
        for (template in PsiTreeUtil.collectElementsOfType(file, KtStringTemplateExpression::class.java)) {
            if (template !in chained && !hasTag(template) && template.parent !is KtValueArgument) continue
            for (entry in template.entries) {
                if (entry !is KtStringTemplateEntryWithExpression) continue
                val expr = entry.expression ?: continue
                out += entry.textRange to expr
            }
        }
        return out
    }

    private fun hasTag(template: KtStringTemplateExpression): Boolean =
        template.entries.any { it is KtLiteralStringTemplateEntry && '<' in it.text }

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
            lower.isStringType || lower.isCharSequenceType -> {
                HoleFact.STRING
            }

            lower.isClassType(HTML_CLASS) -> {
                HoleFact.MARKUP
            }

            // A number or a boolean that may be null prints digits, true, false or null: safe where escaping is.
            lower.isIntType || lower.isLongType || lower.isShortType || lower.isByteType || lower.isDoubleType || lower.isFloatType ||
                lower.isUIntType || lower.isULongType || lower.isUShortType || lower.isUByteType -> {
                if (definite) HoleFact.NUMBER else HoleFact.NULLABLE
            }

            lower.isBooleanType -> {
                if (definite) HoleFact.BOOLEAN else HoleFact.NULLABLE
            }

            else -> {
                null
            }
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

                        type.endsWith(
                            '?',
                        ) && (NUMBER_TYPE.matches(type.dropLast(1)) || BOOLEAN_TYPE.matches(type.dropLast(1))) -> HoleFact.NULLABLE

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

    /** The package of the Kotlin declaration a call resolves to, or null when it resolves to nothing Kotlin. */
    private fun packageOf(target: PsiElement?): String? = (target?.containingFile as? KtFile)?.packageFqName?.asString()

    /**
     * A call to one of Streamlord's expression helpers, `signal("count")` or `post("/save")`: Datastar
     * code the author wrote, whatever page it ends up on. `elements { }` is markup, not code.
     */
    private fun isHelper(expr: KtExpression): Boolean {
        val call = KtPsiUtil.safeDeparenthesize(expr) as? KtCallExpression ?: return false
        val callee = call.calleeExpression as? KtNameReferenceExpression ?: return false
        if (callee.getReferencedName() == "elements") return false
        val target = callee.mainReference.resolve() ?: return callee.getReferencedName() in HELPERS
        return target is KtNamedFunction && packageOf(target) == HTML_PACKAGE
    }

    /** Whether a call by this name resolves into [pkg], or to nothing, as in a file that does not compile yet. */
    private fun callTo(
        callee: KtExpression?,
        names: Set<String>,
        pkg: (String) -> Boolean,
    ): Boolean {
        val ref = callee as? KtNameReferenceExpression ?: return false
        if (ref.getReferencedName() !in names) return false
        val target = ref.mainReference.resolve() ?: return true
        return packageOf(target)?.let(pkg) ?: StreamlordResolve.isStreamlord(target)
    }

    /**
     * Whether the whole value is markup the author built: an HTML literal or a trimmed one,
     * `interpolate(...)` or `Trusted(...)`, kotlinx.html output, a value or function declared `Html`
     * or marked `@Language("HTML")`, a function whose every return is markup, `joinToString` over
     * markup, an `if`, a `when` or a `?:` whose every branch is, or a `+` of markup and literals.
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

            is KtWhenExpression -> {
                e.entries.isNotEmpty() && e.entries.all { isMarkup(it.expression, depth) }
            }

            is KtBinaryExpression -> {
                when (e.operationToken) {
                    // `"<p>" + body + "</p>"`: markup with literals around it; a literal's own holes are judged apart.
                    KtTokens.PLUS -> {
                        val parts = ArrayList<KtExpression>().also { flatten(e, it) }
                        val markup = parts.map { isMarkup(it, depth) }
                        markup.any { it } && parts.indices.all { markup[it] || parts[it] is KtStringTemplateExpression }
                    }

                    KtTokens.ELVIS -> {
                        isMarkup(e.left, depth) && isMarkup(e.right, depth)
                    }

                    else -> {
                        false
                    }
                }
            }

            is KtQualifiedExpression -> {
                qualifiedMarkup(e, depth)
            }

            is KtCallExpression -> {
                val callee = e.calleeExpression
                callTo(callee, MARKUP_CALLS) { it.startsWith(StreamlordResolve.PACKAGE) } ||
                    callTo(callee, setOf("elements")) { it == HTML_PACKAGE } ||
                    (callee?.text == "buildString" && writesHtmlOnly(e.lambdaArguments.firstOrNull()?.getLambdaExpression())) ||
                    declaredMarkup(reference(e), depth)
            }

            is KtNameReferenceExpression -> {
                declaredMarkup(e, depth)
            }

            else -> {
                false
            }
        }
    }

    private fun qualifiedMarkup(
        e: KtQualifiedExpression,
        depth: Int,
    ): Boolean {
        val receiver = e.receiverExpression
        val selector = e.selectorExpression
        val call = selector as? KtCallExpression
        val name = (call?.calleeExpression ?: selector)?.text
        return when {
            name in TRIMS -> isMarkup(receiver, depth)

            name == "joinToString" && call != null -> joined(receiver, call, depth)

            // The text of an Html, or of interpolate(...).
            name == "text" && call == null -> isMarkup(receiver, depth)

            // createHTML().div { ... }, and StringBuilder().appendHTML().div { ... }.toString().
            call != null && isHtmlConsumer(receiver) -> true

            name == "toString" && receiver is KtQualifiedExpression && isHtmlConsumer(receiver.receiverExpression) -> true

            else -> declaredMarkup(reference(selector), depth)
        }
    }

    /** `createHTML()` or `x.appendHTML()`: kotlinx.html's consumers, which escape what they are given. */
    private fun isHtmlConsumer(e: KtExpression?): Boolean {
        val d = e?.let { KtPsiUtil.safeDeparenthesize(it) } ?: return false
        val call = (if (d is KtQualifiedExpression) d.selectorExpression else d) as? KtCallExpression ?: return false
        return callTo(call.calleeExpression, setOf("createHTML", "appendHTML")) { it == KOTLINX_STREAM }
    }

    /** `buildString { appendHTML().div { ... } }`: a lambda whose every statement writes kotlinx.html into the builder. */
    private fun writesHtmlOnly(lambda: KtLambdaExpression?): Boolean {
        val statements = lambda?.bodyExpression?.statements ?: return false
        return statements.isNotEmpty() &&
            statements.all { s ->
                var receiver: KtExpression? = s
                while (receiver is KtQualifiedExpression) {
                    if (isHtmlConsumer(receiver.receiverExpression)) return@all true
                    receiver = receiver.receiverExpression
                }
                false
            }
    }

    /**
     * `items.joinToString { "<li>...</li>" }`, `items.map { "<li>...</li>" }.joinToString("")`, or
     * `listOf(a, b).joinToString()` where every element is markup.
     */
    private fun joined(
        receiver: KtExpression,
        call: KtCallExpression,
        depth: Int,
    ): Boolean {
        val transform =
            call.lambdaArguments.firstOrNull()?.getLambdaExpression()
                ?: call.valueArguments.firstOrNull { it.getArgumentName()?.asName?.asString() == "transform" }?.getArgumentExpression()
        if (transform != null) return lambdaMarkup(transform, depth)
        return elementsMarkup(receiver, depth)
    }

    private fun lambdaMarkup(
        e: KtExpression?,
        depth: Int,
    ): Boolean {
        val lambda = e?.let { KtPsiUtil.safeDeparenthesize(it) } as? KtLambdaExpression ?: return false
        return isMarkup(lambda.bodyExpression?.statements?.lastOrNull(), depth)
    }

    private fun elementsMarkup(
        receiver: KtExpression,
        depth: Int,
    ): Boolean =
        when (val r = KtPsiUtil.safeDeparenthesize(receiver)) {
            is KtCallExpression -> {
                if (r.calleeExpression?.text !in LISTS) {
                    false
                } else {
                    val elements = r.valueArguments.map { it.getArgumentExpression() }
                    elements.isNotEmpty() && elements.all { isMarkup(it, depth) }
                }
            }

            is KtQualifiedExpression -> {
                val map = r.selectorExpression as? KtCallExpression
                map?.calleeExpression?.text == "map" && lambdaMarkup(map.lambdaArguments.firstOrNull()?.getLambdaExpression(), depth)
            }

            else -> {
                false
            }
        }

    /**
     * A value or function declared `Html` or marked `@Language("HTML")`, a `val` whose initializer
     * is markup, or a function whose every return is.
     */
    private fun declaredMarkup(
        ref: KtNameReferenceExpression?,
        depth: Int,
    ): Boolean {
        if (ref == null || depth == 0) return false
        val target = ref.mainReference.resolve() ?: return false
        if (target is KtAnnotated && languageHtml(target)) return true
        if (target is KtCallableDeclaration) {
            val type = target.typeReference?.text?.filterNot { it.isWhitespace() }
            if (type != null && HTML_TYPE.matches(type)) return true
        }
        if (target is KtProperty) {
            return !target.isVar && target.typeReference == null && isMarkup(target.initializer, depth - 1)
        }
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
