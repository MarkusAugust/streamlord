package io.github.markusaugust.streamlord.intellij

import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.TextAttributesKey
import io.github.markusaugust.streamlord.intellij.highlighting.DatastarColors

/**
 * Every part of Datastar has the key the VS Code grammar gives the same kind of scope, in each of
 * the three ways of writing markup, and every key falls back to one of the user's scheme.
 */
class HighlightingTest : StreamlordTestCase() {
    /** The text and key of every colour the plugin paints, in the order of the file. */
    private fun painted(): List<Pair<String, TextAttributesKey>> =
        myFixture
            .doHighlighting()
            .filter { it.forcedTextAttributesKey?.externalName?.startsWith("STREAMLORD_") == true }
            .sortedBy { it.startOffset }
            .map { it.text to it.forcedTextAttributesKey!! }

    private fun painted(
        name: String,
        text: String,
    ): List<Pair<String, TextAttributesKey>> {
        myFixture.configureByText(name, text)
        return painted()
    }

    fun `test each key falls back to the standard key of its kind`() {
        val expected =
            mapOf(
                DatastarColors.SIGNAL to DefaultLanguageHighlighterColors.STATIC_FIELD,
                DatastarColors.SIGNAL_PATH to DefaultLanguageHighlighterColors.INSTANCE_FIELD,
                DatastarColors.ACTION_BACKEND to DefaultLanguageHighlighterColors.FUNCTION_DECLARATION,
                DatastarColors.ACTION to DefaultLanguageHighlighterColors.FUNCTION_DECLARATION,
                DatastarColors.SCOPE_VARIABLE to DefaultLanguageHighlighterColors.KEYWORD,
                DatastarColors.OPTION_KEY to DefaultLanguageHighlighterColors.INSTANCE_FIELD,
                DatastarColors.OBJECT_KEY to DefaultLanguageHighlighterColors.INSTANCE_FIELD,
                DatastarColors.PROPERTY to DefaultLanguageHighlighterColors.INSTANCE_FIELD,
                DatastarColors.METHOD to DefaultLanguageHighlighterColors.FUNCTION_CALL,
                DatastarColors.GLOBAL to DefaultLanguageHighlighterColors.GLOBAL_VARIABLE,
                DatastarColors.IDENTIFIER to DefaultLanguageHighlighterColors.IDENTIFIER,
                DatastarColors.OPERATOR to DefaultLanguageHighlighterColors.OPERATION_SIGN,
                DatastarColors.COMMENT to DefaultLanguageHighlighterColors.BLOCK_COMMENT,
                DatastarColors.TEMPLATE_PUNCTUATION to DefaultLanguageHighlighterColors.KEYWORD,
                DatastarColors.DURATION to DefaultLanguageHighlighterColors.NUMBER,
                DatastarColors.STRING to DefaultLanguageHighlighterColors.STRING,
                DatastarColors.REGEX to DefaultLanguageHighlighterColors.VALID_STRING_ESCAPE,
                DatastarColors.KEYWORD to DefaultLanguageHighlighterColors.KEYWORD,
                DatastarColors.NUMBER to DefaultLanguageHighlighterColors.NUMBER,
                DatastarColors.KOTLIN_DOLLAR_ESCAPE to DefaultLanguageHighlighterColors.VALID_STRING_ESCAPE,
                DatastarColors.ATTRIBUTE_PREFIX to DefaultLanguageHighlighterColors.MARKUP_ATTRIBUTE,
                DatastarColors.ATTRIBUTE_PLUGIN to DefaultLanguageHighlighterColors.MARKUP_ATTRIBUTE,
                DatastarColors.ATTRIBUTE_KEY to DefaultLanguageHighlighterColors.MARKUP_ATTRIBUTE,
                DatastarColors.MODIFIER to DefaultLanguageHighlighterColors.KEYWORD,
                DatastarColors.MODIFIER_SIGIL to DefaultLanguageHighlighterColors.MARKUP_ATTRIBUTE,
                DatastarColors.MODIFIER_ARG to DefaultLanguageHighlighterColors.CONSTANT,
            )
        for ((key, fallback) in expected) assertEquals(key.externalName, fallback, key.fallbackAttributeKey)
        assertEquals(expected.keys, DatastarColors.ALL.toSet())
    }

