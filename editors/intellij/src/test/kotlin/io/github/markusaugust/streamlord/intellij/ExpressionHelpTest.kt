package io.github.markusaugust.streamlord.intellij

import com.intellij.codeInsight.lookup.Lookup
import com.intellij.testFramework.DumbModeTestUtils
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.utils.parameterInfo.MockCreateParameterInfoContext
import com.intellij.testFramework.utils.parameterInfo.MockUpdateParameterInfoContext
import io.github.markusaugust.streamlord.analysis.ActionSpec
import io.github.markusaugust.streamlord.intellij.completion.DatastarParameterInfoHandler
import io.github.markusaugust.streamlord.intellij.documentation.StreamlordDocumentationProvider

/** Help inside a Datastar expression: completion where the value is, its options and events, parameter info and option docs. */
class ExpressionHelpTest : BasePlatformTestCase() {
    private fun lookups(
        name: String,
        text: String,
    ): List<String> {
        myFixture.configureByText(name, text)
        myFixture.completeBasic()
        return myFixture.lookupElementStrings.orEmpty()
    }

    fun `test signals complete in a value over several lines and in single quotes`() {
        val signals = "<div data-signals=\"{busy: false, open: true}\"></div>\n"
        assertContainsElements(lookups("a.html", signals + "<div data-signals=\"{\n  a: 1,\n  b: \$<caret>\"></div>"), "\$busy")
        assertContainsElements(lookups("b.html", signals + "<div data-text='\$<caret>'></div>"), "\$busy")
    }

    fun `test a signal in an ordinary kotlin string keeps its escaped dollar`() {
        myFixture.configureByText(
            "View.kt",
            "val signals = \"<div data-signals:busy=\\\"0\\\" data-signals:bubble=\\\"0\\\"></div>\"\nval x = \"<div data-text=\\\"\\\$bu<caret>\\\"></div>\"",
        )
        // One item is inserted at once; with more, the signal is picked from the list.
        val items = myFixture.completeBasic()
        if (items != null) {
            myFixture.lookup.currentItem = items.first { it.lookupString.endsWith("busy") }
            myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR)
        }
        assertTrue(myFixture.file.text, myFixture.file.text.contains("\\\$busy\\\""))
    }

    fun `test the options of a backend action not yet written`() {
        val options = lookups("c.html", "<div data-on:click=\"@get('/x', {retry: 'never', re<caret>})\"></div>")
        assertContainsElements(options, "retryInterval", "retryScaler")
        assertDoesntContain(options, "retry")
    }

    fun `test the colon after an option is written only where none follows`() {
        myFixture.configureByText("h.html", "<div data-on:click=\"@get('/x', {retryMax<caret>: 10})\"></div>")
        val items = myFixture.completeBasic()
        if (items != null) {
            myFixture.lookup.currentItem = items.first { it.lookupString == "retryMaxCount" }
            myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR)
        }
        assertTrue(myFixture.file.text, myFixture.file.text.contains("{retryMaxCount: 10}"))
    }

    fun `test events after data-on`() {
        assertContainsElements(lookups("d.html", "<button data-on:<caret>></button>"), "data-on:click", "data-on:datastar-fetch")
    }

    fun `test parameter info shows the parameter at the caret`() {
        myFixture.configureByText("e.html", "<div data-on:click=\"@get('/x', <caret>\"></div>")
        val handler = DatastarParameterInfoHandler()
        val create = MockCreateParameterInfoContext(myFixture.editor, myFixture.file)
        assertNotNull(handler.findElementForParameterInfo(create))
        assertEquals("get", (create.itemsToShow!!.single() as ActionSpec).name)
        val update = MockUpdateParameterInfoContext(myFixture.editor, myFixture.file, create.itemsToShow)
        val owner = handler.findElementForUpdatingParameterInfo(update)!!
        handler.updateParameterInfo(owner, update)
        assertEquals(1, update.currentParameter)

        myFixture.configureByText("View.kt", "fun f() { dataOnClick(\"@post(<caret>\") }")
        val kotlin = MockCreateParameterInfoContext(myFixture.editor, myFixture.file)
        assertNotNull(handler.findElementForParameterInfo(kotlin))
        assertEquals("post", (kotlin.itemsToShow!!.single() as ActionSpec).name)

        myFixture.configureByText("f.html", "<div data-text=\"\$count<caret>\"></div>")
        assertNull(handler.findElementForParameterInfo(MockCreateParameterInfoContext(myFixture.editor, myFixture.file)))
    }

    // Parameter info reads the catalog and the text only, so it answers while the IDE indexes.
    fun `test parameter info answers in dumb mode`() {
        myFixture.configureByText("i.html", "<div data-on:click=\"@post('/x', <caret>\"></div>")
        DumbModeTestUtils.runInDumbModeSynchronously(project) {
            val create = MockCreateParameterInfoContext(myFixture.editor, myFixture.file)
            assertNotNull(DatastarParameterInfoHandler().findElementForParameterInfo(create))
            assertEquals("post", (create.itemsToShow!!.single() as ActionSpec).name)
        }
    }

    // A template file works as HTML does: FreeMarker has its plugin here, so `.ftl` is its own language with an HTML view.
    fun `test options and parameter info in a template file`() {
        assertContainsElements(lookups("page.ftl", "<div data-on:click=\"@get('/x', {ret<caret>})\"></div>"), "retry", "retryInterval")
        myFixture.configureByText("call.ftl", "<div data-on:click=\"@get('/x', <caret>)\"></div>")
        val create = MockCreateParameterInfoContext(myFixture.editor, myFixture.file)
        assertNotNull(DatastarParameterInfoHandler().findElementForParameterInfo(create))
    }

    // HTML injected into a Kotlin string is read from the string, decoded.
    fun `test options in html injected into a kotlin string`() {
        val options = lookups("Page.kt", "fun page() = \"\"\"<div data-on:click=\"@get('/x', {ret<caret>})\"></div>\"\"\"")
        assertContainsElements(options, "retry", "retryInterval")
    }

    fun `test an option of a backend action is documented`() {
        myFixture.configureByText("g.html", "<div data-on:click=\"@get('/x', {retryScaler: 3})\"></div>")
        val doc = StreamlordDocumentationProvider().documentationAt(myFixture.file, myFixture.file.text.indexOf("retryScaler") + 3)
        assertTrue(doc?.first.orEmpty(), doc?.first.orEmpty().contains("<b>retryScaler</b>"))
    }
}
