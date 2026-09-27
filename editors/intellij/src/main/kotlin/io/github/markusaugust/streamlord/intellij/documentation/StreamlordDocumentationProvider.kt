package io.github.markusaugust.streamlord.intellij.documentation

import com.intellij.lang.documentation.AbstractDocumentationProvider
import com.intellij.lang.html.HTMLLanguage
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.impl.FakePsiElement
import io.github.markusaugust.streamlord.analysis.actionDoc
import io.github.markusaugust.streamlord.analysis.attributeDoc
import io.github.markusaugust.streamlord.analysis.markdownToHtml
import io.github.markusaugust.streamlord.intellij.analysis.StreamlordAnalysis
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.psi.KtFile

/**
 * Hover documentation for Datastar attributes in HTML (files and strings in Kotlin), for the
 * DSL functions of Streamlord and for actions inside strings. The Kotlin plugin's own KDoc is
 * left in charge of a call it can resolve; this provider speaks where nothing else does.
 */
class StreamlordDocumentationProvider : AbstractDocumentationProvider() {
    override fun getCustomDocumentationElement(
        editor: com.intellij.openapi.editor.Editor,
        file: com.intellij.psi.PsiFile,
        contextElement: PsiElement?,
        targetOffset: Int,
    ): PsiElement? {
        // A DSL call the Kotlin plugin can resolve has KDoc of its own, which says more; this provider speaks where it cannot.
        if (file is KtFile && contextElement != null && resolves(contextElement)) return null
        val doc = documentationAt(file, targetOffset) ?: return null
        return DocTarget(file, doc.first, doc.second)
    }

    override fun generateDoc(
        element: PsiElement?,
        originalElement: PsiElement?,
    ): String? = (element as? DocTarget)?.html

    private fun resolves(element: PsiElement): Boolean {
        val ref = element.parent as? org.jetbrains.kotlin.psi.KtNameReferenceExpression ?: return false
        return try {
            ref.mainReference.resolve() != null
        } catch (_: com.intellij.openapi.project.IndexNotReadyException) {
            false
        }
    }

    /** The documentation for the word at [offset]: its Markdown and the word, or null. */
    fun documentationAt(
        file: com.intellij.psi.PsiFile,
        offset: Int,
    ): Pair<String, String>? {
        val analysis = StreamlordAnalysis.getInstance(file.project)
        val catalog = analysis.catalog
        val prefix = analysis.prefix
        val text = file.text
        if (offset < 0 || offset > text.length) return null
        // An action, `@post`, anywhere in a string or attribute value.
        wordAt(text, offset) { it == '@' || it.isLetterOrDigit() || it == '_' }?.let { (word, _) ->
            if (word.startsWith("@")) {
                val a = catalog.actionsByName[word.substring(1)] ?: return@let
                return markdownToHtml(actionDoc(a)) to word
            }
        }
        val htmlSide = file !is KtFile || analysis.analyzer.htmlStringAt(text, offset) != null
        if (htmlSide) {
            val (word, _) = wordAt(text, offset) { it.isLetterOrDigit() || it in "_:.-" } ?: return null
            val parsed = catalog.parseAttributeName(word.lowercase(), prefix)
            val spec = parsed?.spec ?: return null
            return markdownToHtml(attributeDoc(spec, prefix)) to word
        }
        val (word, end) = wordAt(text, offset) { it.isLetterOrDigit() || it == '_' } ?: return null
        catalog.attributesByKotlin[word]?.let { return markdownToHtml(attributeDoc(it, prefix)) to word }
        val action = catalog.actions.firstOrNull { it.kotlin == word } ?: return null
        if (text.getOrNull(end) != '(') return null
        return markdownToHtml(actionDoc(action, withKotlin = false)) to word
    }

    private fun wordAt(
        text: String,
        offset: Int,
        part: (Char) -> Boolean,
    ): Pair<String, Int>? {
        var start = offset
        while (start > 0 && part(text[start - 1])) start--
        var end = offset
        while (end < text.length && part(text[end])) end++
        if (end <= start) return null
        return text.substring(start, end) to end
    }

    /** A stand-in element carrying the documentation, so the platform has something to show it for. */
    class DocTarget(
        private val file: com.intellij.psi.PsiFile,
        val html: String,
        private val word: String,
    ) : FakePsiElement() {
        override fun getParent(): PsiElement = file

        override fun getName(): String = word

        override fun getManager(): PsiManager = file.manager

        override fun isValid(): Boolean = file.isValid

        override fun getText(): String = word
    }

    companion object {
        fun applies(file: com.intellij.psi.PsiFile): Boolean = file is KtFile || file.language.isKindOf(HTMLLanguage.INSTANCE)
    }
}
