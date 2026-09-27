package io.github.markusaugust.streamlord.intellij.completion

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionSorter
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.completion.InsertionContext
import com.intellij.codeInsight.completion.PlainPrefixMatcher
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.completion.ml.MLRankingIgnorable
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.codeInsight.lookup.LookupElementDecorator
import com.intellij.codeInsight.lookup.LookupElementWeigher
import com.intellij.icons.AllIcons
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.editor.EditorModificationUtil
import com.intellij.openapi.extensions.PluginId
import com.intellij.psi.PsiFile
import io.github.markusaugust.streamlord.analysis.ActionSpec
import io.github.markusaugust.streamlord.analysis.AttributeSpec
import io.github.markusaugust.streamlord.analysis.Catalog
import io.github.markusaugust.streamlord.analysis.ModifierType
import io.github.markusaugust.streamlord.analysis.ValueKind
import io.github.markusaugust.streamlord.analysis.actionDoc
import io.github.markusaugust.streamlord.analysis.actionPrefixAt
import io.github.markusaugust.streamlord.analysis.attributeDoc
import io.github.markusaugust.streamlord.analysis.findCallSites
import io.github.markusaugust.streamlord.analysis.markdownToHtml
import io.github.markusaugust.streamlord.analysis.signalPrefixAt
import io.github.markusaugust.streamlord.intellij.Streamlord
import io.github.markusaugust.streamlord.intellij.analysis.StreamlordAnalysis
import io.github.markusaugust.streamlord.intellij.index.SignalIndex
import io.github.markusaugust.streamlord.intellij.settings.StreamlordSettings
import org.jetbrains.kotlin.psi.KtFile

/**
 * Completions for Datastar in Kotlin strings and in HTML: `$` offers the signals declared anywhere
 * in the project (this file's first), `@` the actions, `data-` the attributes, `__` the modifiers
 * and their values, `#` and `.` in a selector argument the ids and classes of the project. Inside
 * an HTML string in Kotlin the HTML side applies.
 */
class StreamlordCompletionContributor : CompletionContributor() {
    override fun fillCompletionVariants(
        parameters: CompletionParameters,
        result: CompletionResultSet,
    ) {
        if (parameters.completionType != CompletionType.BASIC) return
        val file = parameters.originalFile
        val project = file.project
        val analysis = StreamlordAnalysis.getInstance(project)
        val manager = InjectedLanguageManager.getInstance(project)
        when {
            file is KtFile -> {
                // A string with HTML injected into it is completed inside the injection; the Kotlin pass stays quiet there.
                val src = file.text
                val offset = parameters.offset
                val html = analysis.analyzer.htmlStringAt(src, offset)
                if (html != null && hostHasInjection(file, html.contentStart, html.contentEnd)) return
                Kotlin(file, src, offset, analysis, result).complete()
            }

            file.language.isKindOf(com.intellij.lang.html.HTMLLanguage.INSTANCE) || StreamlordAnalysis.isTemplateText(file) -> {
                val hostFile = manager.getInjectionHost(file)?.containingFile
                val index = SignalIndex.getInstance(project)
                // Attribute names in plain HTML files may come from the official Datastar plugin already.
                val yieldNames =
                    hostFile == null && file.virtualFile != null && !manager.isInjectedFragment(file) &&
                        StreamlordSettings.getInstance(project).state.yieldAttributeNamesToDatastarPlugin &&
                        PluginManagerCore.isPluginInstalled(PluginId.getId(Streamlord.DATASTAR_PLUGIN_ID))
                Html(file.text, parameters.offset, analysis, index, hostFile ?: file, result, offerAttributeNames = !yieldNames).complete()
            }
        }
    }

    private fun hostHasInjection(
        file: KtFile,
        from: Int,
        to: Int,
    ): Boolean {
        if (to <= from) return false
        val host = file.findElementAt(from) ?: return false
        val manager = InjectedLanguageManager.getInstance(file.project)
        var element: com.intellij.psi.PsiElement? = host
        while (element != null && element !is com.intellij.psi.PsiLanguageInjectionHost) element = element.parent
        if (element !is com.intellij.psi.PsiLanguageInjectionHost) return false
        return !manager.getInjectedPsiFiles(element).isNullOrEmpty()
    }

