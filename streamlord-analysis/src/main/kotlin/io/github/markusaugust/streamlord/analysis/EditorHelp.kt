package io.github.markusaugust.streamlord.analysis

import io.github.markusaugust.streamlord.core.catalog.CatalogNames
import io.github.markusaugust.streamlord.core.client.CLIENT_SIGNAL
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
    public val signals: Collection<String>,
    public val ids: Collection<String>,
    public val classes: Collection<String>,
    /** What the editor calls it in an item's detail: `workspace`, `project`. */
    public val called: String,
)

public enum class CompletionKind { SIGNAL, ACTION, OPTION, SCOPE, ATTRIBUTE, EVENT, MODIFIER, VALUE, ID, CLASS }

/** Text to write with places in it to fill, in the order the caret visits them. */
public class Snippet internal constructor(
    public val text: String,
    public val places: List<Place>,
)

/** A place of a [Snippet]: what stands there to begin with, from [start] to [end], and the [choices] it may take, if it has any. */
public class Place internal constructor(
    public val start: Int,
    public val end: Int,
    public val choices: List<String>,
)

/**
 * One item of a completion list, in the order the list shows. It replaces the text from
 * [replaceFrom] up to the caret with [snippet], or else [insert], or else the [label].
 */
public class Completion internal constructor(
    public val label: String,
    public val kind: CompletionKind,
    public val replaceFrom: Int,
    public val insert: String? = null,
    public val snippet: Snippet? = null,
    public val detail: String? = null,
    /** Shown after the label: an option's default. */
    public val tail: String? = null,
    /** Markdown. */
    public val doc: String? = null,
    /** What the typed text is matched against, where it is not the label. */
    public val filter: String? = null,
    /** What the list is ordered by, where it is not the label. */
    internal val key: String? = null,
    /** Where the replaced text ends: the end of the word the caret stands in, which is the caret itself at the end of one. */
    public val replaceTo: Int = replaceFrom,
) {
    internal fun replacingTo(end: Int): Completion =
        Completion(label, kind, replaceFrom, insert, snippet, detail, tail, doc, filter, key, end)
}

/** The items at a caret, in the order shown, and whether nothing else belongs there: a signal, an action, an option or a selector is being named. */
public class Completions internal constructor(
    public val items: List<Completion>,
    public val alone: Boolean,
)

/** What hover shows: the Markdown for the text from [start] to [end]. */
public class Hover internal constructor(
    public val start: Int,
    public val end: Int,
    public val markdown: String,
)

/** The action a caret stands in the arguments of: its signature, what it does, where each parameter stands in [label], and which is at the caret. */
public class SignatureHelp internal constructor(
    public val label: String,
    public val doc: String,
    public val parameters: List<IntRange>,
    public val active: Int,
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
    workspace: Workspace,
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
    // The parameters follow one another in the signature, after its parenthesis, as the catalog writes them.
    var from = action.signature.indexOf('(') + 1
    val parameters =
        action.parameters.map { name ->
            val at = action.signature.indexOf(name, from)
            check(at >= 0) { "The catalog's signature ${action.signature} does not name its parameter $name." }
            from = at + name.length
            at until at + name.length
        }
    return SignatureHelp(action.signature, action.doc, parameters, minOf(call.argument, maxOf(0, parameters.size - 1)))
}

private val NOTHING = Completions(emptyList(), alone = false)

/** Whether a word of [kind] goes on with this character: an attribute's name up to its modifiers, a signal's path, a name. */
private fun Char.continues(kind: CompletionKind): Boolean =
    this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9' ||
        when (kind) {
            CompletionKind.ATTRIBUTE -> this == '-' || this == ':'
            CompletionKind.EVENT, CompletionKind.ID, CompletionKind.CLASS -> this == '-' || (kind != CompletionKind.EVENT && this == '_')
            CompletionKind.MODIFIER -> false
            CompletionKind.SIGNAL -> this == '_' || this == '.'
            CompletionKind.VALUE -> this == '_' || this == '-'
            CompletionKind.ACTION, CompletionKind.OPTION, CompletionKind.SCOPE -> this == '_' || this == '$'
        }

