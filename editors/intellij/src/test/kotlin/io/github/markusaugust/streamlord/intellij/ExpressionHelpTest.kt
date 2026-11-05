package io.github.markusaugust.streamlord.intellij

import com.intellij.codeInsight.lookup.Lookup
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.testFramework.DumbModeTestUtils
import com.intellij.testFramework.utils.parameterInfo.MockCreateParameterInfoContext
import com.intellij.testFramework.utils.parameterInfo.MockUpdateParameterInfoContext
import io.github.markusaugust.streamlord.analysis.ActionSpec
import io.github.markusaugust.streamlord.intellij.completion.DatastarCaret
import io.github.markusaugust.streamlord.intellij.completion.DatastarParameterInfoHandler
import io.github.markusaugust.streamlord.intellij.documentation.StreamlordDocumentationProvider

/** Help inside a Datastar expression: completion where the value is, its options and events, parameter info and option docs. */
class ExpressionHelpTest : StreamlordTestCase() {
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

    // The documentation of the lookup's items (F1 or Ctrl+Q while the list is open).
    fun `test options and events are documented in the list`() {
        val provider = StreamlordDocumentationProvider()

        fun docOf(name: String): String {
            val item = myFixture.lookupElements!!.first { it.lookupString == name }
            val element = provider.getDocumentationElementForLookupItem(psiManager, item.`object`, myFixture.file)
            return provider.generateDoc(element, null).orEmpty()
        }
        myFixture.configureByText("j.html", "<div data-on:click=\"@get('/x', {<caret>})\"></div>")
        myFixture.completeBasic()
        assertTrue(docOf("retry"), docOf("retry").contains("<b>retry</b>"))
        assertTrue(
            myFixture.lookupElements!!.first { it.lookupString == "retry" }.let { item ->
                val presentation =
                    com.intellij.codeInsight.lookup
                        .LookupElementPresentation()
                item.renderElement(presentation)
                presentation.tailText.orEmpty().contains("'auto'")
            },
        )
        myFixture.configureByText("k.html", "<button data-on:<caret>></button>")
        myFixture.completeBasic()
        assertTrue(docOf("data-on:datastar-fetch"), docOf("data-on:datastar-fetch").contains("document"))
        assertTrue(docOf("data-on:click"), docOf("data-on:click").contains("developer.mozilla.org"))
    }

    // The typed handler runs before the document is committed, so it reads the editor's text: what was just typed is there.
    fun `test the typed handler reads the text as typed`() {
        for ((name, text) in listOf(
            "l.html" to "<div data-on:click=\"<caret>\"></div>",
            "L.kt" to "fun f() { dataOnClick(\"<caret>\") }",
        )) {
            myFixture.configureByText(name, text)
            val at = myFixture.caretOffset
            WriteCommandAction.runWriteCommandAction(project) { myFixture.editor.document.insertString(at, "@get") }
            assertNull(name, DatastarCaret.expressionAt(myFixture.file, at + 4))
            assertEquals(name, "@get", DatastarCaret.expressionAt(myFixture.file, at + 4, fromDocument = true)?.before)
        }
    }

    fun `test an option of a backend action is documented`() {
        myFixture.configureByText("g.html", "<div data-on:click=\"@get('/x', {retryScaler: 3})\"></div>")
        val doc = StreamlordDocumentationProvider().documentationAt(myFixture.file, myFixture.file.text.indexOf("retryScaler") + 3)
        assertTrue(doc?.first.orEmpty(), doc?.first.orEmpty().contains("<b>retryScaler</b>"))
    }
}