    private class Kotlin(
        val file: KtFile,
        val src: String,
        val offset: Int,
        val analysis: StreamlordAnalysis,
        val result: CompletionResultSet,
    ) {
        val analyzer = analysis.analyzer
        val catalog: Catalog = analyzer.catalog
        val index = SignalIndex.getInstance(file.project)

        fun complete() {
            // Inside a selector, a Datastar expression or a data-* value, the Kotlin plugin's own list (string templates,
            // every symbol in scope) says nothing useful and would bury the signals; it is stopped there, and only there.
            if (selectorItems()) {
                result.stopHere()
                return
            }
            val html = analyzer.htmlStringAt(src, offset)
            if (html != null && offset > html.contentStart && offset <= html.contentEnd) {
                if (Html(src, offset, analysis, index, file, result, offerAttributeNames = true).complete()) result.stopHere()
                return
            }
            val lex = analyzer.lex(src)
            val site =
                findCallSites(src, catalog.expressionCallSites.keys, lex).firstOrNull { it.openParen < offset && offset <= it.closeParen }
                    ?: return
            val spec = catalog.expressionCallSites[site.name] ?: return
            val s =
                site.selectString(spec) ?: site.args.mapNotNull { it.string }.firstOrNull { it.start < offset && offset <= it.end }
                    ?: return
            if (offset <= s.start || offset > s.contentEnd + (if (s.unterminated) 1 else 0)) return
            val text = src.substring(s.contentStart, offset)
            if (expressionItems(text, index, file, result, allowActions = true)) result.stopHere()
        }

        /** `selector = "#|"` on any DSL call, or the first argument of removeElements: ids and classes from the project. */
        private fun selectorItems(): Boolean {
            val lex = analyzer.lex(src)
            val site =
                findCallSites(src, analyzer.callSiteNames, lex).firstOrNull { it.openParen < offset && offset <= it.closeParen }
                    ?: return false
            val arg =
                site.args.firstOrNull { a ->
                    val s = a.string
                    s != null && s.start < offset && offset <= s.contentEnd + (if (s.unterminated) 1 else 0)
                } ?: return false
            val string = arg.string ?: return false
            val isSelector =
                (arg.named != null && arg.named in SELECTOR_ARG_NAMES) ||
                    (arg.named == null && site.name == "removeElements" && site.args.indexOf(arg) == 0)
            if (!isSelector) return false
            val typed = src.substring(string.contentStart, offset)
            val token = Regex("""[#.]?[\w-]*$""").find(typed)?.value ?: ""
            val local = index.selectorsForFile(file)
            val all = index.allSelectors()
            val rank = { text: String ->
                when {
                    text.startsWith("#") -> if (text.substring(1) in local.ids) 0 else 1
                    else -> if (text.substring(1) in local.classes) 2 else 3
                }
            }
            val matcher = result.withPrefixMatcher(PlainPrefixMatcher(token)).withRelevanceSorter(sorter(rank))
            if (!token.startsWith(".")) {
                for (id in all.ids.sorted()) {
                    val isLocal = id in local.ids
                    matcher.addFixed(
                        LookupElementBuilder
                            .create(
                                "#$id",
                            ).withTypeText(if (isLocal) "id (this file)" else "id (project)")
                            .withIcon(AllIcons.Nodes.Tag),
                    )
                }
            }
            if (!token.startsWith("#")) {
                for (cls in all.classes.sorted()) {
                    val isLocal = cls in local.classes
                    matcher.addFixed(
                        LookupElementBuilder
                            .create(
                                ".$cls",
                            ).withTypeText(if (isLocal) "class (this file)" else "class (project)")
                            .withIcon(AllIcons.Xml.Css_class),
                    )
                }
            }
            return true
        }
    }

