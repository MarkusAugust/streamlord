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
import io.github.markusaugust.streamlord.analysis.DatastarString
import io.github.markusaugust.streamlord.analysis.datastarName
import io.github.markusaugust.streamlord.intellij.analysis.StreamlordAnalysis
import org.jetbrains.kotlin.psi.KtDeclaration
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

    /**
     * The value [element] is or lies in that is Datastar's and no prose as a whole: the value of a Datastar attribute that takes
     * an expression or a signal's name, or a Kotlin string the analysis names as either.
     */
    private fun owner(element: PsiElement): PsiElement? {
        if (element.containingFile is KtFile) {
            // A string in a Kotlin file, not one in its injected HTML, which is a file of its own.
            val string = PsiTreeUtil.getParentOfType(element, KtStringTemplateExpression::class.java, false, KtDeclaration::class.java)
            return string?.takeIf {
                val found = StreamlordAnalysis.getInstance(it.project).datastarString(it)
                found is DatastarString.Expression || found is DatastarString.SignalName
            }
        }
        PsiTreeUtil.getParentOfType(element, XmlAttributeValue::class.java, false)?.let { value ->
            if (!value.containingFile.language.isKindOf(HTMLLanguage.INSTANCE)) return null
            return value.takeIf { prose(it) != null }
        }
        return null
    }

    /** The strings of an expression: a signal's name has none. */
    private object Prose : Tokenizer<PsiElement>() {
        override fun tokenize(
            element: PsiElement,
            consumer: TokenConsumer,
        ) {
            val text = element.text
            val ranges =
                if (element is KtStringTemplateExpression) {
                    // The analysis has left Kotlin's templates and escapes out, and counts from the start of the file.
                    val found = StreamlordAnalysis.getInstance(element.project).datastarString(element) as? DatastarString.Expression
                    val base = element.textRange.startOffset
                    found?.prose.orEmpty().map { TextRange(it.first - base, it.last + 1 - base) }
                } else {
                    val value = element as? XmlAttributeValue ?: return
                    val start = value.valueTextRange.startOffset - value.textRange.startOffset
                    prose(value).orEmpty().map { TextRange(start + it.first, start + it.last + 1) }
                }
            for (range in ranges) consumer.consumeToken(element, text, false, 0, range, PlainTextSplitter.getInstance())
        }
    }
}

/** What is spelled of [value] where its attribute is Datastar's and reads the value itself, as offsets into the value's text. */
private fun prose(value: XmlAttributeValue): List<IntRange>? {
    val attribute = value.parent as? XmlAttribute ?: return null
    val analysis = StreamlordAnalysis.getInstance(value.project)
    val written = value.valueTextRange.shiftLeft(value.textRange.startOffset).substring(value.text)
    return analysis.analyzer.datastarName(attribute.name, analysis.prefix)?.prose(written)
}