private val DURATIONS = listOf("500ms", "1s", "300ms", "100ms")
private val SIGNAL_NAME_TYPED = regex("""\s*([\w.]*)""")
private val SIGNAL_NAME = regex("""\w+(?:[.-]\w+)*""")
private val SCOPE_WORD = regex("""(?:^|[^A-Za-z0-9_$])([a-z]*)$""")
private val SELECTOR_TOKEN = regex("""[#.]?[\w-]*$""")
private val ACTION_WORD = regex("""@[A-Za-z_][A-Za-z0-9_]*""")
private val OPTION_WORD = regex("""[A-Za-z_$][\w$]*""")
private val ATTRIBUTE_WORD = regex("""[A-Za-z][A-Za-z0-9_:.-]*""")
private val KOTLIN_WORD = regex("""[A-Za-z_][A-Za-z0-9_]*""")
private val STRING_OPENED = regex("""['"`]([\w-]*)""")

/** `evt.detail.type ===` at the end of the text, maybe with a string opened after it: what is written after the operator. */
private val FETCH_TYPE_COMPARED = regex("""(?:^|[^\w$.])evt\s*\.\s*detail\s*\.\s*type\s*(?:===?|!==?)\s*((?:['"`][\w-]*)?)$""")

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
        val found = items(workspace, attributeNames, domEvents)
        return Completions(found.items.map { it.replacingTo(wordEnd(it.kind)) }, found.alone)
    }

    /** The end of the word of [kind] that goes on after the caret: what an item of that kind replaces with what is typed before it. */
    fun wordEnd(kind: CompletionKind): Int {
        var end = offset
        while (end < text.length && text[end].continues(kind)) end++
        return end
    }

    private fun items(
        workspace: Workspace,
        attributeNames: Boolean,
        domEvents: Boolean,
    ): Completions {
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
            val call = actionCallAt(before, before.length)
            return call?.option != null || call
                ?.value
                ?.field
                ?.values
                ?.isNotEmpty() == true ||
                actionPrefixAt(before, before.length) != null || scopeWord(before).isNotEmpty()
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
        val key = analyzer.attributesIndexes(text).firstOrNull { offset in it.nameStart..it.nameStart + it.name.length } ?: return null
        return key.name.substring(0, offset - key.nameStart)
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
                items += Completion(sigil + name, kind, from, detail = detail, key = "${if (here) rank else rank + 1}$name")
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

        /** The signals defined, for the [typed] name before the caret; with [dollar], read in an expression after `$`. */
        fun signals(
            typed: String,
            dollar: String?,
        ): Completions {
            // A name written as a value defines the signal it names, which is what is being typed.
            val own = if (dollar == null) definitions() - typed else definitions()
            // Written bare, a name is offered only as the client reads one after a `$`.
            val names = (workspace.signals + own).filter { ':' !in it && (dollar != null || SIGNAL_NAME.matchEntire(it) != null) }
            val items =
                names.toSet().map { name ->
                    val here = name in own
                    Completion(
                        if (dollar == null) name else "$$name",
                        CompletionKind.SIGNAL,
                        offset - typed.length - (dollar?.length ?: 0),
                        insert = dollar?.let { it + name },
                        detail = "signal (${if (here) "this file" else workspace.called})",
                        filter = dollar?.let { it + name },
                        key = (if (here) "0" else "1") + name,
                    )
                }
            return ordered(items, alone = true)
        }
        signalPrefixAt(before, before.length)?.let { typed ->
            // The dollar is replaced too where the source writes it plainly, and left as written otherwise (\$, ${'$'}).
            return signals(typed, if (text.getOrNull(offset - typed.length - 1) == '$') "$" else "")
        }
        // A value that is a signal's name, data-bind="query": the name as written, without a dollar.
        if (!expression.allowActions) return SIGNAL_NAME_TYPED.matchEntire(before)?.let { signals(it.groupValues[1], null) } ?: NOTHING
        // A string is quoted with the quote the attribute around it leaves free, escaped where the Kotlin string needs it.
        val q = if (expression.escaped) "\\${expression.quote}" else expression.quote.toString()
        val call = actionCallAt(before, before.length)
        call?.option?.let { option ->
            // The colon is written only where none follows yet, after the rest of the name, which the item replaces.
            val colon = if (text.substring(wordEnd(CompletionKind.OPTION)).trimStart().startsWith(":")) "" else ": "
            val items =
                option.fields.map { it.option }.filter { it.name !in option.written }.map {
                    Completion(
                        it.name,
                        CompletionKind.OPTION,
                        offset - option.typed.length,
                        insert = it.name + colon,
                        detail = it.type,
                        tail = it.default.takeIf { d -> d.isNotEmpty() }?.let { d -> " = $d" },
                        doc = if (it.doc.isEmpty()) null else fetchOptionDoc(it),
                    )
                }
            return ordered(items, alone = true)
        }
        call?.value?.let { value ->
            // In a string already opened the value is written bare, and quoted otherwise, as an action's path is.
            val items =
                value.field.values.mapIndexed { n, v ->
                    val label = if (value.quoted) v else "${expression.quote}$v${expression.quote}"
                    val insert = if (value.quoted) null else "$q$v$q".takeIf { it != label }
                    Completion(label, CompletionKind.VALUE, offset - value.typed.length, insert, key = n.toString().padStart(3, '0'))
                }
            if (items.isNotEmpty() || value.quoted) return ordered(items, alone = items.isNotEmpty())
        }
        // The strings an argument takes, the types of @intl, or those datastar-fetch is dispatched with, after evt.detail.type ===.
        val strings =
            call?.takeIf { it.option == null && it.value == null && it.argumentStart >= 0 }?.let { c ->
                catalog.actionValues[c.action]?.getOrNull(c.argument)?.let { it to before.substring(c.argumentStart) }
            } ?: catalog.fetchEvents.takeIf { expression.event == DATASTAR_FETCH }?.let { types ->
                FETCH_TYPE_COMPARED.find(before)?.let { m ->
                    val written = m.groupValues[1]
                    types.takeIf { jsRegions(before)[before.length - written.length - 1] == Region.CODE }?.let { it to written }
                }
            }
        strings?.let { (values, written) -> stringItems(values, written, expression.quote, q)?.let { return it } }
        actionPrefixAt(before, before.length)?.let { typed ->
            val items =
                catalog.actions.map {
                    val call = Snip().text("@${it.name}")
                    when {
                        it.kind == "backend" -> call.text("($q").place("/path").text("$q)")
                        it.name == "peek" -> call.text("(() => ").place().text(")")
                        else -> call.text("(").place().text(")")
                    }
                    val detail = (if (it.pro) "Pro · " else "") + it.signature
                    val key = (if (it.pro) "1" else "0") + it.name
                    Completion(
                        "@${it.name}",
                        CompletionKind.ACTION,
                        offset - typed.length - 1,
                        snippet = call.done(),
                        detail = detail,
                        doc = it.doc,
                        key = key,
                    )
                }
            return ordered(items, alone = true)
        }
        val word = scopeWord(before)
        if (word.isEmpty()) return NOTHING
        // `patch` is a name only in `data-on-signal-patch`, the one attribute the client hands it to.
        val names = catalog.compiledParameters.filter { it.first() in 'a'..'z' } + listOfNotNull("patch".takeIf { expression.patchInScope })
        return ordered(names.map { Completion(it, CompletionKind.SCOPE, offset - word.length, detail = "Datastar scope") }, alone = false)
    }

    /**
     * [values] for an argument [written] up to the caret: quoted with [quote], and written as [q], where
     * nothing is written yet, and bare in a string just opened with only a name typed in it; null otherwise.
     */
    private fun stringItems(
        values: List<String>,
        written: String,
        quote: Char,
        q: String,
    ): Completions? {
        val typed = written.trimStart()
        val opened = STRING_OPENED.matchEntire(typed)
        if (typed.isNotEmpty() && opened == null) return null
        val name = opened?.groupValues?.get(1).orEmpty()
        val items =
            values.mapIndexed { n, v ->
                val label = if (opened != null) v else "$quote$v$quote"
                val insert = if (opened != null) null else "$q$v$q".takeIf { it != label }
                Completion(label, CompletionKind.VALUE, offset - name.length, insert, key = n.toString().padStart(3, '0'))
            }
        return ordered(items, alone = true)
    }

    /**
     * The signals this file defines, read with the modules it can use: what makes a signal this
     * file's. The editor is asked about none of its calls, which there may be thousands of.
     */
    private fun definitions(): Set<String> {
        val foreign = foreignHelpers(htmlDsl = !kotlin || modules.htmlDsl)
        return collectSignalDefinitions(text, source.language, { name, _ -> name in foreign }, if (kotlin) analyzer.lex(text) else null)
    }

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
                    val name = prefix + it.name
                    val snippet = Snip().text(name)
                    if (it.keyRequired) snippet.text(":").place("key")
                    // In the key of the attributes form the name stands alone: the value is Kotlin's.
                    if (tag != null && it.valueKind != ValueKind.NONE) snippet.text("=\"").place().text("\"")
                    val written = snippet.done().takeIf { s -> tag != null || s.places.isNotEmpty() }
                    val detail = (if (it.pro) "Datastar Pro · " else "Datastar · ") + it.forms.firstOrNull().orEmpty()
                    val doc = attributeDoc(it, prefix, withKotlin = true)
                    Completion(
                        name,
                        CompletionKind.ATTRIBUTE,
                        offset - typed.length,
                        snippet = written,
                        detail = detail,
                        doc = doc,
                        key =
                            (if (it.pro) "1" else "0") + it.name,
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
                // The tags written already, and the one at the caret.
                val tags = current.split('.').drop(1)
                val values =
                    when (modifier.type) {
                        ModifierType.ENUM -> modifier.values
                        ModifierType.DURATION -> if (tags.size > 1) modifier.flags else DURATIONS
                        ModifierType.IDENTS -> modifier.suggest
                        else -> if (tags.size > 1) emptyList() else modifier.suggest
                    }.filter { it !in tags.dropLast(1) }
                val from = offset - (current.length - current.lastIndexOf('.') - 1)
                return Completions(values.map { Completion(it, CompletionKind.VALUE, from) }, alone = false)
            }
            val items =
                spec.modifiers.filter { it.listed }.map {
                    val snippet =
                        when (it.type) {
                            ModifierType.FLAG -> null
                            ModifierType.DURATION -> Snip().text("${it.name}.").place("500ms")
                            ModifierType.ENUM -> Snip().text("${it.name}.").place(it.values.first(), it.values)
                            ModifierType.INT -> Snip().text("${it.name}.").place("50")
                            ModifierType.IDENT, ModifierType.IDENTS -> Snip().text("${it.name}.").place("name")
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
                        snippet = snippet?.done(),
                        detail = "__${it.name}$form",
                    )
                }
            return ordered(items, alone = false)
        }

        /** The event of `data-on:`, Datastar's own first, then the DOM's; with [value], a value is opened where none follows yet. */
        private fun events(
            typed: String,
            value: Boolean,
        ): Completions {
            val opens = value && !text.substring(offset).trimStart().startsWith("=")
            val from = offset - typed.length

            fun event(
                name: String,
                detail: String,
                doc: String?,
                rank: Int,
            ): Completion {
                val snippet = if (opens) Snip().text("$name=\"").place().text("\"") else Snip().text(name)
                return Completion(
                    name,
                    CompletionKind.EVENT,
                    from,
                    snippet =
                        snippet.done().takeIf {
                            value
                        },
                    detail = detail,
                    doc = doc,
                    key = "$rank$name",
                )
            }
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
            val foreign = kotlin && analyzer.inForeignDslCall(text, offset, modules)
            if (action != null && !foreign) return Hover(range, actionDoc(action, withKotlin))
        }
        wordAt(OPTION_WORD)?.let { range ->
            val word = text.substring(range.first, range.last + 1)
            val option = catalog.fetchOptions.firstOrNull { it.name == word }
            // The key it is when the caret stands at its end.
            val expression = option?.let { expression(range.last + 1) }?.takeIf { it.allowActions }
            val typed = expression?.let { actionCallAt(it.before, it.before.length)?.option?.typed }
            if (option != null && typed == word) return Hover(range, fetchOptionDoc(option))
        }
        expression(offset)?.let { e -> codeWord(e)?.let { return it } }
        if (!kotlin || analyzer.htmlStringAt(text, offset, modules) != null || inAttributesKey()) {
            val range = wordAt(ATTRIBUTE_WORD) ?: return null
            val parsed = catalog.parseAttributeName(text.substring(range.first, range.last + 1).lowercase(), prefix) ?: return null
            val spec = parsed.spec ?: return null
            // A modifier and its tags have documentation of their own; `__` starts one.
            val at = offset - range.first
            for (m in parsed.modifiers) {
                if (at !in m.offset - 2..m.offset + m.text.length) continue
                val modifier = spec.modifiers.firstOrNull { it.name == m.name && it.listed } ?: break
                return Hover(range.first + m.offset - 2, range.first + m.offset + m.text.length, modifierDoc(modifier, prefix + spec.name))
            }
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

    /**
     * A signal, or a name the client hands every expression, where the caret stands on it in the
     * code of expression [e]: not in a string, nor a member of something else.
     */
    private fun codeWord(e: ExpressionAtCaret): Hover? {
        val range = wordAt(CLIENT_SIGNAL) ?: wordAt(OPTION_WORD) ?: return null
        val inBefore = e.before.length - (offset - range.first)
        if (inBefore < 0 || jsRegions(e.before)[inBefore] != Region.CODE || e.before.getOrNull(inBefore - 1) == '.') return null
        val word = text.substring(range.first, range.last + 1)
        // A `$` the client reads no name after, as in `$år`, is no signal.
        if (word.startsWith("$")) {
            val name = word.substring(1)
            if (name.isEmpty() || name.first() == '$') return null
            // As the client's signalPath reads it: each part after a dot is a key of the one before.
            val parts = name.split('.')
            val path = "$" + parts.joinToString("") { "['$it']" }
            val owner = parts.dropLast(1).joinToString(".")
            val length =
                if (parts.size > 1 &&
                    parts.last() == "length"
                ) {
                    " Where $owner is an array or a string, `length` is its length."
                } else {
                    ""
                }
            return Hover(range, "**$word**\n\nDatastar reads it as `$path`, the signal path $name in its store.$length")
        }
        val doc =
            when (word) {
                "el" -> {
                    "The element the attribute is on."
                }

                "evt" -> {
                    "The event that ran the expression of ${prefix}on; undefined in the others."
                }

                "patch" -> {
                    "The signals that changed, with their new values: ${prefix}on-signal-patch hands it to its expression.".takeIf {
                        e.patchInScope
                    }
                }

                else -> {
                    null
                }
            } ?: return null
        return Hover(range, "**$word**\n\n$doc")
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

/** [items] in the order shown: by what each is ordered by, then by label. */
private fun ordered(
    items: List<Completion>,
    alone: Boolean,
): Completions = Completions(items.sortedWith(compareBy<Completion> { it.key ?: it.label }.thenBy { it.label }), alone)

/** The bare lowercase word before the end of [before], or nothing. */
private fun scopeWord(before: String): String =
    SCOPE_WORD
        .find(before)
        ?.groupValues
        ?.get(1)
        .orEmpty()

/** A [Snippet] as it is written: text, and places with what stands there. */
private class Snip {
    private val text = StringBuilder()
    private val places = ArrayList<Place>()

    fun text(s: String) = apply { text.append(s) }

    fun place(
        default: String = "",
        choices: List<String> = emptyList(),
    ) = apply {
        places += Place(text.length, text.length + default.length, choices)
        text.append(default)
    }

    fun done(): Snippet = Snippet(text.toString(), places)
}