    /** Signals, actions, attribute names, modifiers and modifier values in markup, whether a file or a string in Kotlin. */
    private class Html(
        val src: String,
        val offset: Int,
        val analysis: StreamlordAnalysis,
        val index: SignalIndex,
        val indexFile: PsiFile,
        val result: CompletionResultSet,
        val offerAttributeNames: Boolean,
    ) {
        val catalog: Catalog = analysis.catalog
        val prefix: String = analysis.prefix

        /** Returns true when the caret sat in a `data-*` value and the expression items were offered. */
        fun complete(): Boolean {
            val lineStart = src.lastIndexOf('\n', maxOf(0, offset - 1)) + 1
            val before = src.substring(maxOf(lineStart, offset - 400), offset.coerceIn(0, src.length))
            // Inside an attribute value: name="...|
            val inValue = Regex("""([^\s"'<>=]+)\s*=\s*"([^"]*)$""").find(before)
            if (inValue != null) {
                val parsed = catalog.parseAttributeName(inValue.groupValues[1].lowercase(), prefix)
                val spec = parsed?.spec ?: return false
                if (spec.valueKind == ValueKind.EXPRESSION || spec.valueKind == ValueKind.SIGNAL) {
                    return expressionItems(
                        inValue.groupValues[2],
                        index,
                        indexFile,
                        result,
                        allowActions =
                            spec.valueKind == ValueKind.EXPRESSION,
                    )
                }
                return false
            }
            // On an attribute name inside a tag: <div data-on:cl|
            val inName =
                Regex(
                    """<([A-Za-z][A-Za-z0-9:-]*)(?:\s+[^\s<>]+(?:\s*=\s*(?:"[^"]*"|'[^']*'|[^\s"'>]*))?)*\s+([^\s<>="']*)$""",
                ).find(before)
                    ?: return false
            val partial = inName.groupValues[2]
            val tagName = inName.groupValues[1].lowercase()
            if ("__" in partial) {
                modifierItems(partial)
                return false
            }
            if (!partial.startsWith(prefix.substring(0, minOf(prefix.length, maxOf(1, partial.length))))) return false
            if (!offerAttributeNames) return false
            val matcher = result.withPrefixMatcher(PlainPrefixMatcher(partial))
            for (a in catalog.attributes) {
                val onlyOn = a.onlyOn
                if (onlyOn != null && tagName !in onlyOn) continue
                matcher.addFixed(
                    PrioritizedLookupElement.withPriority(
                        LookupElementBuilder
                            .create(prefix + a.name)
                            .withTypeText((if (a.pro) "Datastar Pro · " else "Datastar · ") + (a.forms.firstOrNull() ?: ""))
                            .withIcon(AllIcons.Nodes.Property)
                            .withInsertHandler { context, _ -> insertAttribute(context, a) },
                        if (a.pro) 5.0 else 10.0,
                    ),
                )
            }
            return false
        }

        private fun insertAttribute(
            context: InsertionContext,
            a: AttributeSpec,
        ) {
            val editor = context.editor
            val tail = StringBuilder()
            var caretBack = 0
            if (a.keyRequired) tail.append(':')
            if (a.valueKind != ValueKind.NONE && !a.keyRequired) {
                tail.append("=\"\"")
                caretBack = 1
            }
            EditorModificationUtil.insertStringAtCaret(editor, tail.toString())
            if (caretBack > 0) editor.caretModel.moveToOffset(editor.caretModel.offset - caretBack)
        }

        private fun modifierItems(partial: String) {
            val parsed = catalog.parseAttributeName(partial.lowercase(), prefix)
            val spec = parsed?.spec ?: return
            val lastSep = partial.lastIndexOf("__")
            val current = partial.substring(lastSep + 2)
            val dot = current.indexOf('.')
            if (dot >= 0) {
                val mod = spec.modifiers.firstOrNull { it.name == current.substring(0, dot) } ?: return
                val afterDot = current.substring(current.lastIndexOf('.') + 1)
                val values =
                    when (mod.type) {
                        ModifierType.ENUM -> mod.values
                        ModifierType.DURATION -> if (current.split(".").size > 2) mod.flags else listOf("500ms", "1s", "300ms", "100ms")
                        else -> emptyList()
                    }
                val matcher = result.withPrefixMatcher(PlainPrefixMatcher(afterDot))
                values.forEachIndexed { i, v ->
                    matcher.addFixed(
                        PrioritizedLookupElement.withPriority(
                            LookupElementBuilder.create(v).withIcon(AllIcons.Nodes.Enum),
                            100.0 - i,
                        ),
                    )
                }
                return
            }
            val matcher = result.withPrefixMatcher(PlainPrefixMatcher(current))
            for (m in spec.modifiers) {
                val detail =
                    "__${m.name}" +
                        when (m.type) {
                            ModifierType.FLAG -> ""
                            ModifierType.DURATION -> ".<duration>"
                            ModifierType.ENUM -> ".<${m.values.joinToString("|")}>"
                            ModifierType.INT -> ".<n>"
                            ModifierType.IDENT, ModifierType.IDENTS -> ".<name>"
                        }
                val insert =
                    when (m.type) {
                        ModifierType.FLAG -> m.name
                        ModifierType.DURATION -> "${m.name}.500ms"
                        ModifierType.ENUM -> "${m.name}.${m.values.first()}"
                        ModifierType.INT -> "${m.name}.50"
                        ModifierType.IDENT, ModifierType.IDENTS -> "${m.name}."
                    }
                matcher.addFixed(
                    LookupElementBuilder
                        .create(m.name)
                        .withTypeText(detail)
                        .withIcon(AllIcons.Nodes.Parameter)
                        .withInsertHandler { context, _ ->
                            EditorModificationUtil.insertStringAtCaret(context.editor, insert.removePrefix(m.name))
                        },
                )
            }
        }
    }

