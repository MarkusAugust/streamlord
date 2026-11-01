package io.github.markusaugust.streamlord.intellij

import com.intellij.codeInsight.daemon.impl.analysis.XmlUnboundNsPrefixInspection
import com.intellij.codeInspection.LanguageInspectionSuppressors
import com.intellij.lang.html.HTMLLanguage
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.markusaugust.streamlord.intellij.inspections.DatastarAttributeInspection
import io.github.markusaugust.streamlord.intellij.settings.StreamlordSettings

/**
 * IntelliJ reads the colon in `data-on:click` as a namespace prefix, and its XmlUnboundNsPrefix
 * inspection, on by default, warns that `data-on` is not bound. In HTML that is no namespace.
 */
class NamespaceSuppressorTest : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        myFixture.enableInspections(XmlUnboundNsPrefixInspection())
    }

    private fun unbound(
        name: String,
        text: String,
    ): List<String> {
        myFixture.configureByText(name, text)
        return myFixture.doHighlighting().mapNotNull { it.description }.filter { "is not bound" in it }
    }

    fun `test a datastar key in html is no namespace`() {
        val found =
            unbound(
                "page.html",
                """<div data-signals:foo="1" data-on:click__debounce.500ms="${'$'}foo++" data-class:hidden="${'$'}foo"
                  |     DATA-ON:keydown="${'$'}foo--" data-bind:query></div>
                """.trimMargin(),
            )
        assertEmpty(found)
    }

    fun `test a prefix that is no datastar plugin is still reported`() {
        assertEquals(
            listOf("Namespace 'data-nothing' is not bound", "Namespace 'foo' is not bound", "Namespace 'on' is not bound"),
            unbound("page.html", """<div data-nothing:x="1" on:click="x" data-on:my:event="x"><a foo:bar="a"></a></div>""").sorted(),
        )
    }

    // The namespace warning would say nothing true about a key Datastar does not read; Streamlord's own check does.
    fun `test a key datastar does not read is reported by the attribute check`() {
        myFixture.enableInspections(DatastarAttributeInspection())
        myFixture.configureByText("page.html", """<div data-text:x="1"></div><div data-on__window:click="x"></div>""")
        val found = myFixture.doHighlighting().mapNotNull { it.description }
        assertEmpty(found.filter { "is not bound" in it })
        assertContainsElements(found, "data-text does not take a key. See https://data-star.dev/reference/attributes#data-text")
        assertTrue(found.toString(), found.any { it.startsWith("data-on needs a key") })
    }

    fun `test the aliased prefix follows the setting`() {
        val state = StreamlordSettings.getInstance(project).state
        val before = state.attributePrefix
        try {
            state.attributePrefix = "data-star-"
            assertEquals(
                listOf("Namespace 'data-on' is not bound"),
                unbound("page.html", """<div data-star-on:click="${'$'}n++" data-on:click="${'$'}n++"></div>"""),
            )
        } finally {
            state.attributePrefix = before
        }
    }

    // The platform does not run this inspection on injected HTML today; should it start to, the suppressor covers it.
    fun `test html injected into a kotlin string is no namespace either`() {
        assertEmpty(unbound("View.kt", "fun page() = \"\"\"<div data-on:click=\"\${'$'}n++\" data-signals:n=\"0\"></div>\"\"\""))
        val injected =
            InjectedLanguageManager
                .getInstance(project)
                .findInjectedElementAt(myFixture.file, myFixture.file.text.indexOf("data-on"))
        assertNotNull(injected)
        assertTrue(injected!!.containingFile.language.isKindOf(HTMLLanguage.INSTANCE))
        val suppressors = LanguageInspectionSuppressors.INSTANCE.allForLanguage(HTMLLanguage.INSTANCE)
        assertTrue(suppressors.any { it.isSuppressedFor(injected, "XmlUnboundNsPrefix") })
    }

    fun `test a plain xml file keeps the warning`() {
        assertEquals(
            listOf("Namespace 'data-on' is not bound"),
            unbound("data.xml", """<root><div data-on:click="x"/></root>""").filter { "data-on" in it },
        )
    }

    // A parser that reads the page as XML stops at an unbound prefix, so there the warning is true.
    fun `test xhtml keeps the warning`() {
        assertEquals(
            listOf("Namespace 'data-on' is not bound"),
            unbound(
                "page.xhtml",
                """<html xmlns="http://www.w3.org/1999/xhtml"><body><div data-on:click="${'$'}n++"></div></body></html>""",
            ),
        )
    }
}
