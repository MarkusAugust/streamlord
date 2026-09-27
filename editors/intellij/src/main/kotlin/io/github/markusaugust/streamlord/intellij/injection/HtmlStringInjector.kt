package io.github.markusaugust.streamlord.intellij.injection

import com.intellij.lang.html.HTMLLanguage
import com.intellij.lang.injection.MultiHostInjector
import com.intellij.lang.injection.MultiHostRegistrar
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import io.github.markusaugust.streamlord.analysis.isHtmlString
import io.github.markusaugust.streamlord.intellij.analysis.StreamlordAnalysis
import io.github.markusaugust.streamlord.intellij.settings.StreamlordSettings
import org.jetbrains.kotlin.psi.KtEscapeStringTemplateEntry
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtLiteralStringTemplateEntry
import org.jetbrains.kotlin.psi.KtStringTemplateExpression

/**
 * HTML injected into a Kotlin string that opens with a tag but carries no `@Language("HTML")`: a
 * function that returns markup, a `val` with a fragment. The Kotlin plugin already injects the
 * annotated ones (and Streamlord's parameters carry the annotation), so this covers the rest,
 * giving tag highlighting and completion where the author wrote none of the ceremony.
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
        // Strings the Kotlin plugin injects itself: `@Language("HTML")` just before, or handed to a Streamlord call, whose
        // parameters carry the annotation. Two injections would fight; only the marker-less, free-standing ones are ours.
        if (analyzer.htmlStringAt(src, s.contentStart + 1)?.start == s.start && hasMarker(src, s.start)) return
        if (isCallArgument(host)) return
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

    private fun hasMarker(
        src: String,
        start: Int,
    ): Boolean {
        val before = src.substring(maxOf(0, start - 240), start)
        return MARKER.containsMatchIn(before)
    }

    /** A string passed straight to a call: the Kotlin plugin injects there when the parameter is annotated. */
    private fun isCallArgument(host: KtStringTemplateExpression): Boolean {
        var e: PsiElement? = host.parent
        while (e != null &&
            (e is org.jetbrains.kotlin.psi.KtParenthesizedExpression || e is org.jetbrains.kotlin.psi.KtDotQualifiedExpression)
        ) {
            e =
                e.parent
        }
        return e is org.jetbrains.kotlin.psi.KtValueArgument
    }

    private companion object {
        val MARKER = Regex("""@Language\(\s*"html"\s*\)|//\s*language\s*=\s*html\b""", RegexOption.IGNORE_CASE)
    }
}
