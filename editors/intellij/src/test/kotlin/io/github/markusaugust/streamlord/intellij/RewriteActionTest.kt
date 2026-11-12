package io.github.markusaugust.streamlord.intellij

import io.github.markusaugust.streamlord.intellij.inspections.KotlinInterpolationInspection

private const val Q = "\"\"\""
private const val IMPORTS =
    "import io.github.markusaugust.streamlord.core.domain.Trusted\n" +
        "import io.github.markusaugust.streamlord.core.domain.interpolate\n\n" +
        "class WidgetContext(val rootId: String, val contentId: String, val eventsId: String)\n\n"
private const val SIGNATURE = "fun rootHtml(k: WidgetContext, customCss: String? = null, bodyHtml: String): String =\n    "

/** The literal of the case that found the defects, with each value written as given. */
private fun widget(
    root: String,
    css: String,
    content: String,
    body: String,
    events: String,
): String =
    "$Q\n    <div id=\"$root\" data-theme=\"light\" data-lt-boundary=\"\">\n        <style>$css</style>\n" +
        "        <div id=\"$content\">\n            $body\n        </div>\n        <div id=\"$events\" hidden></div>\n    </div>\n    $Q.trimIndent()"

private const val ESCAPES = "Build this string with interpolate(...), which escapes the values"
private const val CSS = "\${customCss.orEmpty()}"

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
        val values = listOf("\${k.rootId}", CSS, "\${k.contentId}", "\$bodyHtml", "\${k.eventsId}")
        val source = IMPORTS + SIGNATURE + widget(values[0], values[1], values[2], values[3], values[4]) + "\n"
        val rewritten =
            IMPORTS + SIGNATURE + "interpolate(" + widget("%s", "%s", "%s", "%s", "%s") +
                ", k.rootId, Trusted(customCss.orEmpty()), k.contentId, bodyHtml, k.eventsId).text\n"
        for (value in values) {
            val title = if (value == CSS) "Build this string with interpolate(...), with $CSS as Trusted(...)" else ESCAPES
            // The caret one character into the value; a fresh file each time, since the last one was rewritten.
            val at = source.indexOf(value) + 1
            myFixture.configureByText("Widget${values.indexOf(value)}.kt", source.substring(0, at) + "<caret>" + source.substring(at))
            val offered = myFixture.availableIntentions
            val action = offered.firstOrNull { it.text == title }
            assertNotNull("$value: $title is not among ${offered.map { it.text }}", action)
            // The warning's quick fix is the rewrite there, so the context action stands back.
            assertEquals(value, 1, offered.count { it.text.startsWith("Build this string with interpolate(...)") })
            myFixture.launchAction(action!!)
            myFixture.checkResult(rewritten)
        }
    }

    fun `test the rewrite of the widget is a context action in its markup, away from every warning`() {
        val source = IMPORTS + SIGNATURE + widget("\${k.rootId}", CSS, "\${k.contentId}", "\$bodyHtml", "\${k.eventsId}") + "\n"
        val at = source.indexOf("data-theme") + 3
        myFixture.configureByText("Widget.kt", source.substring(0, at) + "<caret>" + source.substring(at))
        val offered = myFixture.availableIntentions.filter { it.text.startsWith("Build this string with interpolate(...)") }
        assertEquals(listOf("Build this string with interpolate(...), with $CSS as Trusted(...)"), offered.map { it.text })
        myFixture.launchAction(offered.single())
        myFixture.checkResult(
            IMPORTS + SIGNATURE + "interpolate(" + widget("%s", "%s", "%s", "%s", "%s") +
                ", k.rootId, Trusted(customCss.orEmpty()), k.contentId, bodyHtml, k.eventsId).text\n",
        )
    }

    fun `test the rewrite is a context action away from the warning`() {
        myFixture.configureByText("Card.kt", "fun card(name: String): String = \"<p cl<caret>ass=\\\"x\\\">\$name</p>\"")
        val offered = myFixture.availableIntentions
        val action = offered.firstOrNull { it.text.startsWith("Build this string with interpolate(...)") }
        assertNotNull(offered.map { it.text }.toString(), action)
        myFixture.launchAction(action!!)
        myFixture.checkResult(
            "import io.github.markusaugust.streamlord.core.domain.interpolate\n\n" +
                "fun card(name: String): String = interpolate(\"<p class=\\\"x\\\">%s</p>\", name).text",
        )
    }

    fun `test the rewrite is a context action where no value has a warning`() {
        myFixture.configureByText("Total.kt", "fun total(count: Int): String = \"<p>to<caret>tal \$count</p>\"")
        assertEmpty(myFixture.doHighlighting().filter { it.inspectionToolId == "KotlinInterpolationInspection" })
        val offered = myFixture.availableIntentions
        val action = offered.firstOrNull { it.text.startsWith("Build this string with interpolate(...)") }
        assertNotNull(offered.map { it.text }.toString(), action)
        myFixture.launchAction(action!!)
        myFixture.checkResult(
            "import io.github.markusaugust.streamlord.core.domain.interpolate\n\n" +
                "fun total(count: Int): String = interpolate(\"<p>total %s</p>\", count).text",
        )
    }
}
