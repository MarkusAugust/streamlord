package io.github.markusaugust.streamlord.intellij

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class CompletionTest : BasePlatformTestCase() {
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
}
