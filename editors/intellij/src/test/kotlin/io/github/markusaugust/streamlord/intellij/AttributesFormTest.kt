package io.github.markusaugust.streamlord.intellij

import com.intellij.grazie.ide.inspection.grammar.GrazieInspection
import com.intellij.grazie.spellcheck.GrazieSpellCheckingInspection
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ThreeState
import io.github.markusaugust.streamlord.intellij.analysis.StreamlordAnalysis
import io.github.markusaugust.streamlord.intellij.completion.DatastarCompletionConfidence
import io.github.markusaugust.streamlord.intellij.documentation.StreamlordDocumentation
import io.github.markusaugust.streamlord.intellij.highlighting.DatastarColors
import io.github.markusaugust.streamlord.intellij.spelling.DatastarSpellcheckingStrategy
import org.jetbrains.kotlin.psi.KtFile

/** kotlinx.html's own way to write Datastar, `attributes["data-text"] = "..."`, is read as Datastar whatever the project has. */
abstract class AttributesFormTestCase : BasePlatformTestCase() {
    fun `test the name and the expression are coloured`() {
        myFixture.configureByText(
            "View.kt",
            "fun FlowContent.f() { button { attributes[\"data-on:click__debounce.500ms\"] = \"\${'\$'}count++; @post('/x')\" } }",
        )
        val keys = colours()
        assertEquals(DatastarColors.ATTRIBUTE_KEY, keys["click"])
        assertEquals(DatastarColors.MODIFIER, keys["debounce"])
        assertEquals(DatastarColors.MODIFIER_ARG, keys["500ms"])
        assertEquals(DatastarColors.KOTLIN_DOLLAR_ESCAPE, keys["\${'\$'}"])
        assertEquals(DatastarColors.SIGNAL, keys["count"])
        assertEquals(DatastarColors.ACTION_BACKEND, keys["@post"])
    }

    // The diagnostics take the first half of a concatenation for a template, and the colours follow them.
    fun `test a value joined to more is not coloured, and an attribute of no expression neither`() {
        myFixture.configureByText(
            "View.kt",
            "fun FlowContent.f(x: String) { span { attributes[\"data-text\"] = \"\${'\$'}count\" + x; attributes[\"data-ignore\"] = \"\${'\$'}n\" } }",
        )
        val keys = colours()
        assertEquals(DatastarColors.ATTRIBUTE_PLUGIN, keys["text"])
        assertNull(keys.toString(), keys["count"])
        assertNull(keys.toString(), keys["n"])
    }

    fun `test completion in the value offers the signals and the actions`() {
        myFixture.addFileToProject("page.html", "<div data-signals:count=\"0\" data-signals:cost=\"0\"></div>")
        myFixture.configureByText("View.kt", "fun FlowContent.f() { span { attributes[\"data-text\"] = \"\\\$co<caret>\" } }")
        val signals = myFixture.completeBasic()?.map { it.lookupString }.orEmpty()
        assertTrue(signals.toString(), signals.any { it.contains("count") } && signals.any { it.contains("cost") })
        myFixture.configureByText("Click.kt", "fun FlowContent.f() { button { attributes[\"data-on:click\"] = \"@p<caret>\" } }")
        val actions = myFixture.completeBasic()?.map { it.lookupString }.orEmpty()
        assertTrue(actions.toString(), actions.any { it.startsWith("@post") } && actions.any { it.startsWith("@patch") })
    }

    fun `test only the strings of the expression are spelled`() {
        myFixture.enableInspections(GrazieSpellCheckingInspection(), GrazieInspection())
        myFixture.configureByText(
            "View.kt",
            "fun FlowContent.f() { span { attributes[\"data-text\"] = \"\${'\$'}usrnm + ' Lagree'\" } }\nval other = \"Wrongg\"",
        )
        val found = myFixture.doHighlighting().mapNotNull { it.description }.filter { "Typo" in it }
        assertTrue(found.toString(), found.any { "Lagree" in it })
        assertTrue(found.toString(), found.any { "Wrongg" in it })
        assertTrue(found.toString(), found.none { "usrnm" in it })
    }

    fun `test hover documents the attribute in the key`() {
        myFixture.configureByText("View.kt", "fun FlowContent.f() { button { attributes[\"data-on:click\"] = \"@post('/x')\" } }")
        val doc = StreamlordDocumentation.at(myFixture.file, myFixture.file.text.indexOf("data-on") + 6)
        assertTrue(doc?.first, doc?.first?.contains("<b>data-on</b>") == true)
    }

    fun `test the autopopup opens in the value`() {
        myFixture.configureByText("View.kt", "fun FlowContent.f() { span { attributes[\"data-text\"] = \"\\\$<caret>\" } }")
        val offset = myFixture.caretOffset
        val element = myFixture.file.findElementAt(offset - 1)!!
        assertEquals(ThreeState.NO, DatastarCompletionConfidence().shouldSkipAutopopup(myFixture.editor, element, myFixture.file, offset))
    }

