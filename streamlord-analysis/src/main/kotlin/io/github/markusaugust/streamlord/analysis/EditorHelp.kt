package io.github.markusaugust.streamlord.analysis

import io.github.markusaugust.streamlord.core.catalog.CatalogNames
import io.github.markusaugust.streamlord.core.client.regex

/*
 * Completion, hover and signature help: what an editor shows at a caret. Both editors call these
 * three and translate what comes back; which items, in which order, with which text and
 * documentation, is decided here.
 */

/**
 * The file an editor asks about: its [text], how it is written, and what the editor knows of the
 * module. A Kotlin file holds the kotlinx.html DSL, its `attributes["data-..."]` form and HTML
 * strings, each found by where the caret stands.
 */
public class Source(
    public val text: String,
    public val language: SourceLanguage,
    public val prefix: String = CatalogNames.PREFIX,
    /** Whether the module has streamlord-core; null where the editor cannot tell, and the imports decide. */
    public val core: Boolean? = null,
    /** Whether the module has streamlord-html; null where the editor cannot tell, and the imports decide. */
    public val htmlDsl: Boolean? = null,
    /** Whether the call named so, its name starting there, is somebody else's, as an editor that resolves calls can tell. */
    public val foreign: (name: String, nameStart: Int) -> Boolean = { _, _ -> false },
)

/** What the rest of the workspace defines: signals, ids and classes, this file's among them or not. */
public class Workspace(
    public val signals: Collection<String> = emptyList(),
    public val ids: Collection<String> = emptyList(),
    public val classes: Collection<String> = emptyList(),
    /** What the editor calls it in an item's detail: `workspace`, `project`. */
    public val called: String = "workspace",
)

public enum class CompletionKind { SIGNAL, ACTION, OPTION, SCOPE, ATTRIBUTE, EVENT, MODIFIER, VALUE, ID, CLASS }

/**
 * One item of a completion list. It replaces the text from [replaceFrom] up to the caret with
 * [snippet], whose `${1:...}` are the places to fill, or else [insert], or else the [label].
 */
public data class Completion(
    val label: String,
    val kind: CompletionKind,
    val replaceFrom: Int,
    val insert: String? = null,
    val snippet: String? = null,
    val detail: String? = null,
    /** Shown after the label: an option's default. */
    val tail: String? = null,
    /** Markdown. */
    val doc: String? = null,
    /** What the typed text is matched against, where it is not the label. */
    val filter: String? = null,
    /** What the list is ordered by, where it is not the label. */
    val sort: String? = null,
)

/** The items at a caret, in the order shown, and whether nothing else belongs there: a signal, an action, an option or a selector is being named. */
public class Completions(
    public val items: List<Completion>,
    public val alone: Boolean,
)

/** What hover shows: the Markdown for the text from [start] to [end]. */
public data class Hover(
    val start: Int,
    val end: Int,
    val markdown: String,
)

/** The action a caret stands in the arguments of: its signature, what it does, where each parameter stands in [label], and which is at the caret. */
public data class SignatureHelp(
    val label: String,
    val doc: String,
    val parameters: List<IntRange>,
    val active: Int,
)

/**
 * The items to offer at [offset] of [source]: signals, actions, options and the names in scope in
 * a Datastar expression; attribute names, events, modifiers and their values in a tag or in the
 * key of the attributes form; ids and classes in a selector. [attributeNames] and [domEvents] are
 * false where the editor offers those already.
 */
public fun Analyzer.complete(
    source: Source,
    offset: Int,
    workspace: Workspace = Workspace(),
    attributeNames: Boolean = true,
    domEvents: Boolean = true,
): Completions = Asked(this, source, offset).complete(workspace, attributeNames, domEvents)

/**
 * Whether [complete] has anything for [offset] of [source], found without making the items: what an
 * editor asks as a character is typed. With [names] false, a name in a tag does not count.
 */
public fun Analyzer.offers(
    source: Source,
    offset: Int,
    names: Boolean,
): Boolean = Asked(this, source, offset).offers(names)

/** The documentation of what stands at [offset] of [source]: an action, an option, an attribute or a helper of the DSL. */
public fun Analyzer.hover(
    source: Source,
    offset: Int,
): Hover? = Asked(this, source, offset).hover()

