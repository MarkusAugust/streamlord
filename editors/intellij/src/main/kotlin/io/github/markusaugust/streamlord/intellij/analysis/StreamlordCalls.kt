package io.github.markusaugust.streamlord.intellij.analysis

import com.intellij.psi.PsiElement
import io.github.markusaugust.streamlord.analysis.KotlinString
import io.github.markusaugust.streamlord.analysis.isHtmlString

/**
 * Which of the calls the analysis knows by name are Streamlord's in a Kotlin file, as its module
 * says, for completion, hover and colouring, which find a call by its name alone. Without core
 * none is; without streamlord-html none of the DSL's is. While the IDE indexes, all are.
 */
class StreamlordCalls private constructor(
    val core: Boolean,
    val dsl: Boolean,
) {
    /** The names of the calls the analysis reads Datastar in that are Streamlord's here. */
    fun siteNames(analysis: StreamlordAnalysis): Set<String> =
        when {
            !core -> emptySet()
            !dsl -> analysis.analyzer.callSiteNames - analysis.analyzer.dslNames
            else -> analysis.analyzer.callSiteNames
        }

    /** The HTML string at [offset]: a literal that looks like HTML, or the markup handed to a call of Streamlord's. */
    fun htmlStringAt(
        analysis: StreamlordAnalysis,
        src: String,
        offset: Int,
    ): KotlinString? {
        val s = analysis.analyzer.htmlStringAt(src, offset) ?: return null
        return if (core || isHtmlString(src, s)) s else null
    }

    companion object {
        private val ALL = StreamlordCalls(core = true, dsl = true)

        fun of(element: PsiElement): StreamlordCalls {
            val core = ProjectLibraries.known(element, StreamlordLibrary.CORE) != false
            val dsl = core && ProjectLibraries.known(element, StreamlordLibrary.HTML) != false
            return if (core && dsl) ALL else StreamlordCalls(core, dsl)
        }
    }
}
