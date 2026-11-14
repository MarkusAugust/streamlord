package io.github.markusaugust.streamlord.intellij.spelling

import com.intellij.lang.html.HTMLLanguage
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.spellchecker.inspections.PlainTextSplitter
import com.intellij.spellchecker.tokenizer.SpellcheckingStrategy
import com.intellij.spellchecker.tokenizer.TokenConsumer
import com.intellij.spellchecker.tokenizer.Tokenizer
import io.github.markusaugust.streamlord.analysis.SourceLanguage
import io.github.markusaugust.streamlord.analysis.ValueKind
import io.github.markusaugust.streamlord.analysis.findCallSites
import io.github.markusaugust.streamlord.analysis.proseRanges
import io.github.markusaugust.streamlord.analysis.readsDsl
import io.github.markusaugust.streamlord.intellij.analysis.StreamlordAnalysis
import io.github.markusaugust.streamlord.intellij.settings.StreamlordSettings
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtSimpleNameStringTemplateEntry
import org.jetbrains.kotlin.psi.KtStringTemplateEntryWithExpression
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.KtValueArgument

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

    /** The expression [element] is or lies in: a Datastar attribute value, or the string of a DSL expression call or of `attributes[...]`. */
    private fun owner(element: PsiElement): PsiElement? {
        if (element.containingFile is KtFile) {
            // A string in a Kotlin file, not one in its injected HTML, which is a file of its own.
            val string = PsiTreeUtil.getParentOfType(element, KtStringTemplateExpression::class.java, false, KtDeclaration::class.java)
            return string?.takeIf { isExpressionString(it) }
        }
        PsiTreeUtil.getParentOfType(element, XmlAttributeValue::class.java, false)?.let { value ->
            if (!value.containingFile.language.isKindOf(HTMLLanguage.INSTANCE)) return null
            val attribute = value.parent as? XmlAttribute ?: return null
            val analysis = StreamlordAnalysis.getInstance(element.project)
            val spec = analysis.catalog.parseAttributeName(attribute.name.lowercase(), analysis.prefix)?.spec ?: return null
            return value.takeIf { spec.valueKind == ValueKind.EXPRESSION || spec.valueKind == ValueKind.SIGNAL }
        }
        return null
    }

    /**
     * Whether [string] is the string a DSL expression call hands Datastar, `dataOnClick("...")`, or the value of
     * `attributes["data-text"] = "..."`. The spell checker asks for every element of every pass, so the strings are
     * found once for each version of the file, and a string that is neither is answered without them.
     */
    private fun isExpressionString(string: KtStringTemplateExpression): Boolean {
        // An argument, as written or with its indent trimmed: `dataOnClick("""...""".trimIndent())`.
        val parent = string.parent
        val argument = if (parent is KtDotQualifiedExpression && parent.receiverExpression === string) parent.parent else parent
        // Or the value of kotlinx.html's `attributes["data-text"] = "..."`.
        if (argument !is KtValueArgument && !(parent is KtBinaryExpression && parent.right === string)) return false
        val file = string.containingFile as? KtFile ?: return false
        return string.textRange.startOffset in expressionStrings(file)
    }

    /**
     * Where each string starts in [file] that kotlinx.html's `attributes["data-..."] =` or a DSL expression call hands
     * Datastar. A DSL call's is not one without the DSL, where such a call is somebody else's and its string prose, nor
     * when the Kotlin plugin resolves the call elsewhere. Worked out again when the file, the settings or the project's
     * libraries change.
     */
    private fun expressionStrings(file: KtFile): Set<Int> =
        CachedValuesManager.getCachedValue(file) {
            val project = file.project
            val analysis = StreamlordAnalysis.getInstance(project)
            val catalog = analysis.catalog
            val src = file.text
            val starts = HashSet<Int>()
            // kotlinx.html's own form is Datastar whatever the project has.
            for (at in analysis.analyzer.attributesIndexes(src)) {
                val kind = catalog.parseAttributeName(at.name.lowercase(), analysis.prefix)?.spec?.valueKind
                if (at.whole && (kind == ValueKind.EXPRESSION || kind == ValueKind.SIGNAL)) at.value?.let { starts += it.start }
            }
            if (analysis.analyzer.readsDsl(analysis.source(src, SourceLanguage.KOTLIN, file))) {
                val sites = findCallSites(src, catalog.expressionCallSites.keys, analysis.analyzer.lex(src))
                val foreign = analysis.foreignAmong(file, sites)
                for (site in sites) {
                    if (site.nameStart in foreign) continue
                    // The strings by their place, or by their name: `dataOnClick(expression = "...")`.
                    val spec = catalog.expressionCallSites[site.name] ?: continue
                    site.expressionStrings(spec).ifEmpty { listOfNotNull(site.named("expression")?.string) }.forEach { starts += it.start }
                }
                for (o in analysis.analyzer.expressionOptions(src)) starts += o.value.start
            }
            CachedValueProvider.Result.create(
                starts,
                file,
                StreamlordSettings.getInstance(project).tracker,
                ProjectRootManager.getInstance(project),
                DumbService.getInstance(project).modificationTracker,
            )
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
            val kotlin = element is KtStringTemplateExpression
            // A Kotlin template inside the string is Kotlin's code, spelled where it is declared.
            val templates =
                (element as? KtStringTemplateExpression)
                    ?.entries
                    ?.filter { it is KtStringTemplateEntryWithExpression || it is KtSimpleNameStringTemplateEntry }
                    ?.map { it.textRange.shiftLeft(element.textRange.startOffset) }
                    .orEmpty()
            for (range in proseRanges(text.substring(content.startOffset, content.endOffset))) {
                val from = content.startOffset + range.first
                val to = content.startOffset + range.last + 1
                val pieces = if (kotlin) betweenEscapes(text, from, to) else listOf(TextRange(from, to))
                for (piece in pieces.flatMap { without(it, templates) }) {
                    consumer.consumeToken(element, text, false, 0, piece, PlainTextSplitter.getInstance())
                }
            }
        }

        /** [range] with each of [cuts] taken out of it. */
        private fun without(
            range: TextRange,
            cuts: List<TextRange>,
        ): List<TextRange> {
            var pieces = listOf(range)
            for (cut in cuts) {
                pieces =
                    pieces.flatMap { piece ->
                        if (!piece.intersects(cut)) {
                            listOf(piece)
                        } else {
                            // Built only when they hold something: a cut can run past either end of the piece.
                            listOfNotNull(
                                if (cut.startOffset > piece.startOffset) TextRange(piece.startOffset, cut.startOffset) else null,
                                if (cut.endOffset < piece.endOffset) TextRange(cut.endOffset, piece.endOffset) else null,
                            )
                        }
                    }
            }
            return pieces
        }

        /** The runs of [text] between [from] and [to] that hold no Kotlin escape: `\n` ends a word, as a new line does. */
        private fun betweenEscapes(
            text: String,
            from: Int,
            to: Int,
        ): List<TextRange> {
            val out = ArrayList<TextRange>()
            var start = from
            var i = from
            while (i < to) {
                if (text[i] != '\\') {
                    i++
                    continue
                }
                if (i > start) out += TextRange(start, i)
                i += if (text.getOrNull(i + 1) == 'u') 6 else 2
                start = minOf(i, to)
            }
            if (to > start) out += TextRange(start, to)
            return out
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