/** The parameters of the action call [offset] of [source] stands in. */
public fun Analyzer.signatureHelp(
    source: Source,
    offset: Int,
): SignatureHelp? {
    val asked = Asked(this, source, offset)
    val expression = asked.expression(offset)?.takeIf { it.allowActions } ?: return null
    val call = actionCallAt(expression.before, expression.before.length) ?: return null
    val action = catalog.actionsByName[call.action] ?: return null
    var from = action.signature.indexOf('(') + 1
    val parameters =
        action.parameters.map { name ->
            val at = action.signature.indexOf(name, from).coerceAtLeast(0)
            from = at + name.length
            at until at + name.length
        }
    return SignatureHelp(action.signature, action.doc, parameters, minOf(call.argument, maxOf(0, parameters.size - 1)))
}

private val NOTHING = Completions(emptyList(), alone = false)
private val DURATIONS = listOf("500ms", "1s", "300ms", "100ms")
private val SCOPE_WORD = regex("""(?:^|[^A-Za-z0-9_$])([a-z]*)$""")
private val SELECTOR_TOKEN = regex("""[#.]?[\w-]*$""")
private val ATTRIBUTES_KEY = regex("""\battributes\s*\[\s*$""")
private val ACTION_WORD = regex("""@[A-Za-z_][A-Za-z0-9_]*""")
private val OPTION_WORD = regex("""[A-Za-z_$][\w$]*""")
private val ATTRIBUTE_WORD = regex("""[A-Za-z][A-Za-z0-9_:.-]*""")
private val KOTLIN_WORD = regex("""[A-Za-z_][A-Za-z0-9_]*""")

