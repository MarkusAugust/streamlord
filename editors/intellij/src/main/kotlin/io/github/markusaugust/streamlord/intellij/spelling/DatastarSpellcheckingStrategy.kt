package io.github.markusaugust.streamlord.intellij.spelling

import com.intellij.lang.html.HTMLLanguage
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.spellchecker.inspections.PlainTextSplitter
import com.intellij.spellchecker.tokenizer.SpellcheckingStrategy
import com.intellij.spellchecker.tokenizer.TokenConsumer
import com.intellij.spellchecker.tokenizer.Tokenizer
import io.github.markusaugust.streamlord.analysis.ValueKind
import io.github.markusaugust.streamlord.analysis.findCallSites
import io.github.markusaugust.streamlord.analysis.proseRanges
import io.github.markusaugust.streamlord.intellij.analysis.StreamlordAnalysis
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtStringTemplateExpression

/**
 * Spelling in a Datastar expression: only its strings are prose. A signal, an action or a name in
 * code is no word, so the value of a Datastar attribute and the string handed to a DSL call are
 * tokenized here, strings only, and every element inside them is left to that. Grazie's text-level
 * speller, which would read the whole value, is off there: [useTextLevelSpellchecking] keeps its
 * default, false.
 */
class DatastarSpellcheckingStrategy :
    SpellcheckingStrategy(),
    DumbAware {
    override fun isMyContext(element: PsiElement): Boolean = owner(element) != null

    override fun isLiteral(element: PsiElement): Boolean = true

    override fun getTokenizer(element: PsiElement): Tokenizer<*> = if (owner(element) === element) Prose else EMPTY_TOKENIZER

    /** The expression [element] is or lies in: a Datastar attribute value, or the string of a DSL expression call. */
    private fun owner(element: PsiElement): PsiElement? {
        PsiTreeUtil.getParentOfType(element, XmlAttributeValue::class.java, false)?.let { value ->
            if (!value.containingFile.language.isKindOf(HTMLLanguage.INSTANCE)) return null
            val attribute = value.parent as? XmlAttribute ?: return null
            val analysis = StreamlordAnalysis.getInstance(element.project)
            val spec = analysis.catalog.parseAttributeName(attribute.name.lowercase(), analysis.prefix)?.spec ?: return null
            return value.takeIf { spec.valueKind == ValueKind.EXPRESSION || spec.valueKind == ValueKind.SIGNAL }
        }
        val string = PsiTreeUtil.getParentOfType(element, KtStringTemplateExpression::class.java, false) ?: return null
        return string.takeIf { isExpressionString(it) }
    }

    /** Whether [string] is the string a DSL expression call hands Datastar, `dataOnClick("...")`. */
    private fun isExpressionString(string: KtStringTemplateExpression): Boolean {
        val file = string.containingFile as? KtFile ?: return false
        val analysis = StreamlordAnalysis.getInstance(file.project)
        val src = file.text
        val start = string.textRange.startOffset
        val sites = findCallSites(src, analysis.catalog.expressionCallSites.keys, analysis.analyzer.lex(src))
        return sites.any { site -> analysis.catalog.expressionCallSites[site.name]?.let { site.selectString(it)?.start == start } == true }
    }

    /** The strings of an expression, read from its text as written: a signal value's text is a name and has none. */
    private object Prose : Tokenizer<PsiElement>() {
        override fun tokenize(
            element: PsiElement,
            consumer: TokenConsumer,
        ) {
            if (element is XmlAttributeValue) {
                val attribute = element.parent as? XmlAttribute ?: return
                val analysis = StreamlordAnalysis.getInstance(element.project)
                val spec = analysis.catalog.parseAttributeName(attribute.name.lowercase(), analysis.prefix)?.spec ?: return
                if (spec.valueKind != ValueKind.EXPRESSION) return
            }
            val text = element.text
            val content = content(element, text)
            for (range in proseRanges(text.substring(content.startOffset, content.endOffset))) {
                val within = TextRange(content.startOffset + range.first, content.startOffset + range.last + 1)
                consumer.consumeToken(element, text, false, 0, within, PlainTextSplitter.getInstance())
            }
        }

        /** The expression inside the quotes of [element], whose [text] is given: an attribute value's or a Kotlin literal's. */
        private fun content(
            element: PsiElement,
            text: String,
        ): TextRange {
            if (element is XmlAttributeValue) return element.valueTextRange.shiftLeft(element.textRange.startOffset)
            // `"..."`, `"""..."""`, with any `$$` prefix of a multi-dollar literal.
            val open = text.indexOf('"').let { q -> if (text.startsWith("\"\"\"", q)) q + 3 else q + 1 }
            val close = if (text.endsWith("\"\"\"") && text.length - 3 >= open) text.length - 3 else maxOf(open, text.length - 1)
            return TextRange(open, maxOf(open, close))
        }
    }
}
