package io.github.markusaugust.streamlord.intellij

import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.impl.AbstractColorsScheme
import com.intellij.openapi.editor.colors.impl.EditorColorsSchemeImpl
import com.intellij.openapi.editor.markup.TextAttributes
import io.github.markusaugust.streamlord.intellij.documentation.StreamlordDocumentation
import io.github.markusaugust.streamlord.intellij.highlighting.DatastarColors
import java.awt.Color

class EditorFeaturesTest : StreamlordTestCase() {
    fun `test hover documents attributes actions and dsl functions`() {
        myFixture.configureByText("page.html", "<div data-on-intersect__once=\"@post('/x')\"></div>")
        val attr = StreamlordDocumentation.at(myFixture.file, 10)!!
        assertTrue(attr.first, attr.first.contains("<b>data-on-intersect</b>") && attr.first.contains("__threshold"))
        val action = StreamlordDocumentation.at(myFixture.file, myFixture.file.text.indexOf("@post") + 2)!!
        assertTrue(action.first, action.first.contains("@post(uri, options?)"))
        myFixture.configureByText("View.kt", "fun f() { dataOnClick(\"@post('/x')\") }\nval x = \"data-on-intersect\"")
        val fn = StreamlordDocumentation.at(myFixture.file, myFixture.file.text.indexOf("dataOnClick") + 3)!!
        assertTrue(fn.first, fn.first.contains("<b>data-on</b>"))
        assertNull(StreamlordDocumentation.at(myFixture.file, myFixture.file.text.indexOf("data-on-intersect") + 3))
        // dataOnSignalPatch also writes the filter, but its expression fills data-on-signal-patch.
        myFixture.configureByText("Feed.kt", "fun f() { dataOnSignalPatch(\"console.log(patch)\") }")
        val patch = StreamlordDocumentation.at(myFixture.file, myFixture.file.text.indexOf("dataOnSignalPatch") + 3)!!
        assertTrue(patch.first, patch.first.contains("<b>data-on-signal-patch</b>") && !patch.first.contains("data-on-signal-patch-filter"))
    }

    // A theme is a scheme built on Default or Darcula that sets the standard keys. Whatever it gives
    // a static field, a signal gets, in a scheme of each kind the IDE has.
    fun `test the theme colours the datastar tokens`() {
        val schemes = EditorColorsManager.getInstance().allSchemes
        assertTrue(schemes.isNotEmpty())
        for (scheme in schemes) {
            val parent = (scheme as? AbstractColorsScheme)?.parentScheme ?: scheme
            val theme = EditorColorsSchemeImpl(parent)
            theme.setAttributes(DefaultLanguageHighlighterColors.STATIC_FIELD, TextAttributes(Color(0x123456), null, null, null, 0))
            assertEquals(parent.name, Color(0x123456), theme.getAttributes(DatastarColors.SIGNAL)?.foregroundColor)
            assertEquals(
                parent.name,
                parent.getAttributes(DefaultLanguageHighlighterColors.STATIC_FIELD),
                parent.getAttributes(DatastarColors.SIGNAL),
            )
        }
    }

    fun `test datastar tokens are coloured in html and in kotlin strings`() {
        myFixture.configureByText("page.html", "<div data-on:click__debounce.500ms=\"\$count++; @post('/x')\"></div>")
        val html = myFixture.doHighlighting().filter { it.forcedTextAttributesKey != null }
        val keys = html.associate { it.text to it.forcedTextAttributesKey }
        assertEquals(DatastarColors.SIGNAL, keys["\$count"])
        assertEquals(DatastarColors.ACTION_BACKEND, keys["@post"])
        assertEquals(DatastarColors.ATTRIBUTE_KEY, keys["click"])
        assertEquals(DatastarColors.MODIFIER, keys["debounce"])
        assertEquals(DatastarColors.DURATION, keys["500ms"])
        myFixture.configureByText(
            "View.kt",
            "fun f() { dataText(\"\${'\$'}count + \$\") }\nfun g() = \"\"\"<p data-text=\"\${'\$'}n\"></p>\"\"\"",
        )
        val kt =
            myFixture
                .doHighlighting()
                .filter {
                    it.forcedTextAttributesKey != null
                }.associate { it.text to it.forcedTextAttributesKey }
        assertEquals(DatastarColors.KOTLIN_DOLLAR_ESCAPE, kt["\${'\$'}"])
        assertEquals(DatastarColors.SIGNAL, kt["count"])
        assertEquals(DatastarColors.ATTRIBUTE_PLUGIN, kt["text"])
        assertEquals(DatastarColors.SIGNAL, kt["n"])
    }

