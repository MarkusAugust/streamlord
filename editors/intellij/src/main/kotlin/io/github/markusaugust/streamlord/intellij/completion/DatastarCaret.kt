package io.github.markusaugust.streamlord.intellij.completion

import com.intellij.lang.html.HTMLLanguage
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.psi.PsiFile
import io.github.markusaugust.streamlord.analysis.Source
import io.github.markusaugust.streamlord.analysis.SourceLanguage
import io.github.markusaugust.streamlord.intellij.analysis.StreamlordAnalysis
import org.jetbrains.kotlin.psi.KtFile

/** What completion, hover and parameter info hand the analysis: the [file] whose text [source] is, and the caret in it. */
class Asked(
    val file: PsiFile,
    val source: Source,
    val offset: Int,
)

/**
 * The question a caret in any file the plugin reads puts to the analysis: the text it reads, the
 * file's own for markup and Kotlin, the host's for HTML injected into a Kotlin string, with what
 * the project's libraries and the Kotlin plugin know of the file.
 */
object DatastarCaret {
    fun at(
        file: PsiFile,
        offset: Int,
    ): Asked? {
        val analysis = StreamlordAnalysis.getInstance(file.project)
        if (file is KtFile) return inKotlin(analysis, file, offset)
        // HTML injected into a Kotlin string is read on the Kotlin side, where the string is decoded: the
        // injected text keeps Kotlin's escapes and leaves out what a template writes.
        val manager = InjectedLanguageManager.getInstance(file.project)
        val host = if (manager.isInjectedFragment(file)) manager.getInjectionHost(file)?.containingFile else null
        if (host is KtFile) return inKotlin(analysis, host, manager.injectedToHost(file, offset))
        // Markup: an HTML file, a template read as text, or a template whose plugin gives it an HTML view beside its own
        // language (FreeMarker, Thymeleaf), where parameter info and hover are handed the template's file and not the view.
        val markup =
            file.language.isKindOf(HTMLLanguage.INSTANCE) ||
                StreamlordAnalysis.isTemplateText(file) ||
                file.viewProvider.allFiles.any { it.language.isKindOf(HTMLLanguage.INSTANCE) }
        if (!markup) return null
        return Asked(file, analysis.source(file.text, SourceLanguage.HTML, file), offset)
    }

    private fun inKotlin(
        analysis: StreamlordAnalysis,
        file: KtFile,
        offset: Int,
    ): Asked {
        val foreign = { name: String, at: Int -> analysis.isForeign(file, name, at) }
        val source = analysis.source(file.text, SourceLanguage.KOTLIN, file).copy(foreign = foreign)
        return Asked(file, source, offset)
    }
}