    // An identifier, a call and an operator are what the scheme makes of them, plain text in both
    // of IntelliJ's own; everything Datastar adds is coloured there.
    fun `test default and darcula colour every datastar key`() {
        val plain = setOf(DatastarColors.IDENTIFIER, DatastarColors.METHOD, DatastarColors.OPERATOR)
        val manager = EditorColorsManager.getInstance()
        for (name in listOf("Default", "Darcula")) {
            val scheme = manager.getScheme(name) ?: manager.allSchemes.first { it.name.contains(name) }
            for (key in DatastarColors.ALL - plain) {
                assertNotNull("${key.externalName} in $name", scheme.getAttributes(key)?.foregroundColor)
            }
        }
    }

    fun `test every part of an expression in html`() {
        val page =
            "<div data-on:click__debounce.500ms__case.kebab=\"foo = {a: Math.max(x.length, .5, 10n)}; " +
                "@peek(() => \$user.name); `t\${\$n}` /* c */ ~ 1_000\"></div>"
        assertEquals(
            listOf(
                "data-" to DatastarColors.ATTRIBUTE_PREFIX,
                "on" to DatastarColors.ATTRIBUTE_PLUGIN,
                ":" to DatastarColors.MODIFIER_SIGIL,
                "click" to DatastarColors.ATTRIBUTE_KEY,
                "__" to DatastarColors.MODIFIER_SIGIL,
                "debounce" to DatastarColors.MODIFIER,
                "." to DatastarColors.MODIFIER_SIGIL,
                "500ms" to DatastarColors.DURATION,
                "__" to DatastarColors.MODIFIER_SIGIL,
                "case" to DatastarColors.MODIFIER,
                "." to DatastarColors.MODIFIER_SIGIL,
                "kebab" to DatastarColors.MODIFIER_ARG,
                "foo" to DatastarColors.IDENTIFIER,
                "=" to DatastarColors.OPERATOR,
                "a" to DatastarColors.OBJECT_KEY,
                ":" to DatastarColors.OPERATOR,
                "Math" to DatastarColors.GLOBAL,
                "max" to DatastarColors.METHOD,
                "x" to DatastarColors.IDENTIFIER,
                "length" to DatastarColors.PROPERTY,
                ".5" to DatastarColors.NUMBER,
                "10n" to DatastarColors.NUMBER,
                "@peek" to DatastarColors.ACTION,
                "=>" to DatastarColors.KEYWORD,
                "\$user" to DatastarColors.SIGNAL,
                ".name" to DatastarColors.SIGNAL_PATH,
                "`t" to DatastarColors.STRING,
                "\${" to DatastarColors.TEMPLATE_PUNCTUATION,
                "\$n" to DatastarColors.SIGNAL,
                "}" to DatastarColors.TEMPLATE_PUNCTUATION,
                "`" to DatastarColors.STRING,
                "/* c */" to DatastarColors.COMMENT,
                "~" to DatastarColors.OPERATOR,
                "1_000" to DatastarColors.NUMBER,
            ),
            painted("page.html", page),
        )
    }

    fun `test the aliased prefix, a name in capitals, and an entity quote`() {
        assertEquals(
            listOf(
                "data-star-" to DatastarColors.ATTRIBUTE_PREFIX,
                "show" to DatastarColors.ATTRIBUTE_PLUGIN,
                "\$a" to DatastarColors.SIGNAL,
                "DATA-" to DatastarColors.ATTRIBUTE_PREFIX,
                "TEXT" to DatastarColors.ATTRIBUTE_PLUGIN,
                "&quot;x&quot;" to DatastarColors.STRING,
            ),
            painted("page.html", "<div data-star-show=\"\$a\" DATA-TEXT=\"&quot;x&quot;\"></div>"),
        )
    }