    companion object {
        private val SELECTOR_ARG_NAMES = setOf("selector", "viewTransitionSelector")

        /**
         * Signals after `$`, actions after `@`, the scope variables after a bare lowercase prefix. Returns true when the caret
         * sits in a signal or action position, where the items offered are the whole story.
         */
        fun expressionItems(
            textBefore: String,
            index: SignalIndex,
            file: PsiFile,
            result: CompletionResultSet,
            allowActions: Boolean,
        ): Boolean {
            val catalog = StreamlordAnalysis.getInstance(file.project).catalog
            val sig = signalPrefixAt(textBefore, textBefore.length)
            if (sig != null) {
                val local = index.forFile(file)
                val matcher =
                    result.withPrefixMatcher(PlainPrefixMatcher("$$sig")).withRelevanceSorter(
                        sorter {
                            if (it.removePrefix("$") in
                                local
                            ) {
                                0
                            } else {
                                1
                            }
                        },
                    )
                for (name in index.all().sorted()) {
                    val isLocal = name in local
                    matcher.addFixed(
                        LookupElementBuilder
                            .create("$$name")
                            .withTypeText(if (isLocal) "signal (this file)" else "signal (project)")
                            .withIcon(AllIcons.Nodes.Variable),
                    )
                }
                return true
            }
            if (!allowActions) return false
            val act = actionPrefixAt(textBefore, textBefore.length)
            if (act != null) {
                val matcher = result.withPrefixMatcher(PlainPrefixMatcher("@$act"))
                for (a in catalog.actions) matcher.addFixed(actionElement(a))
                return true
            }
            val scope = Regex("""(?:^|[^A-Za-z0-9_$])([a-z]*)$""").find(textBefore)
            if (scope != null && scope.groupValues[1].isNotEmpty()) {
                val matcher = result.withPrefixMatcher(PlainPrefixMatcher(scope.groupValues[1]))
                for (v in listOf(
                    "el",
                    "evt",
                    "patch",
                )) {
                    matcher.addFixed(LookupElementBuilder.create(v).withTypeText("Datastar scope").withIcon(AllIcons.Nodes.Variable))
                }
            }
            return false
        }

        /** A sorter of our own: the given rank first (this file before the project), then the name. */
        fun sorter(rank: (String) -> Int): CompletionSorter =
            CompletionSorter
                .emptySorter()
                .weigh(
                    object : LookupElementWeigher("streamlordRank") {
                        override fun weigh(element: LookupElement): Comparable<*> = rank(element.lookupString)
                    },
                ).weigh(
                    object : LookupElementWeigher("streamlordName") {
                        override fun weigh(element: LookupElement): Comparable<*> = element.lookupString
                    },
                )

        private fun actionElement(a: ActionSpec): LookupElement {
            val insert =
                when {
                    a.kind == "backend" -> "('/path')"
                    a.name == "peek" -> "(() => )"
                    else -> "()"
                }
            val caretBack =
                when {
                    a.kind == "backend" -> 2
                    a.name == "peek" -> 1
                    else -> 1
                }
            return PrioritizedLookupElement.withPriority(
                LookupElementBuilder
                    .create("@${a.name}")
                    .withTypeText((if (a.pro) "Pro · " else "") + a.signature)
                    .withIcon(AllIcons.Nodes.Function)
                    .withInsertHandler { context, _ ->
                        EditorModificationUtil.insertStringAtCaret(context.editor, insert)
                        context.editor.caretModel.moveToOffset(context.editor.caretModel.offset - caretBack)
                        if (a.kind ==
                            "backend"
                        ) {
                            context.editor.selectionModel.setSelection(
                                context.editor.caretModel.offset - 5,
                                context.editor.caretModel.offset,
                            )
                        }
                    },
                if (a.pro) 5.0 else 10.0,
            )
        }

        /** Documentation of an attribute for the lookup, as HTML. */
        fun attributeHtml(
            a: AttributeSpec,
            prefix: String,
        ): String = markdownToHtml(attributeDoc(a, prefix))

        fun actionHtml(a: ActionSpec): String = markdownToHtml(actionDoc(a))
    }
}

/**
 * Our items keep the order our sorter gave them: the machine-learned ranking of Kotlin completion
 * leaves an element marked [MLRankingIgnorable] where it is, instead of guessing.
 */
private class Fixed(
    delegate: LookupElement,
) : LookupElementDecorator<LookupElement>(delegate),
    MLRankingIgnorable

/** Add an element that the machine-learned ranking must not move. */
private fun CompletionResultSet.addFixed(element: LookupElement) = addElement(Fixed(element))