    fun `test only the catalog's attributes are coloured as datastar`() {
        // The page's own data-color, or a typo, is left to HTML, as in VS Code.
        val page = "<div data-color=\"red\" data-onn:click=\"x\" data-show=\"\$open\"></div>"
        myFixture.configureByText("page.html", page)
        val html = myFixture.doHighlighting().filter { it.forcedTextAttributesKey != null }.map { it.text }
        assertTrue(html.toString(), "show" in html && "color" !in html && "onn" !in html && "data-" in html)
        assertEquals(1, html.count { it == "data-" })
        myFixture.configureByText(
            "View.kt",
            "import kotlinx.html.*\nfun g() = \"\"\"<p data-color=\"red\" data-text=\"x\"></p>\"\"\"\n" +
                "fun FlowContent.f() { div { attributes[\"data-color\"] = \"red\" } }",
        )
        val kt = myFixture.doHighlighting().filter { it.forcedTextAttributesKey != null }.map { it.text }
        assertTrue(kt.toString(), "text" in kt && "color" !in kt)
    }

    fun `test html is injected into a free-standing html string`() {
        myFixture.configureByText(
            "View.kt",
            "fun side(name: String) = \"\"\"<div id=\"a\"><b>\$name</b></div>\"\"\"\nval sql = \"\"\"select * from x\"\"\"",
        )
        val manager = InjectedLanguageManager.getInstance(project)

        fun hostAt(needle: String) =
            com.intellij.psi.util.PsiTreeUtil.getParentOfType(
                myFixture.file.findElementAt(myFixture.file.text.indexOf(needle)),
                org.jetbrains.kotlin.psi.KtStringTemplateExpression::class.java,
            )!!
        val injected = manager.getInjectedPsiFiles(hostAt("<div"))
        assertNotNull(injected)
        // One injected file in two shreds, around the `${'$'}name` template.
        assertEquals(
            "HTML",
            injected!!
                .first()
                .first.language.id,
        )
        assertEquals(2, injected.size)
        assertNull(manager.getInjectedPsiFiles(hostAt("select")))
    }

    fun `test html is injected wherever the kotlin plugin injects none, and only there`() {
        myFixture.addFileToProject(
            "stubs/Language.kt",
            "package org.intellij.lang.annotations\nannotation class Language(val value: String, val prefix: String = \"\", val suffix: String = \"\")",
        )
        val src =
            """
            import org.intellij.lang.annotations.Language
            import io.github.markusaugust.streamlord.core.domain.*

            fun take(@Language("HTML", prefix = "<ul>", suffix = "</ul>") body: String) = body

            fun wrap(@Language("HTML") body: String) = body

            @Language("HTML")
            val joined = "<p>one</p>" + "<i>two</i>"

            fun wrapped(x: String) = wrap("<ul>" + x + "<li>mid</li></ul>")

            fun bracketed() = wrap(("<p>alone</p>") + "<i>sum</i>")

            fun page(name: String) = interpolate { "<p>${'$'}{+name}</p>" }

            fun hello(name: String) = interpolate { "Hello <b>${'$'}{+name}</b>" }

            fun bold() = Trusted("<b>bold</b>")

            fun items() = listOf("<li>a</li>")

            fun call() = take("<li>arg</li>")

            @Deprecated("<b>not</b> markup")
            fun old() = 1
            """.trimIndent()
        myFixture.configureByText("View.kt", src)
        val manager = InjectedLanguageManager.getInstance(project)

        fun injected(needle: String): String? {
            val host =
                com.intellij.psi.util.PsiTreeUtil.getParentOfType(
                    myFixture.file.findElementAt(src.indexOf(needle)),
                    org.jetbrains.kotlin.psi.KtStringTemplateExpression::class.java,
                )!!
            return manager
                .getInjectedPsiFiles(host)
                ?.first()
                ?.first
                ?.let { it.language.id + " " + it.text }
        }
        // A mark on another function's parameter says nothing of the literals after it.
        assertEquals("HTML <p></p>", injected("<p>\${+name"))
        assertEquals("HTML Hello <b></b>", injected("Hello"))
        // A call's argument is the Kotlin plugin's only where the parameter is marked, with what the mark adds.
        assertEquals("HTML <b>bold</b>", injected("<b>bold"))
        assertEquals("HTML <li>a</li>", injected("<li>a"))
        assertEquals("HTML <ul><li>arg</li></ul>", injected("<li>arg"))
        assertEquals(null, injected("<b>not"))
        // A sum under a mark is one injection of the Kotlin plugin's, all its literals in it.
        assertEquals("HTML <p>one</p><i>two</i>", injected("<i>two"))
        assertEquals("HTML <ul>x<li>mid</li></ul>", injected("<li>mid"))
        // A literal in parentheses is none of the sum's literals to the Kotlin plugin, which takes it for a value.
        assertEquals("HTML <p>alone</p>", injected("<p>alone"))
    }
}
