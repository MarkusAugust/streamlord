package io.github.markusaugust.streamlord.intellij.completion

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionSorter
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.completion.InsertionContext
import com.intellij.codeInsight.completion.PlainPrefixMatcher
import com.intellij.codeInsight.completion.ml.MLRankingIgnorable
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.codeInsight.lookup.LookupElementDecorator
import com.intellij.codeInsight.lookup.LookupElementWeigher
import com.intellij.icons.AllIcons
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.extensions.PluginId
import com.intellij.psi.PsiLanguageInjectionHost
import com.intellij.psi.util.PsiTreeUtil
import io.github.markusaugust.streamlord.analysis.Completion
import io.github.markusaugust.streamlord.analysis.CompletionKind
import io.github.markusaugust.streamlord.analysis.Workspace
import io.github.markusaugust.streamlord.analysis.complete
import io.github.markusaugust.streamlord.intellij.Streamlord
import io.github.markusaugust.streamlord.intellij.analysis.StreamlordAnalysis
import io.github.markusaugust.streamlord.intellij.index.SignalIndex
import io.github.markusaugust.streamlord.intellij.settings.StreamlordSettings
import org.jetbrains.kotlin.psi.KtFile

/** Completions for Datastar in markup, Kotlin strings, the DSL and its attributes form: the analysis's items, as lookup elements. */
class StreamlordCompletionContributor : CompletionContributor() {
    override fun fillCompletionVariants(
        parameters: CompletionParameters,
        result: CompletionResultSet,
    ) {
        if (parameters.completionType != CompletionType.BASIC) return
        val file = parameters.originalFile
        // A string with HTML injected into it is completed inside the injection; the Kotlin pass stays quiet there.
        if (file is KtFile && injectedAt(file, parameters.offset)) return
        val asked = DatastarCaret.at(file, parameters.offset) ?: return
        val project = file.project
        val manager = InjectedLanguageManager.getInstance(project)
        // In a plain HTML file the official Datastar plugin may offer the attribute names and the DOM's events already.
        val yieldNames =
            file !is KtFile && file.virtualFile != null && !manager.isInjectedFragment(file) &&
                StreamlordSettings.getInstance(project).state.yieldAttributeNamesToDatastarPlugin &&
                PluginManagerCore.isPluginInstalled(PluginId.getId(Streamlord.DATASTAR_PLUGIN_ID))
        val index = SignalIndex.getInstance(project)
        val selectors = index.allSelectors()
        val workspace = Workspace(index.definitionsSeenFrom(asked.file), selectors.ids, selectors.classes, called = "project")
        val found =
            StreamlordAnalysis
                .getInstance(project)
                .analyzer
                .complete(asked.source, asked.offset, workspace, attributeNames = !yieldNames, domEvents = !yieldNames)
        // Where a signal, an action, an option or a selector is named, the Kotlin plugin's own list would bury it.
        if (file is KtFile && found.alone) result.stopHere()
        val sorter = CompletionSorter.emptySorter().weigh(ByRank)
        for ((from, items) in found.items.withIndex().groupBy { it.value.replaceFrom }) {
            val typed = asked.source.text.substring(from, asked.offset)
            val set = result.withPrefixMatcher(PlainPrefixMatcher(typed)).withRelevanceSorter(sorter)
            for ((rank, item) in items) set.addElement(Fixed(element(item, rank)))
        }
    }

    private fun injectedAt(
        file: KtFile,
        offset: Int,
    ): Boolean {
        val host = PsiTreeUtil.getParentOfType(file.findElementAt(offset), PsiLanguageInjectionHost::class.java, false) ?: return false
        return !InjectedLanguageManager.getInstance(file.project).getInjectedPsiFiles(host).isNullOrEmpty()
    }
}

private val ICONS =
    mapOf(
        CompletionKind.SIGNAL to AllIcons.Nodes.Variable,
        CompletionKind.SCOPE to AllIcons.Nodes.Variable,
        CompletionKind.ACTION to AllIcons.Nodes.Function,
        CompletionKind.OPTION to AllIcons.Nodes.Property,
        CompletionKind.ATTRIBUTE to AllIcons.Nodes.Property,
        CompletionKind.EVENT to AllIcons.Nodes.Property,
        CompletionKind.MODIFIER to AllIcons.Nodes.Parameter,
        CompletionKind.VALUE to AllIcons.Nodes.Enum,
        CompletionKind.ID to AllIcons.Nodes.Tag,
        CompletionKind.CLASS to AllIcons.Xml.Css_class,
    )

private fun element(
    item: Completion,
    rank: Int,
): LookupElement {
    val lookup = item.filter ?: item.label
    val text = item.snippet ?: item.insert
    val builder =
        LookupElementBuilder
            .create(LookupDoc(item.label, item.doc, rank), lookup)
            .withPresentableText(item.label)
            .withTypeText(item.detail)
            .withTailText(item.tail, true)
            .withIcon(ICONS[item.kind])
    return if (text == null ||
        text == lookup
    ) {
        builder
    } else {
        builder.withInsertHandler { context, _ -> insert(context, text, item.snippet != null) }
    }
}

/** What the item writes in place of the name it was picked by: a snippet's text with its first place to fill selected. */
private fun insert(
    context: InsertionContext,
    text: String,
    snippet: Boolean,
) {
    val (written, place) = if (snippet) expand(text) else text to (text.length until text.length)
    context.document.replaceString(context.startOffset, context.tailOffset, written)
    val start = context.startOffset
    context.editor.caretModel.moveToOffset(start + place.last + 1)
    if (!place.isEmpty()) context.editor.selectionModel.setSelection(start + place.first, start + place.last + 1)
}

private val PLACE = Regex("""\$\{(\d+)(?::([^}]*)|\|([^|}]*)\|)?}""")

/** A snippet's text, each place to fill written with its default or first choice, and where the place numbered lowest stands. */
internal fun expand(snippet: String): Pair<String, IntRange> {
    val out = StringBuilder()
    var first: Pair<Int, IntRange>? = null
    var from = 0
    for (m in PLACE.findAll(snippet)) {
        out.append(snippet, from, m.range.first)
        val default = m.groups[2]?.value ?: m.groups[3]?.value?.substringBefore(',') ?: ""
        val number = m.groupValues[1].toInt()
        if (first == null || number < first.first) first = number to (out.length until out.length + default.length)
        out.append(default)
        from = m.range.last + 1
    }
    out.append(snippet, from, snippet.length)
    return out.toString() to (first?.second ?: (out.length until out.length))
}

/** The order the analysis gave the items. */
private object ByRank : LookupElementWeigher("streamlordRank") {
    override fun weigh(element: LookupElement): Comparable<*> = (element.`object` as? LookupDoc)?.rank ?: Int.MAX_VALUE
}

/**
 * Our items keep the order our sorter gave them: the machine-learned ranking of Kotlin completion
 * leaves an element marked [MLRankingIgnorable] where it is, instead of guessing.
 */
private class Fixed(
    delegate: LookupElement,
) : LookupElementDecorator<LookupElement>(delegate),
    MLRankingIgnorable
