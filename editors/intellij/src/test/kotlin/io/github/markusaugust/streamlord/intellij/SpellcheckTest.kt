package io.github.markusaugust.streamlord.intellij

import com.intellij.grazie.ide.inspection.grammar.GrazieInspection
import com.intellij.grazie.spellcheck.GrazieSpellCheckingInspection
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.markusaugust.streamlord.intellij.spelling.DatastarSpellcheckingStrategy

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

    // The spell checker asks for every element of every pass, so the answer must not read the whole file each time.
    fun `test the strategy answers in time for a large kotlin file`() {
        val src = (0 until 600).joinToString("\n") { "fun f$it() { val s = \"text $it\"; dataOnClick(\"\\\$n$it++\") }" }
        myFixture.configureByText("Big.kt", src)
        val strategy = DatastarSpellcheckingStrategy()
        val started = System.nanoTime()
        var claimed = 0
        PsiTreeUtil.processElements(myFixture.file) {
            if (strategy.isMyContext(it)) claimed++
            true
        }
        val ms = (System.nanoTime() - started) / 1_000_000
        assertTrue("claimed $claimed", claimed > 0)
        // Eight seconds before the strings were cached, a fifth of one after.
        assertTrue("took $ms ms", ms < 3000)
    }

    // ${'$'} writes the dollar, so the browser gets $usrnm: a plain $usrnm here would be Kotlin's.
    fun `test only the strings of an expression in html injected into kotlin are spelled`() {
        val found =
            typos("Inj.kt", "fun page() = \"\"\"<div data-text=\"\${'\$'}usrnm + ' Lagree'\" title=\"Wrongg\"></div>\"\"\"")
        assertTrue(found.toString(), found.any { "Lagree" in it })
        assertTrue(found.toString(), found.none { "usrnm" in it })
    }

    // A Kotlin template in a string of an expression is Kotlin's code, which Kotlin spells where it is declared.
    fun `test a kotlin template in a string of an expression is no prose`() {
        val found = typos("Tpl.kt", "fun f(usrnm: String) { dataText(\"'Hello \${usrnm}'\") }")
        assertEquals(found.toString(), 1, found.count { "usrnm" in it })
    }

    // The analysis reads a string whose indent is trimmed, and one handed by name, as the expression.
    fun `test a trimmed string and a named one are expressions`() {
        val found =
            typos(
                "Trim.kt",
                "fun f() { dataOnClick(\"\\\$usrnm = ' Lagree'\".trimIndent()); dataOnClick(expression = \"\\\$usrnm = 1\") }",
            )
        assertTrue(found.toString(), found.none { "usrnm" in it })
        assertTrue(found.toString(), found.any { "Lagree" in it })
    }

    // A Kotlin escape ends a word: `\nline` is a new line and then `line`.
    fun `test a kotlin escape in a string of an expression ends the word`() {
        val found = typos("Esc.kt", "fun f() { dataOnClick(\"\\\$m = 'Line one\\nline two'\") }")
        assertTrue(found.toString(), found.isEmpty())
    }

    fun `test only the strings of an expression in a kotlin dsl call are spelled`() {
        val found = typos("View.kt", "fun f() { dataOnClick(\"\\\$usrnm = ' Lagree'\") }\nval prose = \"Wrongg\"")
        assertTrue(found.toString(), found.any { "Lagree" in it })
        assertTrue(found.toString(), found.any { "Wrongg" in it })
        assertTrue(found.toString(), found.none { "usrnm" in it })
    }
}
