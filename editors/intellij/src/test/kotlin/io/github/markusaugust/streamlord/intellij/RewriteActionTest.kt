package io.github.markusaugust.streamlord.intellij

import com.intellij.codeInsight.intention.HighPriorityAction
import com.intellij.codeInspection.ex.QuickFixWrapper
import io.github.markusaugust.streamlord.intellij.inspections.KotlinInterpolationInspection
import io.github.markusaugust.streamlord.intellij.inspections.ReplaceTextFix

private const val Q = "\"\"\""
private const val IMPORTS =
    "import io.github.markusaugust.streamlord.core.domain.Trusted\n" +
        "import io.github.markusaugust.streamlord.core.domain.interpolate\n\n" +
        "class LayoutContext(val pageId: String, val sectionId: String, val streamId: String)\n\n"
private const val SIGNATURE = "fun pageHtml(k: LayoutContext, pageStyle: String? = null, mainHtml: String): String =\n    "

/** The literal of the case that found the defects, with each value written as given. */
private fun widget(
    root: String,
    css: String,
    content: String,
    body: String,
    events: String,
): String =
    "$Q\n    <div id=\"$root\" data-theme=\"light\" data-ui-boundary=\"\">\n        <style>$css</style>\n" +
        "        <div id=\"$content\">\n            $body\n        </div>\n        <div id=\"$events\" hidden></div>\n    </div>\n    $Q.trimIndent()"

private const val CSS = "\${pageStyle.orEmpty()}"

/** The widget with each value marked where it stands, the CSS the author vouches for among them. */
private val MARKED = widget("\${+k.pageId}", "\${+Trusted(pageStyle.orEmpty())}", "\${+k.sectionId}", "\${+mainHtml}", "\${+k.streamId}")

/**
 * The rewrite to `interpolate` as the author reaches it: from the caret in each value of a literal
 * with a trim call and a value in a style, and as a context action anywhere in an HTML string
 * whose values interpolate keeps, where no warning stands.
 */
class RewriteActionTest : StreamlordTestCase() {
    override fun setUp() {
        super.setUp()
        myFixture.enableInspections(KotlinInterpolationInspection())
    }

    fun `test the rewrite is offered from each value and leaves one call with the style's value as Trusted`() {
        val values = listOf("\${k.pageId}", CSS, "\${k.sectionId}", "\$mainHtml", "\${k.streamId}")
        val source = IMPORTS + SIGNATURE + widget(values[0], values[1], values[2], values[3], values[4]) + "\n"
        val rewritten =
            IMPORTS + SIGNATURE + "interpolate { " + MARKED + " }.text\n"
        // One edit, one title, from whichever value it is offered.
        val title = "Mark the values with + in interpolate { }, with $CSS as Trusted(...)"
        for (value in values) {
            // The caret one character into the value; a fresh file each time, since the last one was rewritten.
            val at = source.indexOf(value) + 1
            myFixture.configureByText("Widget${values.indexOf(value)}.kt", source.substring(0, at) + "<caret>" + source.substring(at))
            val offered = myFixture.availableIntentions
            val action = offered.firstOrNull { it.text == title }
            assertNotNull("$value: $title is not among ${offered.map { it.text }}", action)
            // The warning's quick fix is the rewrite there, so the context action stands back.
            assertEquals(value, 1, offered.count { it.text.startsWith("Mark the values with + in interpolate { }") })
            myFixture.launchAction(action!!)
            myFixture.checkResult(rewritten)
        }
    }

    fun `test marking the values with + is the first action offered on each value of the widget`() {
        val values = listOf("\${k.pageId}", CSS, "\${k.sectionId}", "\$mainHtml", "\${k.streamId}")
        val source = IMPORTS + SIGNATURE + widget(values[0], values[1], values[2], values[3], values[4]) + "\n"
        for (value in values) {
            val at = source.indexOf(value) + 1
            myFixture.configureByText("First${values.indexOf(value)}.kt", source.substring(0, at) + "<caret>" + source.substring(at))
            val offered = myFixture.availableIntentions.map { it.text }
            assertTrue("$value: $offered", offered.first().startsWith("Mark the values with + in interpolate { }"))
        }
    }

