package io.github.markusaugust.streamlord.analysis

/*
 * The documentation the editors show on hover and in completion, as Markdown. One wording for
 * VS Code and IntelliJ.
 */

/** The `__name.arg` form a modifier is written in, for documentation. */
public fun modifierForm(m: Modifier): String =
    "__" + m.name +
        when (m.type) {
            ModifierType.FLAG -> ""
            ModifierType.ENUM -> "." + m.values.joinToString("|")
            ModifierType.DURATION -> ".500ms"
            ModifierType.INT -> ".n"
            ModifierType.IDENT, ModifierType.IDENTS -> ".name"
        }

/** The browser lowercases attribute names; how Datastar reads the key differs per attribute. */
public fun casingNote(a: AttributeSpec): String =
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

/** Markdown documentation of an attribute: forms, casing rule, modifiers, Kotlin helpers and the Pro note. */
public fun attributeDoc(
    a: AttributeSpec,
    prefix: String = "data-",
): String {
    val mods = if (a.modifiers.isNotEmpty()) "\n\n**Modifiers:** " + a.modifiers.joinToString(", ") { "`${modifierForm(it)}`" } else ""
    val kotlin = if (a.kotlin.isNotEmpty()) "\n\n**Kotlin:** " + a.kotlin.joinToString(", ") { "`$it()`" } else ""
    val pro = if (a.pro) "\n\n_Datastar Pro. Requires the Pro bundle, which you license and load yourself._" else ""
    val casing = if (a.keyed) "\n\n**Key casing:** " + casingNote(a) else ""
    val forms = a.forms.joinToString("  \n") { "`" + it.replaceFirst("data-", prefix) + "`" }
    return "**$prefix${a.name}**\n\n${a.doc}\n\n$forms$casing$mods$kotlin$pro"
}

/** Markdown documentation of an action, with its signature and the Kotlin helper when asked for. */
public fun actionDoc(
    a: ActionSpec,
    withKotlin: Boolean = true,
): String {
    val pro = if (a.pro) "\n\n_Datastar Pro._" else ""
    val kotlin = if (withKotlin) "\n\nKotlin: `${a.kotlin}()`" else ""
    return "**${a.signature}**\n\n${a.doc}$pro$kotlin"
}

/** The Markdown as plain HTML for editors that render HTML: bold, code, italics and paragraphs only, which is all the docs use. */
public fun markdownToHtml(md: String): String {
    val escaped = md.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    // Code spans are set aside first, so the underscores and asterisks inside them stay literal.
    val codes = ArrayList<String>()
    val withoutCode =
        Regex("""`([^`]+)`""").replace(escaped) {
            codes += it.groupValues[1]
            "\u0000${codes.size - 1}\u0000"
        }
    val bold = Regex("""\*\*(.+?)\*\*""").replace(withoutCode) { "<b>${it.groupValues[1]}</b>" }
    val italic = Regex("""(?<![A-Za-z0-9_])_([^_\n]+)_(?![A-Za-z0-9_])""").replace(bold) { "<i>${it.groupValues[1]}</i>" }
    val restored = Regex("""\u0000(\d+)\u0000""").replace(italic) { "<code>${codes[it.groupValues[1].toInt()]}</code>" }
    return restored
        .split("\n\n")
        .map { "<p>" + it.replace("  \n", "<br/>").replace("\n", " ") + "</p>" }
        .joinToString("")
}
