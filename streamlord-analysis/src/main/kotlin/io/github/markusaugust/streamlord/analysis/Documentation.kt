package io.github.markusaugust.streamlord.analysis

import io.github.markusaugust.streamlord.core.catalog.CatalogNames
import io.github.markusaugust.streamlord.core.client.regex

/*
 * The documentation the editors show on hover and in completion, as Markdown. One wording for
 * VS Code and IntelliJ.
 */

/** The `__name.arg` form a modifier is written in, for documentation. */
internal fun modifierForm(m: Modifier): String =
    "__" + m.name +
        when (m.type) {
            ModifierType.FLAG -> ""
            ModifierType.ENUM -> "." + m.values.joinToString("|")
            ModifierType.DURATION -> ".500ms"
            ModifierType.INT -> ".n"
            ModifierType.IDENT, ModifierType.IDENTS -> ".name"
        }

/**
 * Markdown documentation of modifier [m] of the attribute named [attribute]: what it does, the
 * tags it takes after a dot, and that the flags of a duration come after it.
 */
internal fun modifierDoc(
    m: Modifier,
    attribute: String,
): String {
    val example = m.suggest.firstOrNull()
    val value =
        when (m.type) {
            ModifierType.FLAG -> {
                null
            }

            ModifierType.DURATION -> {
                "`.500ms`: the duration, in milliseconds, or `.1s` in seconds."
            }

            ModifierType.ENUM -> {
                "one of " + m.values.joinToString(", ") { "`.$it`" } + "."
            }

            ModifierType.INT -> {
                "`.${example ?: "n"}`: a whole number" +
                    (if (m.min != null && m.max != null) " from ${m.min} to ${m.max}." else ".")
            }

            ModifierType.IDENT -> {
                "`.${example ?: "name"}`: one name."
            }

            ModifierType.IDENTS -> {
                "`.${example ?: "name"}`: one or more names, each after a dot."
            }
        }
    val tags = listOfNotNull(value) + m.flags.map { "`.$it`: ${m.flagDocs[it].orEmpty()}" }
    val flags = m.flags.joinToString(" and ") { "`.$it`" }
    return "**__${m.name}** on $attribute" +
        (if (m.doc.isEmpty()) "" else "\n\n${m.doc}") +
        (if (tags.isEmpty()) "" else "\n\n**Tags:**  \n" + tags.joinToString("  \n")) +
        (if (m.flags.isEmpty()) "" else "\n\n$flags ${if (m.flags.size == 1) "comes" else "come"} after the duration.")
}

/** The browser lowercases attribute names; how Datastar reads the key differs per attribute. */
internal fun casingNote(a: AttributeSpec): String =
    when (keyKind(a)) {
        KeyKind.SIGNAL -> {
            "the browser lowercases attribute names, and Datastar reads the key as camelCase: `foo-bar` is the signal `\$fooBar`. " +
                "Write keys in kebab-case; `__case` changes the conversion."
        }

        KeyKind.EVENT -> {
            "the key is the event name, lowercased by the browser. For a camelCase event write `widget-loaded__case.camel`."
        }

        KeyKind.CLASS -> {
            "the key is the class name, lowercased by the browser. For a camelCase class write `is-open__case.camel`."
        }

        KeyKind.RAW -> {
            "the key is used as written, after the browser has lowercased it. Write it in kebab-case; " +
                "for a name that really has capitals (SVG's `viewBox`), use the object form."
        }
    }

/**
 * Markdown documentation of an attribute: forms, casing rule, modifiers, Kotlin helpers and the Pro
 * note. [withKotlin] leaves the helpers out, for a project without streamlord-html.
 */
internal fun attributeDoc(
    a: AttributeSpec,
    prefix: String = CatalogNames.PREFIX,
    withKotlin: Boolean = true,
): String {
    val listed = a.modifiers.filter { it.listed }
    val mods = if (listed.isNotEmpty()) "\n\n**Modifiers:** " + listed.joinToString(", ") { "`${modifierForm(it)}`" } else ""
    val kotlin = if (withKotlin && a.kotlin.isNotEmpty()) "\n\n**Kotlin:** " + a.kotlin.joinToString(", ") { "`$it()`" } else ""
    val pro = if (a.pro) "\n\n_Datastar Pro. Requires the Pro bundle, which you license and load yourself._" else ""
    val casing = if (a.keyed) "\n\n**Key casing:** " + casingNote(a) else ""
    val forms = a.forms.joinToString("  \n") { "`" + it.replaceFirst("data-", prefix) + "`" }
    return "**$prefix${a.name}**\n\n${a.doc}\n\n$forms$casing$mods$kotlin$pro"
}

