package io.github.markusaugust.streamlord.intellij.highlighting

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlAttributeValue
import io.github.markusaugust.streamlord.analysis.AttributeSpec
import io.github.markusaugust.streamlord.analysis.AttributesIndex
import io.github.markusaugust.streamlord.analysis.KotlinString
import io.github.markusaugust.streamlord.analysis.NameToken
import io.github.markusaugust.streamlord.analysis.PLACEHOLDER
import io.github.markusaugust.streamlord.analysis.Token
import io.github.markusaugust.streamlord.analysis.ValueKind
import io.github.markusaugust.streamlord.analysis.findCallSites
import io.github.markusaugust.streamlord.analysis.templatePlaceholders
import io.github.markusaugust.streamlord.analysis.tokenize
import io.github.markusaugust.streamlord.analysis.tokenizeAttributeName
import io.github.markusaugust.streamlord.analysis.tokenizeExpression
import io.github.markusaugust.streamlord.analysis.tokenizeSignalName
import io.github.markusaugust.streamlord.intellij.analysis.StreamlordAnalysis
import io.github.markusaugust.streamlord.intellij.analysis.StreamlordCalls
import org.jetbrains.kotlin.psi.KtFile
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
        val (spec, parts) = datastarName(analysis, element.name) ?: return
        val nameStart = element.nameElement.textRange.startOffset
        for (p in parts) paint(holder, TextRange(nameStart + p.start, nameStart + p.end), DatastarColors.of(p.part))
        val valueElement: XmlAttributeValue = element.valueElement ?: return
        val start = valueElement.valueTextRange.startOffset
        for (t in valueTokens(spec, valueElement.value)) paint(holder, TextRange(start + t.start, start + t.end), DatastarColors.of(t.kind))
    }
}

