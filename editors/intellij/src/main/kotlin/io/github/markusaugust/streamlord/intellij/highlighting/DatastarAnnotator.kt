package io.github.markusaugust.streamlord.intellij.highlighting

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlAttributeValue
import io.github.markusaugust.streamlord.analysis.datastarName
import io.github.markusaugust.streamlord.analysis.templatePlaceholders
import io.github.markusaugust.streamlord.analysis.tokenize
import io.github.markusaugust.streamlord.intellij.analysis.StreamlordAnalysis
import io.github.markusaugust.streamlord.intellij.analysis.templateEngine
import org.jetbrains.kotlin.psi.KtStringTemplateExpression

/**
 * Colours for Datastar in HTML: the parts of a Datastar attribute's name, one of the catalog's,
 * and the expression in its value. The page's own `data-color`, or a typo, is left to HTML. Runs on HTML files, on the HTML view of template files, and on HTML injected anywhere
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
        val name = analysis.analyzer.datastarName(element.name, analysis.prefix) ?: return
        val nameStart = element.nameElement.textRange.startOffset
        for (p in name.parts) paint(holder, TextRange(nameStart + p.start, nameStart + p.end), DatastarColors.of(p.part))
        val valueElement: XmlAttributeValue = element.valueElement ?: return
        val start = valueElement.valueTextRange.startOffset
        val value = valueElement.value
        for (t in name.valueTokens(value, templatePlaceholders(value, templateEngine(file)))) {
            paint(holder, TextRange(start + t.start, start + t.end), DatastarColors.of(t.kind))
        }
    }
}

/** Colours for what the analysis names Datastar's in a Kotlin string, at the offsets it gives: see `datastarStrings`. */
class KotlinDatastarAnnotator : Annotator {
    override fun annotate(
        element: PsiElement,
        holder: AnnotationHolder,
    ) {
        if (element !is KtStringTemplateExpression) return
        val string = StreamlordAnalysis.getInstance(element.project).datastarString(element) ?: return
        for (p in string.parts) paint(holder, TextRange(p.start, p.end), DatastarColors.of(p.part))
        for (t in string.tokens) paint(holder, TextRange(t.start, t.end), DatastarColors.of(t.kind))
    }
}

/**
 * Colours for Datastar in a template file read as text (see [StreamlordAnalysis.isTemplateText]), or coloured by a
 * TextMate bundle and no more, as `.hbs` is: the same parts of a
 * `data-*` attribute and the same expression tokens, found by the analysis's own tokenizer.
 */
class TemplateTextAnnotator : Annotator {
    override fun annotate(
        element: PsiElement,
        holder: AnnotationHolder,
    ) {
        // A plain text file is read at its one PsiPlainText; a file a bundled TextMate grammar colours (Handlebars' .hbs) is
        // visited as the file alone.
        val file = element.containingFile ?: return
        if (element !is com.intellij.psi.PsiPlainText && !(element === file && file.language.id == "textmate")) return
        if (!StreamlordAnalysis.isTemplateText(file)) return
        val analysis = StreamlordAnalysis.getInstance(file.project)
        val text = element.text
        val base = element.textRange.startOffset
        for (tag in tokenize(text).tags) {
            for (attr in tag.attributes) {
                // The page's own data-color, or a typo, is left alone, as in HTML.
                val name = analysis.analyzer.datastarName(attr.name, analysis.prefix) ?: continue
                for (p in name.parts) {
                    paint(holder, TextRange(base + attr.nameStart + p.start, base + attr.nameStart + p.end), DatastarColors.of(p.part))
                }
                val value = attr.value ?: continue
                for (t in name.valueTokens(value, templatePlaceholders(value, templateEngine(file)))) {
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
