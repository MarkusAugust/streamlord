package io.github.markusaugust.streamlord.intellij.completion

import com.intellij.codeInsight.completion.CompletionConfidence
import com.intellij.lang.html.HTMLLanguage
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.util.ThreeState
import io.github.markusaugust.streamlord.analysis.findCallSites
import io.github.markusaugust.streamlord.intellij.analysis.StreamlordAnalysis
import org.jetbrains.kotlin.psi.KtFile

/**
 * The platform holds the automatic completion popup back inside string literals. A string that
 * is a Datastar expression, a selector or a `data-*` value is where the signals and actions
 * live, so there the popup is let through; everywhere else the usual rule stands.
 */
class DatastarCompletionConfidence : CompletionConfidence() {
    override fun shouldSkipAutopopup(
        editor: com.intellij.openapi.editor.Editor,
        contextElement: PsiElement,
        psiFile: PsiFile,
        offset: Int,
    ): ThreeState {
        val analysis = StreamlordAnalysis.getInstance(psiFile.project)
        val src = psiFile.text
        return when {
            psiFile is KtFile -> {
                val lex = analysis.analyzer.lex(src)
                val s = lex.stringAt(offset) ?: return ThreeState.UNSURE
                if (analysis.analyzer.htmlStringAt(src, offset) != null) return ThreeState.NO
                val inSite =
                    findCallSites(src, analysis.analyzer.callSiteNames, lex).any {
                        it.openParen < s.start &&
                            s.end <= it.closeParen + 1
                    }
                if (inSite) ThreeState.NO else ThreeState.UNSURE
            }

            psiFile.language.isKindOf(HTMLLanguage.INSTANCE) || StreamlordAnalysis.isTemplateText(psiFile) -> {
                val lineStart = src.lastIndexOf('\n', maxOf(0, offset - 1)) + 1
                val before = src.substring(maxOf(lineStart, offset - 400), offset.coerceIn(0, src.length))
                if (IN_DATA_VALUE.containsMatchIn(before)) ThreeState.NO else ThreeState.UNSURE
            }

            else -> {
                ThreeState.UNSURE
            }
        }
    }

    private companion object {
        val IN_DATA_VALUE = Regex("""data-[^\s"'<>=]*\s*=\s*"[^"]*$""")
    }
}