/**
 * Colours for Datastar in Kotlin strings: the expression handed to a DSL call (`dataText("$count")`)
 * where the project has the DSL and the call is Streamlord's, the name and the expression of
 * kotlinx.html's `attributes["data-text"] = "$count"`, and inside an HTML string, whether handed to
 * `patchElements` or free-standing, the `data-*` attribute names and their expressions.
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
        // kotlinx.html's own form is Datastar whatever the project has: the name in the key, the expression in the value.
        for (at in attributesIndexes(file, analysis)) {
            if (s.start == at.value?.start) {
                if (!at.whole) return
                val (spec, _) = datastarName(analysis, at.name) ?: return
                if (spec.valueKind == ValueKind.EXPRESSION && s.text.isNotBlank()) paintExpression(holder, src, s, 0, s.text.length)
                return
            }
            if (s.contentStart == at.nameStart) {
                val (_, parts) = datastarName(analysis, at.name) ?: return
                for (p in parts) paint(holder, TextRange(at.nameStart + p.start, at.nameStart + p.end), DatastarColors.of(p.part))
                return
            }
        }
        val calls = StreamlordCalls.of(file)
        val html = calls.htmlStringAt(analysis, src, s.contentStart + 1)
        if (html != null && html.start == s.start) {
            paintHtml(holder, src, s, analysis)
            return
        }
        // Every call that takes an expression is the DSL's, which is somebody else's in a project without it.
        if (!calls.dsl) return
        val named =
            findCallSites(src, SIGNAL_NAME_CALLS, lex).firstOrNull { it.openParen < s.start && s.end <= it.closeParen }
        if (named != null && named.positional
                .firstOrNull()
                ?.string
                ?.start == s.start
        ) {
            val pair = named.name in PAIR_CALLS
            if ((!pair || named.positional.getOrNull(1)?.string != null) && analysis.foreignAmong(file, listOf(named)).isEmpty()) {
                if (PLACEHOLDER !in s.text) paintDecoded(holder, s, 0, tokenizeSignalName(s.text))
                return
            }
        }
        val site =
            findCallSites(src, analyzer.catalog.expressionCallSites.keys, lex).firstOrNull {
                it.openParen < s.start &&
                    s.end <= it.closeParen
            }
                ?: return
        val spec = analyzer.catalog.expressionCallSites[site.name] ?: return
        val chosen = site.selectString(spec) ?: return
        if (chosen.start != s.start || analysis.foreignAmong(file, listOf(site)).isNotEmpty()) return
        paintExpression(holder, src, s, 0, s.text.length)
    }

    /** The `attributes["data-..."] =` of [file], found once for each version of it: the annotator is called for every string. */
    private fun attributesIndexes(
        file: KtFile,
        analysis: StreamlordAnalysis,
    ): List<AttributesIndex> =
        CachedValuesManager.getCachedValue(file) {
            CachedValueProvider.Result.create(analysis.analyzer.attributesIndexes(file.text), file)
        }

    private fun paintHtml(
        holder: AnnotationHolder,
        src: String,
        s: KotlinString,
        analysis: StreamlordAnalysis,
    ) {
        for (tag in tokenize(s.text, templates = false).tags) {
            for (attr in tag.attributes) {
                val (spec, parts) = datastarName(analysis, attr.name) ?: continue
                for (p in parts) {
                    val r = s.toSource(attr.nameStart + p.start, attr.nameStart + p.end)
                    paint(holder, TextRange(r.first, r.last + 1), DatastarColors.of(p.part))
                }
                val value = attr.value ?: continue
                if (value.isBlank()) continue
                when (spec.valueKind) {
                    ValueKind.EXPRESSION, ValueKind.FILTER -> {
                        paintExpression(
                            holder,
                            src,
                            s,
                            attr.valueStart,
                            attr.valueStart + value.length,
                        )
                    }

                    ValueKind.SIGNAL -> {
                        if (PLACEHOLDER !in value) paintDecoded(holder, s, attr.valueStart, tokenizeSignalName(value))
                    }

                    else -> {}
                }
            }
        }
    }

    /** Paint [tokens] of the decoded text that starts at [from], mapping each back to the source. */
    private fun paintDecoded(
        holder: AnnotationHolder,
        s: KotlinString,
        from: Int,
        tokens: List<Token>,
    ) {
        for (t in tokens) {
            val r = s.toSource(from + t.start, from + t.end)
            paint(holder, TextRange(r.first, r.last + 1), DatastarColors.of(t.kind))
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
        // A Kotlin template is Kotlin's: its own highlighter colours it, and Datastar never sees it as written.
        val templates =
            s.interpolations
                .filter { it.start < sourceEnd && sourceStart < it.end }
                .map { (it.start - sourceStart) until (it.end - sourceStart) }
        for (t in tokenizeExpression(text, templates)) {
            paint(holder, TextRange(sourceStart + t.start, sourceStart + t.end), DatastarColors.of(t.kind))
        }
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
                val (spec, parts) = datastarName(analysis, attr.name) ?: continue
                for (p in parts) {
                    paint(holder, TextRange(base + attr.nameStart + p.start, base + attr.nameStart + p.end), DatastarColors.of(p.part))
                }
                val value = attr.value ?: continue
                for (t in valueTokens(spec, value)) {
                    paint(holder, TextRange(base + attr.valueStart + t.start, base + attr.valueStart + t.end), DatastarColors.of(t.kind))
                }
            }
        }
    }
}

/** The DSL's calls whose first argument names a signal: alone, or as the key of a pair with an expression. */
private val SIGNAL_NAME_CALLS = setOf("dataBind", "dataRef", "dataIndicator", "dataSignals", "dataComputed")
private val PAIR_CALLS = setOf("dataSignals", "dataComputed")

/**
 * The spec and the coloured parts of [name] when it is one of the catalog's attributes, under the
 * project's prefix or the other bundle's: the VS Code grammar knows both, and so the colours do.
 */
private fun datastarName(
    analysis: StreamlordAnalysis,
    name: String,
): Pair<AttributeSpec, List<NameToken>>? {
    for (prefix in listOf(analysis.prefix, "data-", "data-star-").distinct()) {
        val spec = analysis.catalog.parseAttributeName(name.lowercase(), prefix)?.spec ?: continue
        val parts = tokenizeAttributeName(name, prefix) ?: continue
        return spec to parts
    }
    return null
}

/** The tokens of a value as written in markup, around a template engine's placeholders. */
private fun valueTokens(
    spec: AttributeSpec,
    value: String,
): List<Token> {
    if (value.isBlank()) return emptyList()
    return when (spec.valueKind) {
        ValueKind.EXPRESSION, ValueKind.FILTER -> tokenizeExpression(value, templatePlaceholders(value))
        ValueKind.SIGNAL -> tokenizeSignalName(value)
        else -> emptyList()
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