    fun `test a signal name and a filter are read as the client reads them`() {
        assertEquals(
            listOf(
                "data-" to DatastarColors.ATTRIBUTE_PREFIX,
                "bind" to DatastarColors.ATTRIBUTE_PLUGIN,
                "user" to DatastarColors.SIGNAL,
                ".name" to DatastarColors.SIGNAL_PATH,
                "data-" to DatastarColors.ATTRIBUTE_PREFIX,
                "indicator" to DatastarColors.ATTRIBUTE_PLUGIN,
                "busy" to DatastarColors.SIGNAL,
                "data-" to DatastarColors.ATTRIBUTE_PREFIX,
                "ref" to DatastarColors.ATTRIBUTE_PLUGIN,
                "box" to DatastarColors.SIGNAL,
                "data-" to DatastarColors.ATTRIBUTE_PREFIX,
                "persist" to DatastarColors.ATTRIBUTE_PLUGIN,
                "include" to DatastarColors.OPTION_KEY,
                ":" to DatastarColors.OPERATOR,
                "/^a/" to DatastarColors.REGEX,
            ),
            painted(
                "page.html",
                "<input data-bind=\"user.name\" data-indicator=\"busy\" data-ref=\"box\" data-persist=\"{include: /^a/}\">",
            ),
        )
    }

    fun `test a kotlin template is kotlin's, and an escaped dollar is a signal`() {
        val kt =
            painted(
                "View.kt",
                "fun FlowContent.f(n: Int, id: Int) {\n" +
                    "    div { dataText(\"\$n + \\\$total\") }\n" +
                    "    div { dataShow(\$\$\"\$open && \$\${id} > 0\") }\n" +
                    "}\n" +
                    "fun g(n: Int) = \"\"\"<p data-text=\"\$n + \${'\$'}m\"></p>\"\"\"",
            )
        assertEquals(
            listOf(
                "+" to DatastarColors.OPERATOR,
                "\$total" to DatastarColors.SIGNAL,
                "\$open" to DatastarColors.SIGNAL,
                "&&" to DatastarColors.OPERATOR,
                ">" to DatastarColors.OPERATOR,
                "0" to DatastarColors.NUMBER,
                "data-" to DatastarColors.ATTRIBUTE_PREFIX,
                "text" to DatastarColors.ATTRIBUTE_PLUGIN,
                "+" to DatastarColors.OPERATOR,
                "\${'\$'}" to DatastarColors.KOTLIN_DOLLAR_ESCAPE,
                "m" to DatastarColors.SIGNAL,
            ),
            kt,
        )
    }

    fun `test the dsl's signal names, one-argument objects and named expressions`() {
        val kt =
            painted(
                "View.kt",
                "fun FlowContent.f() {\n" +
                    "    input { dataBind(\"name\") }\n" +
                    "    div { dataSignals(\"count\", \"0\") }\n" +
                    "    div { dataClass(\"{hidden: \\\$a}\") }\n" +
                    "    div { dataOnClick(expression = \"\\\$b++\") }\n" +
                    "}",
            )
        assertEquals(
            listOf(
                "name" to DatastarColors.SIGNAL,
                "count" to DatastarColors.SIGNAL,
                "0" to DatastarColors.NUMBER,
                "hidden" to DatastarColors.OBJECT_KEY,
                ":" to DatastarColors.OPERATOR,
                "\$a" to DatastarColors.SIGNAL,
                "\$b" to DatastarColors.SIGNAL,
                "++" to DatastarColors.OPERATOR,
            ),
            kt,
        )
    }

    fun `test a template file colours only datastar's names, and around a placeholder`() {
        for (ext in listOf("jte", "peb", "mustache", "hbs")) {
            assertEquals(
                ext,
                listOf(
                    "data-" to DatastarColors.ATTRIBUTE_PREFIX,
                    "text" to DatastarColors.ATTRIBUTE_PLUGIN,
                    "+" to DatastarColors.OPERATOR,
                    "\$count" to DatastarColors.SIGNAL,
                ),
                painted("page.$ext", "<div data-color=\"red\" data-onn:click=\"x\" data-text=\"{{ n }} + \$count\"></div>"),
            )
        }
        assertEquals(
            listOf(
                "data-" to DatastarColors.ATTRIBUTE_PREFIX,
                "text" to DatastarColors.ATTRIBUTE_PLUGIN,
                "+" to DatastarColors.OPERATOR,
                "\$count" to DatastarColors.SIGNAL,
            ),
            painted("page.html", "<div data-text=\"\${user.name} + \$count\"></div>"),
        )
    }
}