/** One question at [offset] of [source], with the modules the file can use settled. */
private class Asked(
    val analyzer: Analyzer,
    val source: Source,
    val offset: Int,
) {
    val text = source.text
    val prefix = source.prefix
    val catalog = analyzer.catalog
    val kotlin = source.language == SourceLanguage.KOTLIN
    val modules = analyzer.modulesOf(source)

    fun complete(
        workspace: Workspace,
        attributeNames: Boolean,
        domEvents: Boolean,
    ): Completions {
        if (offset !in 0..text.length) return NOTHING
        if (kotlin) analyzer.selectorBefore(text, offset, modules)?.let { return selectors(it, workspace) }
        expression(offset)?.let { return expressionItems(it, workspace) }
        val names = Names(attributeNames, domEvents)
        if (kotlin) attributesKey()?.let { return names.at(it, tag = null) }
        val name = if (kotlin) nameInKotlin() else nameAtCaret(text, offset, prefix)
        return name?.let { names.at(it.typed, it.tag) } ?: NOTHING
    }

    fun offers(names: Boolean): Boolean {
        if (offset !in 0..text.length) return false
        if (kotlin && analyzer.selectorBefore(text, offset, modules) != null) return true
        expression(offset)?.let { e ->
            val before = e.before
            if (signalPrefixAt(before, before.length) != null) return true
            if (!e.allowActions) return false
            return actionCallAt(before, before.length)?.option != null || actionPrefixAt(before, before.length) != null ||
                SCOPE_WORD
                    .find(before)
                    ?.groupValues
                    ?.get(1)
                    .orEmpty()
                    .isNotEmpty()
        }
        if (!names) return false
        val at = Names(attributeNames = true, domEvents = false)
        if (kotlin) attributesKey()?.let { return at.at(it, tag = null).items.isNotEmpty() }
        val name = (if (kotlin) nameInKotlin() else nameAtCaret(text, offset, prefix)) ?: return false
        return at.at(name.typed, name.tag).items.isNotEmpty()
    }

    /** The Datastar expression at [at]: in a Kotlin file a DSL call's string only where the file can call the DSL. */
    fun expression(at: Int): ExpressionAtCaret? =
        if (kotlin) analyzer.expressionAtCaret(text, at, prefix, modules) else analyzer.expressionAtCaret(text, at, false, prefix)

    /** The key of `attributes["data-..."]` the caret stands in, as typed up to it. */
    fun attributesKey(): String? {
        val s = analyzer.lex(text).stringAt(offset)?.takeIf { offset > it.contentStart && offset <= it.contentEnd } ?: return null
        if (!ATTRIBUTES_KEY.containsMatchIn(text.substring(maxOf(0, s.start - 40), s.start))) return null
        return s.text.substring(0, decodedIndex(s, offset))
    }

    /** The attribute name at the caret in an HTML string of a Kotlin file, or in the markup of a `+` chain that opens with a tag. */
    fun nameInKotlin(): NameAtCaret? {
        val html = analyzer.htmlStringAt(text, offset, modules)
        if (html != null) return if (offset > html.contentStart && offset <= html.contentEnd) nameAtCaret(html, offset, prefix) else null
        return analyzer.chainAt(text, offset)?.let { nameAtCaret(it, offset, prefix) }
    }

    private fun selectors(
        typed: String,
        workspace: Workspace,
    ): Completions {
        val token = SELECTOR_TOKEN.find(typed)?.value ?: ""
        val own = collectSelectors(text)
        val from = offset - token.length
        val items = ArrayList<Completion>()

        fun add(
            names: Collection<String>,
            ownNames: Set<String>,
            sigil: String,
            kind: CompletionKind,
            rank: Int,
        ) {
            for (name in (names + ownNames).toSet()) {
                val here = name in ownNames
                val detail = "${if (kind == CompletionKind.ID) "id" else "class"} (${if (here) "this file" else workspace.called})"
                items += Completion(sigil + name, kind, from, detail = detail, sort = "${if (here) rank else rank + 1}$name")
            }
        }
        if (!token.startsWith(".")) add(workspace.ids, own.ids, "#", CompletionKind.ID, 0)
        if (!token.startsWith("#")) add(workspace.classes, own.classes, ".", CompletionKind.CLASS, 2)
        return ordered(items, alone = true)
    }

    /**
     * Signals after `$`, actions after `@`, the options of a backend action on a key of its options
     * object, the names in scope after a bare lowercase word. What is replaced is measured back from
     * the caret in the source, where the text before it may be written with escapes.
     */
    private fun expressionItems(
        expression: ExpressionAtCaret,
        workspace: Workspace,
    ): Completions {
        val before = expression.before
        signalPrefixAt(before, before.length)?.let { typed ->
            // The dollar is replaced too where the source writes it plainly, and left as written otherwise (\$, ${'$'}).
            val dollar = if (text.getOrNull(offset - typed.length - 1) == '$') "$" else ""
            val own = definitions()
            val items =
                (workspace.signals + own).filter { ':' !in it }.toSet().map { name ->
                    val here = name in own
                    Completion(
                        "$$name",
                        CompletionKind.SIGNAL,
                        offset - typed.length - dollar.length,
                        insert = dollar + name,
                        detail = "signal (${if (here) "this file" else workspace.called})",
                        filter = dollar + name,
                        sort = (if (here) "0" else "1") + name,
                    )
                }
            return ordered(items, alone = true)
        }
        if (!expression.allowActions) return NOTHING
        actionCallAt(before, before.length)?.option?.let { option ->
            // The colon is written only where none follows yet.
            val colon = if (text.substring(offset).trimStart().startsWith(":")) "" else ": "
            val items =
                catalog.fetchOptions.filter { it.name !in option.written }.map {
                    Completion(
                        it.name,
                        CompletionKind.OPTION,
                        offset - option.typed.length,
                        insert = it.name + colon,
                        detail = it.type,
                        tail = " = ${it.default}",
                        doc = fetchOptionDoc(it),
                        sort = it.name,
                    )
                }
            return ordered(items, alone = true)
        }
        actionPrefixAt(before, before.length)?.let { typed ->
            val items =
                catalog.actions.map {
                    val arguments =
                        when {
                            it.kind == "backend" -> "('\${1:/path}')"
                            it.name == "peek" -> "(() => \${1})"
                            else -> "(\${1})"
                        }
                    Completion(
                        "@${it.name}",
                        CompletionKind.ACTION,
                        offset - typed.length - 1,
                        snippet = "@${it.name}$arguments",
                        detail = (if (it.pro) "Pro · " else "") + it.signature,
                        doc = it.doc,
                        sort = (if (it.pro) "1" else "0") + it.name,
                    )
                }
            return ordered(items, alone = true)
        }
        val word =
            SCOPE_WORD
                .find(before)
                ?.groupValues
                ?.get(1)
                .orEmpty()
        if (word.isEmpty()) return NOTHING
        // `patch` is a name only in `data-on-signal-patch`, the one attribute the client hands it to.
        val names = catalog.compiledParameters.filter { it.first() in 'a'..'z' } + listOfNotNull("patch".takeIf { expression.patchInScope })
        return ordered(names.map { Completion(it, CompletionKind.SCOPE, offset - word.length, detail = "Datastar scope") }, alone = false)
    }

    /**
     * The signals this file defines, read with the modules it can use: what makes a signal this
     * file's. The editor is asked about none of its calls, which there may be thousands of.
     */
    private fun definitions(): Set<String> =
        collectSignalDefinitions(
            text,
            source.language,
            foreignHelpers(
                htmlDsl =
                    !kotlin || modules.htmlDsl,
            ),
        )

    /** Attribute names, the events of `data-on:`, modifiers and their values, for a name typed in a tag or the key of the attributes form. */
    private inner class Names(
        val attributeNames: Boolean,
        val domEvents: Boolean,
    ) {
        /** In a tag named [tag]; with none, in the key of the attributes form, where no value follows the name and no tag is known. */
        fun at(
            typed: String,
            tag: String?,
        ): Completions {
            if (typed.any { it in "\"'<>" }) return NOTHING
            if ("__" in typed) return modifiers(typed)
            val lower = typed.lowercase()
            if (lower.startsWith("${prefix}on:")) return events(typed.substring(prefix.length + 3), value = tag != null)
            if (!typed.startsWith(prefix.substring(0, minOf(prefix.length, maxOf(1, typed.length)))) || !attributeNames) return NOTHING
            val items =
                catalog.attributes.filter { it.onlyOn == null || tag in it.onlyOn }.map {
                    val key = if (it.keyRequired) ":\${1:key}" else ""
                    val value = if (it.valueKind != ValueKind.NONE) "=\"\${2}\"" else ""
                    Completion(
                        prefix + it.name,
                        CompletionKind.ATTRIBUTE,
                        offset - typed.length,
                        snippet =
                            if (tag !=
                                null
                            ) {
                                prefix + it.name + key + value
                            } else {
                                key.takeIf { k -> k.isNotEmpty() }?.let { k -> prefix + it.name + k }
                            },
                        detail = (if (it.pro) "Datastar Pro · " else "Datastar · ") + it.forms.firstOrNull().orEmpty(),
                        doc = attributeDoc(it, prefix, withKotlin = true),
                        sort = (if (it.pro) "1" else "0") + it.name,
                    )
                }
            return ordered(items, alone = false)
        }

        private fun modifiers(typed: String): Completions {
            val spec = catalog.parseAttributeName(typed.lowercase(), prefix)?.spec ?: return NOTHING
            val current = typed.substring(typed.lastIndexOf("__") + 2)
            val dot = current.indexOf('.')
            if (dot >= 0) {
                val modifier = spec.modifiers.firstOrNull { it.name == current.substring(0, dot) } ?: return NOTHING
                val values =
                    when (modifier.type) {
                        ModifierType.ENUM -> modifier.values
                        ModifierType.DURATION -> if (current.split('.').size > 2) modifier.flags else DURATIONS
                        else -> emptyList()
                    }
                val from = offset - (current.length - current.lastIndexOf('.') - 1)
                return ordered(values.mapIndexed { i, v -> Completion(v, CompletionKind.VALUE, from, sort = "$i") }, alone = false)
            }
            val items =
                spec.modifiers.map {
                    val snippet =
                        when (it.type) {
                            ModifierType.FLAG -> null
                            ModifierType.DURATION -> "${it.name}.\${1:500ms}"
                            ModifierType.ENUM -> "${it.name}.\${1|${it.values.joinToString(",")}|}"
                            ModifierType.INT -> "${it.name}.\${1:50}"
                            ModifierType.IDENT, ModifierType.IDENTS -> "${it.name}.\${1:name}"
                        }
                    val form =
                        when (it.type) {
                            ModifierType.FLAG -> ""
                            ModifierType.DURATION -> ".<duration>"
                            ModifierType.ENUM -> ".<${it.values.joinToString("|")}>"
                            ModifierType.INT -> ".<n>"
                            ModifierType.IDENT, ModifierType.IDENTS -> ".<name>"
                        }
                    Completion(
                        it.name,
                        CompletionKind.MODIFIER,
                        offset - current.length,
                        snippet = snippet,
                        detail = "__${it.name}$form",
                        sort = it.name,
                    )
                }
            return ordered(items, alone = false)
        }

        /** The event of `data-on:`, Datastar's own first, then the DOM's; with [value], a value is opened where none follows yet. */
        private fun events(
            typed: String,
            value: Boolean,
        ): Completions {
            val tail = if (value && !text.substring(offset).trimStart().startsWith("=")) "=\"\${1}\"" else ""
            val from = offset - typed.length

            fun event(
                name: String,
                detail: String,
                doc: String?,
                rank: Int,
            ) = Completion(
                name,
                CompletionKind.EVENT,
                from,
                snippet = if (value) name + tail else null,
                detail = detail,
                doc = doc,
                sort = "$rank$name",
            )
            val datastar = catalog.datastarEvents.map { event(it.name, "Datastar event", it.doc, 0) }
            val dom =
                if (domEvents) {
                    catalog.domEvents.map {
                        event(
                            it.name,
                            "DOM event",
                            it.mdn?.let { url ->
                                "[MDN]($url)"
                            },
                            1,
                        )
                    }
                } else {
                    emptyList()
                }
            return ordered(datastar + dom, alone = false)
        }
    }

    fun hover(): Hover? {
        if (offset !in 0..text.length) return null
        val withKotlin = modules.htmlDsl
        wordAt(ACTION_WORD)?.let { range ->
            val action = catalog.actionsByName[text.substring(range.first + 1, range.last + 1)]
            if (action != null &&
                !(kotlin && analyzer.inForeignDslCall(text, offset, modules))
            ) {
                return Hover(range, actionDoc(action, withKotlin))
            }
        }
        wordAt(OPTION_WORD)?.let { range ->
            val word = text.substring(range.first, range.last + 1)
            val option = catalog.fetchOptions.firstOrNull { it.name == word }
            // The key it is when the caret stands at its end.
            val expression = option?.let { expression(range.last + 1) }
            if (expression != null && expression.allowActions &&
                actionCallAt(expression.before, expression.before.length)?.option?.typed == word
            ) {
                return Hover(range, fetchOptionDoc(option))
            }
        }
        if (!kotlin || analyzer.htmlStringAt(text, offset, modules) != null || inAttributesKey()) {
            val range = wordAt(ATTRIBUTE_WORD) ?: return null
            val spec = catalog.parseAttributeName(text.substring(range.first, range.last + 1).lowercase(), prefix)?.spec ?: return null
            return Hover(range, attributeDoc(spec, prefix, withKotlin))
        }
        // The DSL's functions are documented where the file can call them, and a name in a string is none.
        if (!withKotlin || analyzer.lex(text).stringAt(offset) != null) return null
        val range = wordAt(KOTLIN_WORD) ?: return null
        val word = text.substring(range.first, range.last + 1)
        catalog.attributesByKotlin[word]?.let { return Hover(range, attributeDoc(it, prefix, withKotlin = true)) }
        val action = catalog.actions.firstOrNull { it.kotlin == word } ?: return null
        val called = '(' in text.substring(range.last + 1, minOf(text.length, range.last + 3)).substringBefore('\n')
        return if (called) Hover(range, actionDoc(action, withKotlin = false)) else null
    }

    private fun inAttributesKey(): Boolean = analyzer.attributesIndexes(text).any { offset in it.nameStart..it.nameStart + it.name.length }

    /** The first match of [word] on the caret's line that holds the caret, at either end too, as an editor finds the word at a position. */
    private fun wordAt(word: Regex): IntRange? {
        val lineStart = text.lastIndexOf('\n', offset - 1) + 1
        val lineEnd = text.indexOf('\n', offset).let { if (it < 0) text.length else it }
        return word
            .findAll(text.substring(lineStart, lineEnd))
            .firstOrNull { it.range.first + lineStart <= offset && offset <= it.range.last + 1 + lineStart }
            ?.let { (it.range.first + lineStart)..(it.range.last + lineStart) }
    }
}

private fun Hover(
    range: IntRange,
    markdown: String,
): Hover = Hover(range.first, range.last + 1, markdown)

/** [items] in the order shown: by what each is sorted by, then by label. */
private fun ordered(
    items: List<Completion>,
    alone: Boolean,
): Completions = Completions(items.sortedWith(compareBy<Completion> { it.sort ?: it.label }.thenBy { it.label }), alone)