/** Markdown documentation of an option of the backend actions, or a key of a signal filter: its type, its default where it has one and what it does. */
internal fun fetchOptionDoc(o: FetchOption): String =
    "**${o.name}**: `${o.type}`" + (if (o.default.isEmpty()) "" else ", default `${o.default}`") + "\n\n${o.doc}"

/** Markdown documentation of [value], one of the strings option [o] takes: what the option does with each. */
internal fun optionValueDoc(
    o: FetchOption,
    value: String,
): String = "**'$value'**: a value of ${o.name}\n\n${o.doc}"

/** Markdown documentation of [type], a type `datastar-fetch` is dispatched with: what it says, and what the event is. */
internal fun fetchTypeDoc(
    type: String,
    catalog: Catalog,
): String =
    "**'$type'**: a type of $DATASTAR_FETCH\n\n${catalog.fetchEventDocs[type].orEmpty()}\n\n" +
        catalog.datastarEvents.first { it.name == DATASTAR_FETCH }.doc

/**
 * Markdown documentation of [helper], the DSL's call that writes action [a] with some of its
 * arguments given as JavaScript expressions, as [spec] names them: by place, and the parameters after it by name.
 */
internal fun expressionHelperDoc(
    helper: String,
    a: ActionSpec,
    spec: CallSiteSpec,
    catalog: Catalog,
): String {
    val given = listOfNotNull(spec.arg?.let { a.parameters.getOrNull(it) }) + a.parameters.filter { it.removeSuffix("?") in spec.more }
    val listed = given.map { "`$it`" }.let { if (it.size > 1) it.dropLast(1).joinToString(", ") + " and " + it.last() else it.joinToString() }
    val what = if (given.size > 1) "JavaScript expressions" else "a JavaScript expression"
    return actionDoc(a, withKotlin = false, catalog = catalog) + "\n\n`$helper` writes it with $listed given as $what."
}

/**
 * Markdown documentation of an action: its signature, each parameter with the kind the catalog
 * gives it and what it is, the link to the reference, and the Kotlin helper when asked for.
 */
internal fun actionDoc(
    a: ActionSpec,
    withKotlin: Boolean = true,
    catalog: Catalog = Catalog.default,
): String {
    val parameters = parameterDocs(a, catalog).mapIndexed { n, doc -> "`${a.parameters[n]}`: $doc" }
    val pro = if (a.pro) "\n\n_Datastar Pro._" else ""
    val kotlin = if (withKotlin) "\n\nKotlin: `${a.kotlin}()`" else ""
    return "**${a.signature}**\n\n${a.doc}\n\n${parameters.joinToString(
        "  \n",
    )}$pro\n\n[Reference](${Docs.ACTIONS}#${a.name.lowercase()})$kotlin"
}

/** What each parameter of [a] is, in order: the kind the catalog gives it, and what it is said to be. */
internal fun parameterDocs(
    a: ActionSpec,
    catalog: Catalog,
): List<String> {
    val kinds = catalog.actionKinds[a.name].orEmpty()
    val docs = catalog.parameterDocs[a.name].orEmpty()
    return a.parameters.indices.map { n ->
        val kind = kinds.getOrNull(n)?.takeIf { it != ArgumentKind.ANY }?.phrase()?.let { it.replaceFirstChar(Char::uppercaseChar) + ". " }
        kind.orEmpty() + docs.getOrNull(n).orEmpty()
    }
}

/** The Markdown as plain HTML for editors that render HTML: bold, code, italics, links and paragraphs only, which is all the docs use. */
public fun markdownToHtml(md: String): String {
    val escaped = md.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    // Code spans and links are set aside first, so the underscores and asterisks inside them stay literal.
    val codes = ArrayList<String>()
    val withoutCode =
        regex("""`([^`]+)`|\[([^\]]+)]\(([^)\s]+)\)""").replace(escaped) {
            val (code, text, url) = it.destructured
            codes += if (it.value.startsWith("`")) "<code>$code</code>" else "<a href=\"$url\">$text</a>"
            "\u0000${codes.size - 1}\u0000"
        }
    val bold = regex("""\*\*(.+?)\*\*""").replace(withoutCode) { "<b>${it.groupValues[1]}</b>" }
    val italic = regex("""(?<![A-Za-z0-9_])_([^_\n]+)_(?![A-Za-z0-9_])""").replace(bold) { "<i>${it.groupValues[1]}</i>" }
    val restored = regex("""\u0000(\d+)\u0000""").replace(italic) { codes[it.groupValues[1].toInt()] }
    return restored
        .split("\n\n")
        .map { "<p>" + it.replace("  \n", "<br/>").replace("\n", " ") + "</p>" }
        .joinToString("")
}
