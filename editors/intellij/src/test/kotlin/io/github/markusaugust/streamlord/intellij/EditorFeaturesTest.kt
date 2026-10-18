package io.github.markusaugust.streamlord.intellij

import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.impl.AbstractColorsScheme
import com.intellij.openapi.editor.colors.impl.EditorColorsSchemeImpl
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.markusaugust.streamlord.intellij.documentation.StreamlordDocumentationProvider
import io.github.markusaugust.streamlord.intellij.highlighting.DatastarColors
import java.awt.Color

class EditorFeaturesTest : BasePlatformTestCase() {
    fun `test hover documents attributes actions and dsl functions`() {
        val provider = StreamlordDocumentationProvider()
        myFixture.configureByText("page.html", "<div data-on-intersect__once=\"@post('/x')\"></div>")
        val attr = provider.documentationAt(myFixture.file, 10)!!
        assertTrue(attr.first, attr.first.contains("<b>data-on-intersect</b>") && attr.first.contains("__threshold"))
        val action = provider.documentationAt(myFixture.file, myFixture.file.text.indexOf("@post") + 2)!!
        assertTrue(action.first, action.first.contains("@post(uri, options?)"))
        myFixture.configureByText("View.kt", "fun f() { dataOnClick(\"@post('/x')\") }\nval x = \"data-on-intersect\"")
        val fn = provider.documentationAt(myFixture.file, myFixture.file.text.indexOf("dataOnClick") + 3)!!
        assertTrue(fn.first, fn.first.contains("<b>data-on</b>"))
        assertNull(provider.documentationAt(myFixture.file, myFixture.file.text.indexOf("data-on-intersect") + 3))
        // dataOnSignalPatch also writes the filter, but its expression fills data-on-signal-patch.
        myFixture.configureByText("Feed.kt", "fun f() { dataOnSignalPatch(\"console.log(patch)\") }")
        val patch = provider.documentationAt(myFixture.file, myFixture.file.text.indexOf("dataOnSignalPatch") + 3)!!
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
        assertEquals(DatastarColors.MODIFIER_ARG, keys["500ms"])
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
}
