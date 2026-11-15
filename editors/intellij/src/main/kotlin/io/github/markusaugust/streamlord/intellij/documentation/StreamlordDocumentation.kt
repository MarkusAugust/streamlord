package io.github.markusaugust.streamlord.intellij.documentation

import com.intellij.psi.PsiFile
import io.github.markusaugust.streamlord.analysis.hover
import io.github.markusaugust.streamlord.analysis.markdownToHtml
import io.github.markusaugust.streamlord.intellij.analysis.StreamlordAnalysis
import io.github.markusaugust.streamlord.intellij.completion.DatastarCaret

/** What the analysis says of the word at a place in a file: an attribute, an action, an option or a function of the DSL. */
object StreamlordDocumentation {
    /** The documentation for the word at [offset] of [file]: its HTML and the word, or null. */
    fun at(
        file: PsiFile,
        offset: Int,
    ): Pair<String, String>? {
        val asked = DatastarCaret.at(file, offset) ?: return null
        val found = StreamlordAnalysis.getInstance(file.project).analyzer.hover(asked.source, asked.offset) ?: return null
        return markdownToHtml(found.markdown) to asked.source.text.substring(found.start, found.end)
    }
}
