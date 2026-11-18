package io.github.markusaugust.streamlord.intellij.analysis

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import io.github.markusaugust.streamlord.analysis.Catalog
import io.github.markusaugust.streamlord.analysis.ValueAnswer
import io.github.markusaugust.streamlord.analysis.ValueFact
import io.github.markusaugust.streamlord.analysis.ValueQuestion
import io.github.markusaugust.streamlord.analysis.fact
import io.github.markusaugust.streamlord.analysis.isMarkup
import io.github.markusaugust.streamlord.analysis.opensWithTag
import io.github.markusaugust.streamlord.analysis.writesItself
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.analysis.api.types.KaFlexibleType
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtAnnotated
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtCallableDeclaration
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtConstantExpression
import org.jetbrains.kotlin.psi.KtEnumEntry
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtIfExpression
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParenthesizedExpression
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtPsiUtil
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtReturnExpression
import org.jetbrains.kotlin.psi.KtStringTemplateEntryWithExpression
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.KtTypeReference
import org.jetbrains.kotlin.psi.KtWhenExpression

/**
 * What the Kotlin plugin answers of each value the analysis asks about: the type the Analysis API resolves it to, whether
 * it is a constant, and what its source shows of markup and of the author's own. The analysis judges the value by these.
 * Plain values out, so nothing of an analysis session outlives the call.
 */
object ValueAnswers {
    private const val HTML_PACKAGE = "io.github.markusaugust.streamlord.html"
    private const val KOTLINX_STREAM = "kotlinx.html.stream"

    private val TRIMS = setOf("trimIndent", "trimMargin")
    private val LISTS = setOf("listOf", "listOfNotNull", "arrayOf", "setOf", "sequenceOf")
    private val MARKUP_CALLS = setOf("interpolate", "Trusted")

    /** The expression helpers by name, for a call the Kotlin plugin cannot resolve. */
    private val HELPERS: Set<String> = Catalog.default.signalNameHelpers.keys

    /** How many calls deep a function's body is read for the markup it returns. */
    private const val CALL_DEPTH = 3

