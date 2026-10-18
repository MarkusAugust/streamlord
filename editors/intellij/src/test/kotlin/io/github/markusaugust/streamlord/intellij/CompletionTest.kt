package io.github.markusaugust.streamlord.intellij

import com.intellij.codeInsight.lookup.Lookup
import com.intellij.psi.statistics.StatisticsManager
import com.intellij.psi.statistics.impl.StatisticsManagerImpl
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class CompletionTest : BasePlatformTestCase() {
    private val actions =
        listOf("@delete", "@get", "@patch", "@peek", "@post", "@put", "@query", "@setAll", "@toggleAll", "@clipboard", "@fit", "@intl")
    private val onModifiers =
        listOf(
            "capture",
            "case",
            "debounce",
            "delay",
            "document",
            "once",
            "outside",
            "passive",
            "prevent",
            "stop",
            "throttle",
            "viewtransition",
            "window",
        )

    fun `test signals after a dollar with this file first`() {
        myFixture.addFileToProject("other.html", "<div data-signals=\"{busy: false}\"></div>")
        myFixture.configureByText("Feed.kt", "fun f() { dataSignals(\"count\" to 0); dataOnClick(\"\$<caret>\") }")
        val items = myFixture.completeBasic()!!.map { it.lookupString }
        assertTrue(items.toString(), "\$count" in items && "\$busy" in items)
    }

    fun `test actions after an at sign`() {
        myFixture.configureByText("Feed.kt", "fun f() { dataOnClick(\"@<caret>\") }")
        val items = myFixture.completeBasic()!!.map { it.lookupString }
        assertTrue(items.toString(), "@post" in items && "@clipboard" in items)
    }

    fun `test actions and modifiers are listed by name, pro last, as in vs code`() {
        myFixture.configureByText("Feed.kt", "fun f() { dataOnClick(\"@<caret>\") }")
        assertEquals(actions, myFixture.completeBasic()!!.map { it.lookupString })
        myFixture.configureByText("page.html", "<div data-on:click=\"@<caret>\"></div>")
        assertEquals(actions, myFixture.completeBasic()!!.map { it.lookupString })
        val modifiers =
            mapOf(
                "<div data-on:click__<caret>" to onModifiers,
                "<div data-on-intersect__<caret>" to
                    listOf("debounce", "delay", "exit", "full", "half", "once", "threshold", "throttle", "viewtransition"),
                "<input data-bind:value__<caret>" to listOf("case", "event", "prop"),
            )
        modifiers.entries.forEachIndexed { n, (source, expected) ->
            myFixture.configureByText("mods$n.html", source)
            assertEquals(source, expected, myFixture.completeBasic()!!.map { it.lookupString })
        }
    }

    fun `test the order holds after the user has picked items before`() {
        // Tests run with the platform's usage statistics off, and they are what moves a picked item up.
        (StatisticsManager.getInstance() as StatisticsManagerImpl).enableStatistics(myFixture.testRootDisposable)
        repeat(5) { pick("<div data-on:click=\"@<caret>\"></div>", "@query") }
        repeat(5) { pick("<div data-on:click__<caret>></div>", "window") }
        myFixture.configureByText("page.html", "<div data-on:click=\"@<caret>\"></div>")
        assertEquals(actions, myFixture.completeBasic()!!.map { it.lookupString })
        myFixture.configureByText("mods.html", "<div data-on:click__<caret>")
        assertEquals(onModifiers, myFixture.completeBasic()!!.map { it.lookupString }.filter { it in onModifiers })
    }

    private fun pick(
        source: String,
        item: String,
    ) {
        myFixture.configureByText("pick.html", source)
        myFixture.completeBasic()
        val lookup = myFixture.lookup!!
        lookup.currentItem = lookup.items.first { it.lookupString == item }
        myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR)
    }

    fun `test ids and classes in a selector argument`() {
        myFixture.addFileToProject("other.html", "<div id=\"feed\" class=\"card\"></div>")
        myFixture.configureByText(
            "Feed.kt",
            "fun f() { s.patchElements(\"<li>x</li>\", selector = \"#<caret>\", mode = ElementPatchMode.APPEND) }",
        )
        // One item is inserted straight away.
        val items = myFixture.completeBasic()
        assertNull(items?.map { it.lookupString }.toString(), items)
        assertTrue(
            myFixture.editor.document.text
                .contains("selector = \"#feed\""),
        )
    }

    fun `test attributes modifiers and values in html`() {
        myFixture.configureByText("page.html", "<div data-<caret>")
        val attrs = myFixture.completeBasic()!!.map { it.lookupString }
        assertTrue(attrs.toString(), "data-on" in attrs && "data-signals" in attrs)
        myFixture.configureByText("page2.html", "<div data-on:click__<caret>")
        val mods = myFixture.completeBasic()!!.map { it.lookupString }
        assertTrue(mods.toString(), "debounce" in mods && "outside" in mods)
        myFixture.configureByText("page3.html", "<div data-on:click__debounce.<caret>")
        val values = myFixture.completeBasic()!!.map { it.lookupString }
        assertEquals(listOf("500ms", "1s", "300ms", "100ms"), values)
    }

    fun `test the html side inside an html string in kotlin`() {
        myFixture.configureByText("View.kt", "fun side() = \"\"\"\n<div data-<caret>\n\"\"\"")
        val items = myFixture.completeBasic()?.map { it.lookupString } ?: emptyList()
        assertTrue(items.toString(), "data-on" in items)
    }

    fun `test patch is offered only in data-on-signal-patch, where the client defines it`() {
        myFixture.configureByText("page.html", "<div data-on-signal-patch=\"pat<caret>\"></div>")
        // The one item is inserted straight away.
        myFixture.completeBasic()
        assertTrue(myFixture.editor.document.text, "\"patch\"" in myFixture.editor.document.text)
        myFixture.configureByText("Feed.kt", "fun f() { dataOnSignalPatch(\"pat<caret>\") }")
        myFixture.completeBasic()
        assertTrue(myFixture.editor.document.text, "\"patch\"" in myFixture.editor.document.text)
        myFixture.configureByText("page2.html", "<div data-on:click=\"pat<caret>\"></div>")
        val items = myFixture.completeBasic()?.map { it.lookupString } ?: emptyList()
        assertFalse(items.toString(), "patch" in items || "\"patch\"" in myFixture.editor.document.text)
    }
}
