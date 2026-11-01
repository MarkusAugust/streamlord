package io.github.markusaugust.streamlord.intellij

import com.intellij.grazie.ide.inspection.grammar.GrazieInspection
import com.intellij.grazie.spellcheck.GrazieSpellCheckingInspection
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** In a Datastar expression only the text in quotes is prose: a signal or an action name is no word to spell. */
class SpellcheckTest : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        myFixture.enableInspections(GrazieSpellCheckingInspection(), GrazieInspection())
    }

    private fun typos(
        name: String,
        text: String,
    ): List<String> {
        myFixture.configureByText(name, text)
        return myFixture.doHighlighting().mapNotNull { it.description }.filter { "Typo" in it }
    }

    fun `test only the strings of an expression in html are spelled`() {
        val found = typos("page.html", "<div data-text=\"\$usrnm + ' Lagree'\" title=\"Wrongg\"></div>")
        assertTrue(found.toString(), found.any { "Lagree" in it })
        assertTrue(found.toString(), found.any { "Wrongg" in it })
        assertTrue(found.toString(), found.none { "usrnm" in it })
    }

    fun `test only the strings of an expression in a kotlin dsl call are spelled`() {
        val found = typos("View.kt", "fun f() { dataOnClick(\"\\\$usrnm = ' Lagree'\") }\nval prose = \"Wrongg\"")
        assertTrue(found.toString(), found.any { "Lagree" in it })
        assertTrue(found.toString(), found.any { "Wrongg" in it })
        assertTrue(found.toString(), found.none { "usrnm" in it })
    }
}