    fun `test in a block the mark is the first action offered on a value left unmarked`() {
        val source =
            "import io.github.markusaugust.streamlord.core.domain.interpolate\n\n" +
                "fun card(name: String) = interpolate { \"<p title=\\\"\$na<caret>me\\\">x</p>\" }\n"
        myFixture.configureByText("Unmarked.kt", source)
        val offered = myFixture.availableIntentions.map { it.text }
        assertEquals(offered.toString(), "Mark \$name with +, which interpolate escapes", offered.first())
    }

    fun `test a fix that marks a value as markup is put above no other where it stands alone`() {
        // The rewrite is withheld for a string that writes this, so the waiver is the only fix.
        val source =
            "import io.github.markusaugust.streamlord.core.domain.Trusted\n\n" +
                "class Row(val n: String) {\n    fun cell(note: String) = \"<td title=\\\"\${this.n}\\\">\$no<caret>te</td>\"\n}\n"
        myFixture.configureByText("Alone.kt", source)
        val fixes = myFixture.getAllQuickFixes().mapNotNull { QuickFixWrapper.unwrap(it) as? ReplaceTextFix }
        val markup = fixes.single { it.familyName == "Mark \$note as markup" }
        assertFalse(fixes.map { it.familyName }.toString(), markup is HighPriorityAction)
    }

    fun `test the rewrite of the widget is a context action in its markup, away from every warning`() {
        val source = IMPORTS + SIGNATURE + widget("\${k.pageId}", CSS, "\${k.sectionId}", "\$mainHtml", "\${k.streamId}") + "\n"
        val at = source.indexOf("data-theme") + 3
        myFixture.configureByText("Widget.kt", source.substring(0, at) + "<caret>" + source.substring(at))
        val offered = myFixture.availableIntentions.filter { it.text.startsWith("Mark the values with + in interpolate { }") }
        assertEquals(listOf("Mark the values with + in interpolate { }, with $CSS as Trusted(...)"), offered.map { it.text })
        myFixture.launchAction(offered.single())
        myFixture.checkResult(
            IMPORTS + SIGNATURE + "interpolate { " + MARKED + " }.text\n",
        )
    }

    fun `test the rewrite is a context action away from the warning`() {
        myFixture.configureByText("Card.kt", "fun card(name: String): String = \"<p cl<caret>ass=\\\"x\\\">\$name</p>\"")
        val offered = myFixture.availableIntentions
        val action = offered.firstOrNull { it.text.startsWith("Mark the values with + in interpolate { }") }
        assertNotNull(offered.map { it.text }.toString(), action)
        myFixture.launchAction(action!!)
        myFixture.checkResult(
            "import io.github.markusaugust.streamlord.core.domain.interpolate\n\n" +
                "fun card(name: String): String = interpolate { \"<p class=\\\"x\\\">\${+name}</p>\" }.text",
        )
    }

    fun `test no rewrite is offered where no value of the string is escaped`() {
        myFixture.configureByText("Total.kt", "fun total(count: Int): String = \"<p>to<caret>tal \$count</p>\"")
        assertEmpty(myFixture.availableIntentions.filter { it.text.startsWith("Mark the values with + in interpolate { }") })
    }

    fun `test the rewrite is offered for a selection inside the string`() {
        myFixture.configureByText("Card.kt", "fun card(name: String): String = \"<selection><p class</selection>=\\\"x\\\">\$name</p>\"")
        val offered = myFixture.availableIntentions.filter { it.text.startsWith("Mark the values with + in interpolate { }") }
        assertEquals(1, offered.size)
        myFixture.launchAction(offered.single())
        myFixture.checkResult(
            "import io.github.markusaugust.streamlord.core.domain.interpolate\n\n" +
                "fun card(name: String): String = interpolate { \"<p class=\\\"x\\\">\${+name}</p>\" }.text",
        )
    }
}