    /**
     * The answer to each value the analysis asks about in [file], by the range it asks by. An argument of
     * `interpolate(...)` is answered only where its class is somebody else's: the analysis reads the rest of it from the text.
     */
    fun of(
        file: KtFile,
        questions: List<ValueQuestion>,
    ): Map<TextRange, ValueAnswer> {
        val asked = questions.mapNotNull { q -> expressionAt(file, q.start, q.end)?.let { q to it } }
        if (asked.isEmpty()) return emptyMap()
        return analyze(file) {
            val constant = { e: KtExpression -> e.evaluate() != null || enumEntry(e) }
            val type = { e: KtExpression -> written(e.expressionType) }
            val markup = Markup({ ValueAnswer(type(it)).fact() == ValueFact.MARKUP }, { ValueAnswer(written(it.type)).fact().isMarkup() })
            buildMap {
                for ((q, e) in asked) {
                    val answer =
                        if (q.argument) {
                            ValueAnswer(
                                type(e),
                            ).takeIf { it.fact() == ValueFact.OTHER }
                        } else {
                            answer(e, constant, type, markup)
                        }
                    answer?.let { put(TextRange(q.start, q.end), it) }
                }
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
     * The type as Kotlin writes it, every class by its qualified name: `kotlin.collections.List<kotlin.String>?`. A type of
     * Java's may be null, and is written so. Null for a type that is no class, and `*` for such a type argument.
     */
    private fun KaSession.written(type: KaType?): String? {
        if (type == null) return null
        val cls = ((type as? KaFlexibleType)?.lowerBound ?: type) as? KaClassType ?: return null
        val arguments = cls.typeArguments.takeIf { it.isNotEmpty() }?.joinToString(",", "<", ">") { written(it.type) ?: "*" }
        val nullable = type is KaFlexibleType || type.isMarkedNullable
        return cls.classId.asFqNameString() + arguments.orEmpty() + if (nullable) "?" else ""
    }

    /**
     * What [constant] and [type] say of a value, and where they leave it open, what its source shows: a call to a helper
     * with the author's own arguments, markup, or a literal only.
     */
    private fun answer(
        expr: KtExpression,
        constant: (KtExpression) -> Boolean,
        type: (KtExpression) -> String?,
        markup: Markup,
    ): ValueAnswer {
        val typed = ValueAnswer(type(expr), constant(expr))
        if (typed.constant == true || typed.fact().isMarkup()) return typed
        val read =
            when {
                isHelper(expr) && ownArguments(expr, constant, type) -> ValueFact.CONSTANT
                markup.isMarkup(expr, CALL_DEPTH) -> ValueFact.MARKUP
                literalOnly(expr) -> ValueFact.CONSTANT
                else -> return typed
            }
        return typed.copy(read = read)
    }

    /**
     * A value the author wrote, or one that cannot carry code: a constant, a literal only, an enum
     * entry or its name, a number or a boolean, or a call to a helper whose arguments are all such. A value from anywhere else, `signal(q)` with a `q`
     * from the request, runs as code on the page.
     */
    private fun authorsOwn(
        expr: KtExpression?,
        constant: (KtExpression) -> Boolean,
        type: (KtExpression) -> String?,
    ): Boolean {
        val e = expr?.let { KtPsiUtil.safeDeparenthesize(it) } ?: return false
        if (constant(e) || literalOnly(e)) return true
        if (isHelper(e)) return ownArguments(e, constant, type)
        return ValueAnswer(type(e)).fact().writesItself()
    }

    /**
     * Every argument of a helper call is the author's own, and a lambda of options sets each of
     * its properties to such a value: `post("/save") { openWhenHidden = true }`.
     */
    private fun ownArguments(
        expr: KtExpression,
        constant: (KtExpression) -> Boolean,
        type: (KtExpression) -> String?,
    ): Boolean {
        val call = KtPsiUtil.safeDeparenthesize(expr) as? KtCallExpression ?: return false
        return call.valueArguments.all { argument ->
            val value = argument.getArgumentExpression()
            val lambda = value?.let { KtPsiUtil.safeDeparenthesize(it) } as? KtLambdaExpression
            if (lambda == null) {
                authorsOwn(value, constant, type)
            } else {
                lambda.bodyExpression?.statements.orEmpty().all { s ->
                    s is KtBinaryExpression && s.operationToken == KtTokens.EQ && authorsOwn(s.right, constant, type)
                }
            }
        }
    }

    /**
     * `Tab.HOME`, or its `name`: an entry of a Kotlin enum that does not override `toString`, so what it
     * writes is the name the author gave it. K2 folds no enum entry into a constant.
     */
    private fun enumEntry(expr: KtExpression): Boolean {
        val e = KtPsiUtil.safeDeparenthesize(expr)
        if (e is KtQualifiedExpression && e.selectorExpression?.text == "name") return entryOf(e.receiverExpression) != null
        val entry = entryOf(e) ?: return false
        val owner = PsiTreeUtil.getParentOfType(entry, KtClass::class.java) ?: return false
        // An entry with a body of its own may override toString too.
        return listOf(entry, owner).none { c -> c.declarations.any { it is KtNamedFunction && it.name == "toString" } }
    }

    /** The Kotlin enum entry a name or a qualified name resolves to; a Java one would need the Java plugin. */
    private fun entryOf(expr: KtExpression?): KtEnumEntry? {
        val e = expr?.let { KtPsiUtil.safeDeparenthesize(it) }
        val ref = (if (e is KtQualifiedExpression) e.selectorExpression else e) as? KtNameReferenceExpression ?: return null
        return ref.mainReference.resolve() as? KtEnumEntry
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

    /** Whether `.trimMargin()` is called on [literal], which then starts past its first `|`. */
    private fun marginTrimmed(literal: KtExpression): Boolean {
        var receiver: PsiElement = literal
        while (receiver.parent is KtParenthesizedExpression) receiver = receiver.parent
        val q = receiver.parent as? KtQualifiedExpression ?: return false
        val call = q.selectorExpression as? KtCallExpression
        return q.receiverExpression == receiver && call?.calleeExpression?.text == "trimMargin"
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
     * code, the author's own when its arguments are. `elements { }` is markup, not code.
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
     * A value whose every result the source spells out: a literal without templates, or an `if`,
     * `when` or `?:` that picks between such. `evaluate()` folds none of these, and their type is
     * String, but what they write is the author's.
     */
    private fun literalOnly(expr: KtExpression?): Boolean {
        val e = expr?.let { KtPsiUtil.safeDeparenthesize(it) } ?: return false
        return when (e) {
            is KtStringTemplateExpression -> e.entries.none { it is KtStringTemplateEntryWithExpression }
            is KtConstantExpression -> true
            is KtIfExpression -> literalOnly(e.then) && literalOnly(e.`else`)
            is KtWhenExpression -> e.entries.isNotEmpty() && e.entries.all { literalOnly(it.expression) }
            is KtBinaryExpression -> e.operationToken == KtTokens.ELVIS && literalOnly(e.left) && literalOnly(e.right)
            else -> false
        }
    }

    /**
     * The reading of markup, with what the editor knows of the elements of a collection, Html or
     * Trusted, for a `joinToString` without a transform, and of a type a declaration writes.
     */
    private class Markup(
        private val markupElements: (KtExpression) -> Boolean,
        private val markupType: (KtTypeReference) -> Boolean,
    ) {
        private fun branchesMarkup(
            branches: List<KtExpression?>,
            depth: Int,
        ): Boolean {
            val markup = branches.map { isMarkup(it, depth) }
            return markup.any { it } && branches.indices.all { markup[it] || literalOnly(branches[it]) }
        }

        /**
         * Whether the whole value is markup the author built: an HTML literal or a trimmed one,
         * `interpolate(...)` or `Trusted(...)`, kotlinx.html output, a value or function declared `Html`
         * or marked `@Language("HTML")`, a function whose every return is markup, `joinToString` over
         * markup, an `if`, a `when` or a `?:` whose every branch is, or a `+` of markup and literals.
         */

        fun isMarkup(
            expr: KtExpression?,
            depth: Int,
        ): Boolean {
            val e = expr?.let { KtPsiUtil.safeDeparenthesize(it) } ?: return false
            return when (e) {
                is KtStringTemplateExpression -> {
                    opensWithTag(e.text.trimStart('$', '"'), trimMargin = marginTrimmed(e))
                }

                // Every branch markup or a literal, and one at least markup: `if (b) "<b>x</b>" else ""`.
                is KtIfExpression -> {
                    branchesMarkup(listOf(e.then, e.`else`), depth)
                }

                is KtWhenExpression -> {
                    e.entries.isNotEmpty() && branchesMarkup(e.entries.map { it.expression }, depth)
                }

                is KtBinaryExpression -> {
                    when (e.operationToken) {
                        // `"<p>" + body + "</p>"`: markup with literals around it; a literal's own placeholders are judged apart.
                        KtTokens.PLUS -> {
                            val parts = ArrayList<KtExpression>().also { flatten(e, it) }
                            val markup = parts.map { isMarkup(it, depth) }
                            markup.any { it } && parts.indices.all { markup[it] || parts[it] is KtStringTemplateExpression }
                        }

                        KtTokens.ELVIS -> {
                            branchesMarkup(listOf(e.left, e.right), depth)
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

                // The html of a Trusted; a property of that name elsewhere is read by its declaration.
                name == "html" && call == null && isMarkup(receiver, depth) -> true

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
            markupElements(receiver) ||
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
                        map?.calleeExpression?.text == "map" &&
                            lambdaMarkup(map.lambdaArguments.firstOrNull()?.getLambdaExpression(), depth)
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
            if (target is KtCallableDeclaration && target.typeReference?.let(markupType) == true) return true
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
