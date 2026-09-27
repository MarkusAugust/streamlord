package io.github.markusaugust.streamlord.intellij.highlighting

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlAttributeValue
import io.github.markusaugust.streamlord.analysis.KotlinString
import io.github.markusaugust.streamlord.analysis.TEMPLATE_SYNTAX
import io.github.markusaugust.streamlord.analysis.ValueKind
import io.github.markusaugust.streamlord.analysis.findCallSites
import io.github.markusaugust.streamlord.analysis.tokenize
import io.github.markusaugust.streamlord.analysis.tokenizeAttributeName
import io.github.markusaugust.streamlord.analysis.tokenizeExpression
import io.github.markusaugust.streamlord.intellij.analysis.StreamlordAnalysis
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtStringTemplateExpression

/**
 * Colours for Datastar in HTML: the parts of a `data-*` attribute name and the expression in its
 * value. Runs on HTML files, on the HTML view of template files, and on HTML injected anywhere
 * but into a Kotlin string, where [KotlinDatastarAnnotator] paints with the exact offsets.
 */
class HtmlDatastarAnnotator : Annotator {
    override fun annotate(
        element: PsiElement,
        holder: AnnotationHolder,
    ) {
        if (element !is XmlAttribute) return
        val file = element.containingFile ?: return
        if (!StreamlordAnalysis.isHtmlSide(file)) return
        val analysis = StreamlordAnalysis.getInstance(file.project)
        val prefix = analysis.prefix
        val name = element.name
        val parts = tokenizeAttributeName(name, prefix) ?: return
        val nameStart = element.nameElement.textRange.startOffset
        for (p in parts) paint(holder, TextRange(nameStart + p.start, nameStart + p.end), DatastarColors.of(p.part))
        val spec = analysis.catalog.parseAttributeName(name.lowercase(), prefix)?.spec ?: return
        if (spec.valueKind != ValueKind.EXPRESSION) return
        val valueElement: XmlAttributeValue = element.valueElement ?: return
        val range = valueElement.valueTextRange
        val value = valueElement.value
        if (value.isBlank() || TEMPLATE_SYNTAX.containsMatchIn(value)) return
        for (t in tokenizeExpression(
            value,
        )) {
            paint(holder, TextRange(range.startOffset + t.start, range.startOffset + t.end), DatastarColors.of(t.kind))
        }
    }
}

/**
 * Colours for Datastar in Kotlin strings: the expression handed to a DSL call (`dataText("$count")`),
 * and inside an HTML string, whether handed to `patchElements` or free-standing, the `data-*`
 * attribute names and their expressions.
 */
class KotlinDatastarAnnotator : Annotator {
    override fun annotate(
        element: PsiElement,
        holder: AnnotationHolder,
    ) {
        if (element !is KtStringTemplateExpression) return
        val file = element.containingFile as? KtFile ?: return
        val analysis = StreamlordAnalysis.getInstance(file.project)
        val analyzer = analysis.analyzer
        val src = file.text
        val start = element.textRange.startOffset
        val lex = analyzer.lex(src)
        val s = lex.strings.firstOrNull { it.start == start || (it.start < start && it.end == element.textRange.endOffset) } ?: return
        val html = analyzer.htmlStringAt(src, s.contentStart + 1)
        if (html != null && html.start == s.start) {
            paintHtml(holder, src, s, analysis)
            return
        }
        val site =
            findCallSites(src, analyzer.catalog.expressionCallSites.keys, lex).firstOrNull {
                it.openParen < s.start &&
                    s.end <= it.closeParen
            }
                ?: return
        val spec = analyzer.catalog.expressionCallSites[site.name] ?: return
        val chosen = site.selectString(spec) ?: return
        if (chosen.start != s.start) return
        paintExpression(holder, src, s, 0, s.text.length)
    }

    private fun paintHtml(
        holder: AnnotationHolder,
        src: String,
        s: KotlinString,
        analysis: StreamlordAnalysis,
    ) {
        val prefix = analysis.prefix
        for (tag in tokenize(s.text).tags) {
            for (attr in tag.attributes) {
                val parts = tokenizeAttributeName(attr.name, prefix) ?: continue
                for (p in parts) {
                    val r = s.toSource(attr.nameStart + p.start, attr.nameStart + p.end)
                    paint(holder, TextRange(r.first, r.last + 1), DatastarColors.of(p.part))
                }
                val spec = analysis.catalog.parseAttributeName(attr.name.lowercase(), prefix)?.spec ?: continue
                val value = attr.value ?: continue
                if (spec.valueKind != ValueKind.EXPRESSION || value.isBlank()) continue
                paintExpression(holder, src, s, attr.valueStart, attr.valueStart + value.length)
            }
        }
    }

    /** Paint the expression in the decoded range [from, to) of the string, mapping each token back to the source. */
    private fun paintExpression(
        holder: AnnotationHolder,
        src: String,
        s: KotlinString,
        from: Int,
        to: Int,
    ) {
        // Tokenize the source text of the range, not the decoded text, so `${'$'}count` is seen as written.
        val srcRange = s.toSource(from, to)
        val sourceStart = srcRange.first
        val sourceEnd = if (to >= s.text.length) s.contentEnd else srcRange.last + 1
        if (sourceEnd <= sourceStart) return
        val text = src.substring(sourceStart, sourceEnd)
        for (t in tokenizeExpression(text)) paint(holder, TextRange(sourceStart + t.start, sourceStart + t.end), DatastarColors.of(t.kind))
    }
}

/**
 * Colours for Datastar in a template file read as text (see [StreamlordAnalysis.isTemplateText]): the same parts of a
 * `data-*` attribute and the same expression tokens, found by the analysis's own tokenizer.
 */
class TemplateTextAnnotator : Annotator {
    override fun annotate(
        element: PsiElement,
        holder: AnnotationHolder,
    ) {
        if (element !is com.intellij.psi.PsiPlainText) return
        val file = element.containingFile ?: return
        if (!StreamlordAnalysis.isTemplateText(file)) return
        val analysis = StreamlordAnalysis.getInstance(file.project)
        val prefix = analysis.prefix
        val text = element.text
        val base = element.textRange.startOffset
        for (tag in tokenize(text).tags) {
            for (attr in tag.attributes) {
                val parts = tokenizeAttributeName(attr.name, prefix) ?: continue
                for (p in parts) {
                    paint(
                        holder,
                        TextRange(base + attr.nameStart + p.start, base + attr.nameStart + p.end),
                        DatastarColors.of(p.part),
                    )
                }
                val spec = analysis.catalog.parseAttributeName(attr.name.lowercase(), prefix)?.spec ?: continue
                val value = attr.value ?: continue
                if (spec.valueKind != ValueKind.EXPRESSION || value.isBlank() || TEMPLATE_SYNTAX.containsMatchIn(value)) continue
                for (t in tokenizeExpression(value)) {
                    paint(holder, TextRange(base + attr.valueStart + t.start, base + attr.valueStart + t.end), DatastarColors.of(t.kind))
                }
            }
        }
    }
}

private fun paint(
    holder: AnnotationHolder,
    range: TextRange,
    key: com.intellij.openapi.editor.colors.TextAttributesKey,
) {
    if (range.isEmpty) return
    holder
        .newSilentAnnotation(HighlightSeverity.INFORMATION)
        .range(range)
        .textAttributes(key)
        .create()
}
