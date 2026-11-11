package io.github.markusaugust.streamlord.intellij.documentation

import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.icons.AllIcons
import com.intellij.lang.documentation.DocumentationMarkup
import com.intellij.lang.html.HTMLLanguage
import com.intellij.model.Pointer
import com.intellij.openapi.fileTypes.PlainTextLanguage
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.platform.backend.documentation.DocumentationResult
import com.intellij.platform.backend.documentation.DocumentationTarget
import com.intellij.platform.backend.documentation.DocumentationTargetProvider
import com.intellij.platform.backend.documentation.LookupElementDocumentationTargetProvider
import com.intellij.platform.backend.presentation.TargetPresentation
import com.intellij.psi.PsiFile
import io.github.markusaugust.streamlord.intellij.completion.LookupDoc
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNameReferenceExpression

/** The documentation of one word, as the catalog has it. It holds text alone, so it stays good for as long as it is shown. */
class StreamlordDocumentationTarget(
    private val word: String,
    val html: String,
) : DocumentationTarget {
    override fun createPointer(): Pointer<out DocumentationTarget> = Pointer.hardPointer(this)

    override fun computePresentation(): TargetPresentation =
        TargetPresentation.builder(word).icon(AllIcons.Actions.Lightning).presentation()

    // In the platform's content section, so it is set as the documentation of every other language is.
    override fun computeDocumentation(): DocumentationResult =
        DocumentationResult.documentation(DocumentationMarkup.CONTENT_START + html + DocumentationMarkup.CONTENT_END)
}

/**
 * Quick documentation at a place in a Kotlin file, an HTML file or a template read as text. The
 * Kotlin plugin's own KDoc is left in charge of a call it can resolve; this speaks where nothing
 * else does.
 */
class StreamlordDocumentationTargetProvider : DocumentationTargetProvider {
    override fun documentationTargets(
        file: PsiFile,
        offset: Int,
    ): List<DocumentationTarget> {
        if (file !is KtFile && !file.language.isKindOf(HTMLLanguage.INSTANCE) &&
            file.language != PlainTextLanguage.INSTANCE
        ) {
            return emptyList()
        }
        // A DSL call the Kotlin plugin can resolve has KDoc of its own, which says more.
        if (file is KtFile && resolves(file, offset)) return emptyList()
        val (html, word) = StreamlordDocumentation.at(file, offset) ?: return emptyList()
        return listOf(StreamlordDocumentationTarget(word, html))
    }

    private fun resolves(
        file: KtFile,
        offset: Int,
    ): Boolean {
        val ref = file.findElementAt(offset)?.parent as? KtNameReferenceExpression ?: return false
        return try {
            ref.mainReference.resolve() != null
        } catch (_: IndexNotReadyException) {
            false
        }
    }
}

/** An option or an event in the completion list, which carries its documentation: F1 or Ctrl+Q while the list is open. */
class StreamlordLookupDocumentationTargetProvider : LookupElementDocumentationTargetProvider {
    override fun documentationTarget(
        psiFile: PsiFile,
        element: LookupElement,
        offset: Int,
    ): DocumentationTarget? = (element.`object` as? LookupDoc)?.let { StreamlordDocumentationTarget(it.name, it.html) }
}
