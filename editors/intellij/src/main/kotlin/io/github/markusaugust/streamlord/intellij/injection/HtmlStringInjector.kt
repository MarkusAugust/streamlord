package io.github.markusaugust.streamlord.intellij.injection

import com.intellij.lang.html.HTMLLanguage
import com.intellij.lang.injection.MultiHostInjector
import com.intellij.lang.injection.MultiHostRegistrar
import com.intellij.lang.injection.general.LanguageInjectionContributor
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import io.github.markusaugust.streamlord.analysis.isHtmlString
import io.github.markusaugust.streamlord.intellij.analysis.StreamlordAnalysis
import io.github.markusaugust.streamlord.intellij.settings.StreamlordSettings
import org.jetbrains.kotlin.idea.KotlinLanguage
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtEscapeStringTemplateEntry
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtLiteralStringTemplateEntry
import org.jetbrains.kotlin.psi.KtParenthesizedExpression
import org.jetbrains.kotlin.psi.KtStringTemplateExpression

/**
 * HTML injected into a Kotlin string the analysis reads as HTML and the Kotlin plugin injects nothing into: a
 * function that returns markup, a `val` with a fragment, the block of `interpolate`, an argument of a parameter
 * without the mark. The Kotlin plugin injects the marked ones (and Streamlord's parameters carry the mark), so this
 * covers the rest, giving tag highlighting and completion where the author wrote none of the ceremony.
 *
 * Templates (`$name`, `${expr}`) are left out of the injected text, as the Kotlin plugin does:
 * the fragments around them are joined, and the Kotlin side of the plugin keeps judging the
 * string with its exact offsets.
 */
class HtmlStringInjector : MultiHostInjector {
    override fun elementsToInjectIn(): List<Class<out PsiElement>> = listOf(KtStringTemplateExpression::class.java)

    override fun getLanguagesToInject(
        registrar: MultiHostRegistrar,
        context: PsiElement,
    ) {
        val host = context as? KtStringTemplateExpression ?: return
        val file = host.containingFile as? KtFile ?: return
        if (!StreamlordSettings.getInstance(file.project).state.injectHtml) return
        if (!host.isValidHost) return
        val src = file.text
        val analyzer = StreamlordAnalysis.getInstance(file.project).analyzer
        val s = analyzer.lex(src).strings.firstOrNull { it.start == host.textRange.startOffset } ?: return
        if (s.unterminated || !isHtmlString(src, s)) return
        // A string the Kotlin plugin injects into itself, by a mark on what it is or on the parameter it is handed to,
        // keeps that injection and its prefix: ours would win over it. A sum is injected whole, and asked of as one.
        var whole: PsiElement = host
        while (whole.parent.let { (it is KtBinaryExpression && it.operationToken == KtTokens.PLUS) || it is KtParenthesizedExpression }) {
            whole = whole.parent
        }
        val contributors = LanguageInjectionContributor.INJECTOR_EXTENSION.allForLanguageOrAny(KotlinLanguage.INSTANCE)
        if (contributors.any { it.getInjection(host) != null || (whole !== host && it.getInjection(whole) != null) }) return
        val entries = host.entries
        val ranges = ArrayList<TextRange>()
        var current: TextRange? = null
        for (entry in entries) {
            if (entry is KtLiteralStringTemplateEntry || entry is KtEscapeStringTemplateEntry) {
                val r = entry.textRange.shiftLeft(host.textRange.startOffset)
                current =
                    if (current != null &&
                        current.endOffset == r.startOffset
                    ) {
                        current.union(r)
                    } else {
                        current.also { if (it != null) ranges += it }?.let { r }
                            ?: r
                    }
            } else {
                current?.let { ranges += it }
                current = null
            }
        }
        current?.let { ranges += it }
        if (ranges.isEmpty()) return
        registrar.startInjecting(HTMLLanguage.INSTANCE)
        for (r in ranges) registrar.addPlace(null, null, host, r)
        registrar.doneInjecting()
    }
}
