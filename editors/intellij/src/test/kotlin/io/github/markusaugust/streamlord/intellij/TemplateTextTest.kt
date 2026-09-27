package io.github.markusaugust.streamlord.intellij

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.markusaugust.streamlord.intellij.highlighting.DatastarColors
import io.github.markusaugust.streamlord.intellij.inspections.DatastarAttributeInspection
import io.github.markusaugust.streamlord.intellij.inspections.DatastarExpressionInspection
import java.io.File

/** Template files without a plugin behind them are judged as text, against the VS Code extension's own fixtures. */
class TemplateTextTest : BasePlatformTestCase() {
    private val fixtures = File(System.getProperty("streamlord.repo"), "editors/vscode/test/fixtures")

    override fun setUp() {
        super.setUp()
        myFixture.enableInspections(DatastarAttributeInspection(), DatastarExpressionInspection())
    }

    private fun descriptions(name: String): List<String> {
        myFixture.configureByText(name, File(fixtures, name).readText())
        return myFixture
            .doHighlighting()
            .mapNotNull { it.description }
            .filter {
                !it.startsWith(
                    "Typo",
                )
            }.map { it.substringBefore(" See ") }
    }

    fun `test jte freemarker velocity and mustache fixtures as plain text`() {
        assertEquals(
            listOf("Unknown modifier __debunce on data-on. Did you mean __debounce?", "Datastar expression: Unexpected end of input"),
            descriptions("template.jte").sorted().reversed(),
        )
        assertEquals(
            listOf(
                "Unknown Datastar attribute data-signal. Did you mean data-signals?",
                "data-on needs a key, e.g. data-on:<event>=\"expr\".",
            ),
            descriptions("template.ftl"),
        )
        assertEquals(listOf("__threshold needs an integer between 0 and 100, e.g. __threshold.50."), descriptions("template.vm"))
        assertEquals(
            listOf("data-persist is a Datastar Pro attribute; it needs the Pro bundle.", "Datastar expression: Unexpected end of input"),
            descriptions("template.mustache"),
        )
    }

    fun `test the template text is coloured and completed`() {
        myFixture.configureByText("page.jte", "<div data-on:click__debounce.500ms=\"@post('/x')\"></div>")
        val keys =
            myFixture.doHighlighting().filter { it.forcedTextAttributesKey != null }.associate {
                it.text to
                    it.forcedTextAttributesKey
            }
        assertEquals(DatastarColors.ACTION_BACKEND, keys["@post"])
        assertEquals(DatastarColors.MODIFIER, keys["debounce"])
        myFixture.configureByText("page2.jte", "<div data-<caret>")
        val items = myFixture.completeBasic()!!.map { it.lookupString }
        assertTrue(items.toString(), "data-on" in items)
    }

    fun `test an unknown extension is left alone`() {
        myFixture.configureByText("notes.txt", "<div data-onn:x=\"1\"></div>")
        assertEquals(emptyList<String>(), myFixture.doHighlighting().mapNotNull { it.description }.filter { !it.startsWith("Typo") })
    }
}
