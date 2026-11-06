package io.github.markusaugust.streamlord.intellij.documentation

import com.intellij.lang.documentation.AbstractDocumentationProvider
import com.intellij.lang.html.HTMLLanguage
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.impl.FakePsiElement
import com.intellij.psi.util.PsiTreeUtil
import io.github.markusaugust.streamlord.analysis.actionCallAt
import io.github.markusaugust.streamlord.analysis.actionDoc
import io.github.markusaugust.streamlord.analysis.attributeDoc
import io.github.markusaugust.streamlord.analysis.fetchOptionDoc
import io.github.markusaugust.streamlord.analysis.markdownToHtml
import io.github.markusaugust.streamlord.intellij.analysis.ProjectLibraries
import io.github.markusaugust.streamlord.intellij.analysis.StreamlordAnalysis
import io.github.markusaugust.streamlord.intellij.analysis.StreamlordCalls
import io.github.markusaugust.streamlord.intellij.analysis.StreamlordLibrary
import io.github.markusaugust.streamlord.intellij.completion.DatastarCaret
import io.github.markusaugust.streamlord.intellij.completion.LookupDoc
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.KtValueArgument

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

    /** An option or an event in the completion list, which carries its documentation. */
    override fun getDocumentationElementForLookupItem(
        psiManager: PsiManager,
        `object`: Any?,
        element: PsiElement?,
    ): PsiElement? {
        val doc = `object` as? LookupDoc ?: return null
        val file = element?.containingFile ?: return null
        return DocTarget(file, doc.html, doc.name)
    }

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
        // The DSL's names are for a project that has it; while the IDE indexes it may.
        val dsl = ProjectLibraries.known(file, StreamlordLibrary.HTML) != false
        val calls = (file as? KtFile)?.let { StreamlordCalls.of(it) }
        // An action, `@post`, anywhere in a string or attribute value, `attributes["data-on:click"]` among them;
        // not in the string of a call named as the DSL's that is somebody else's, as every such call is without the DSL.
        wordAt(text, offset) { it == '@' || it.isLetterOrDigit() || it == '_' }?.let { (word, _) ->
            if (word.startsWith("@")) {
                val a = catalog.actionsByName[word.substring(1)] ?: return@let
                if (file is KtFile && inCallNamed(file, offset, analysis.analyzer.dslNames)) {
                    if (calls?.dsl == false || analysis.isForeignCallAt(file, offset, analysis.analyzer.dslNames)) return@let
                }
                return markdownToHtml(actionDoc(a, withKotlin = dsl)) to word
            }
        }
        // A key of a backend action's options object, read as the key it is with the caret at its end.
        wordAt(text, offset) { it.isLetterOrDigit() || it == '_' || it == '$' }?.let { (word, end) ->
            val option = catalog.fetchOptions.firstOrNull { it.name == word } ?: return@let
            val expression = DatastarCaret.expressionAt(file, end) ?: return@let
            if (expression.allowActions && actionCallAt(expression.before, expression.before.length)?.option?.typed == word) {
                return markdownToHtml(fetchOptionDoc(option)) to word
            }
        }
        // kotlinx.html's own form names the attribute in its key, whatever the project has.
        val key =
            (file as? KtFile)?.let {
                analysis.analyzer.attributesIndexes(text).any { at -> offset in at.nameStart..at.nameStart + at.name.length }
            } == true
        val htmlSide = calls == null || key || calls.htmlStringAt(analysis, text, offset) != null
        if (htmlSide) {
            val (word, _) = wordAt(text, offset) { it.isLetterOrDigit() || it in "_:.-" } ?: return null
            val parsed = catalog.parseAttributeName(word.lowercase(), prefix)
            val spec = parsed?.spec ?: return null
            return markdownToHtml(attributeDoc(spec, prefix, withKotlin = dsl)) to word
        }
        // A DSL function's name is code, never a word in a string.
        if (!dsl || analysis.analyzer.lex(text).stringAt(offset) != null) return null
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

    /** Whether [offset] is in a string handed straight to a call with one of [names]. */
    private fun inCallNamed(
        file: PsiFile,
        offset: Int,
        names: Set<String>,
    ): Boolean {
        val string = PsiTreeUtil.getParentOfType(file.findElementAt(offset), KtStringTemplateExpression::class.java) ?: return false
        val call =
            PsiTreeUtil.getParentOfType(string.parent as? KtValueArgument ?: return false, KtCallExpression::class.java) ?: return false
        return call.calleeExpression?.text in names
    }
}