    protected fun colours() =
        myFixture
            .doHighlighting()
            .filter { it.forcedTextAttributesKey != null }
            .associate { it.text to it.forcedTextAttributesKey }
}

class AttributesFormTest : AttributesFormTestCase() {
    override fun getProjectDescriptor(): LightProjectDescriptor = StreamlordProject
}

class AttributesFormWithoutStreamlordTest : AttributesFormTestCase() {
    override fun getProjectDescriptor(): LightProjectDescriptor = KotlinxHtmlProject
}

/**
 * In a project with the DSL, a call named as one of its functions that the Kotlin plugin resolves elsewhere is somebody
 * else's: its string is neither coloured, spelled as an expression, documented nor completed as Datastar.
 */
class ForeignDslCallTest : StreamlordTestCase() {
    private val onReceiver =
        "class Other { fun dataText(s: String) {}\n fun dataOnClick(s: String) {} }\n" +
            "fun f(other: Other) { other.dataText(\"\${'\$'}count + ' Lagree'\"); other.dataOnClick(\"@post('/x')\") }"

    fun `test a call on a receiver of somebody else's is not coloured`() {
        myFixture.configureByText("Other.kt", onReceiver)
        val keys = colours()
        assertNull(keys.toString(), keys["count"])
        assertNull(keys.toString(), keys["@post"])
    }

    fun `test a function imported from elsewhere is not coloured, and the dsl's own still is`() {
        myFixture.addFileToProject("mine/Mine.kt", "package mine\nfun dataText(s: String) {}")
        myFixture.configureByText("Mine.kt", "import mine.dataText\nfun f() { dataText(\"\${'\$'}count\") }")
        assertNull(colours()["count"])
        myFixture.configureByText("View.kt", "fun f() { dataText(\"\${'\$'}count\") }")
        assertEquals(DatastarColors.SIGNAL, colours()["count"])
    }

    // Nothing in the file says the call is somebody else's, but its issue had it resolved: the colours agree with the diagnostics.
    fun `test a call the diagnostics found foreign is not coloured`() {
        myFixture.addFileToProject("Helper.kt", "fun dataText(s: String) {}")
        myFixture.configureByText("View.kt", "fun f() { dataText(\"\${'\$'}count + @nope()\") }")
        val highlights = myFixture.doHighlighting()
        assertTrue(highlights.none { it.description?.contains("nope") == true })
        val keys = highlights.filter { it.forcedTextAttributesKey != null }.associate { it.text to it.forcedTextAttributesKey }
        assertNull(keys.toString(), keys["count"])
    }

    fun `test the string is spelled as kotlin's`() {
        myFixture.configureByText("Other.kt", onReceiver)
        val strategy = DatastarSpellcheckingStrategy()
        val file = myFixture.file
        val claimed =
            inBackground {
                var claimed = 0
                PsiTreeUtil.processElements(file) {
                    if (strategy.isMyContext(it)) claimed++
                    true
                }
                claimed
            }
        assertEquals(0, claimed)
    }

    fun `test hover leaves the action out`() {
        val file = myFixture.configureByText("Other.kt", onReceiver)
        assertNull(inBackground { StreamlordDocumentation.at(file, file.text.indexOf("@post") + 2) })
    }

    // A typed handler asks on the UI thread, where the Kotlin plugin forbids analysis: the names decide there.
    fun `test nothing is resolved on the ui thread`() {
        val file = myFixture.configureByText("Other.kt", onReceiver) as KtFile
        val analysis = StreamlordAnalysis.getInstance(project)
        val offset = file.text.indexOf("@post") + 2
        val names = analysis.catalog.expressionCallSites.keys
        assertFalse(analysis.isForeignCallAt(file, offset, names))
        assertTrue(inBackground { analysis.isForeignCallAt(file, offset, names) })
    }

    /** What [block] gives in a read action off the UI thread, where the platform runs highlighting, hover and spelling. */
    private fun <T> inBackground(block: () -> T): T =
        ApplicationManager
            .getApplication()
            .executeOnPooledThread<T> { ReadAction.nonBlocking<T>(block).executeSynchronously() }
            .get()

    fun `test completion is kotlin's`() {
        myFixture.addFileToProject("page.html", "<div data-signals:level=\"0\" data-signals:limit=\"0\"></div>")
        myFixture.configureByText(
            "Other.kt",
            "class Other { fun dataText(s: String) {} }\nfun f(other: Other) { val local = 1; other.dataText(\"\$l<caret>\") }",
        )
        val items = myFixture.completeBasic()?.map { it.lookupString }
        assertNotNull(items)
        assertTrue(items.toString(), "local" in items!!)
        assertTrue(items.toString(), items.none { it.startsWith("\$") })
    }

    private fun colours() =
        myFixture
            .doHighlighting()
            .filter { it.forcedTextAttributesKey != null }
            .associate { it.text to it.forcedTextAttributesKey }
}
