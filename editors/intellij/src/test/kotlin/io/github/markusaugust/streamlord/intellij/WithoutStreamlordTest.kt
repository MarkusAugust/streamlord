package io.github.markusaugust.streamlord.intellij

import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.DumbModeTestUtils
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ThreeState
import io.github.markusaugust.streamlord.intellij.analysis.ProjectLibraries
import io.github.markusaugust.streamlord.intellij.analysis.StreamlordAnalysis
import io.github.markusaugust.streamlord.intellij.analysis.StreamlordLibrary
import io.github.markusaugust.streamlord.intellij.completion.DatastarCompletionConfidence
import io.github.markusaugust.streamlord.intellij.documentation.StreamlordDocumentation
import io.github.markusaugust.streamlord.intellij.inspections.DatastarAttributeInspection
import io.github.markusaugust.streamlord.intellij.inspections.KotlinInterpolationInspection
import io.github.markusaugust.streamlord.intellij.inspections.StreamlordMarkupInspection
import io.github.markusaugust.streamlord.intellij.spelling.DatastarSpellcheckingStrategy

/**
 * What a project without streamlord-html gets: the DSL's names are somebody else's, so their
 * strings are no Datastar, no fix writes a helper, and hover leaves the Kotlin helpers out.
 */
abstract class WithoutDslTestCase : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        myFixture.enableInspections(KotlinInterpolationInspection(), DatastarAttributeInspection(), StreamlordMarkupInspection())
    }

    fun `test a dsl named call is somebody else's and gets no trap`() {
        myFixture.configureByText("Click.kt", "fun f() { dataOnClick(\"\$count++\") }")
        val shown = myFixture.doHighlighting().mapNotNull { it.description }
        assertTrue(shown.toString(), shown.none { it.contains("Kotlin interpolates") })
        assertTrue(myFixture.getAllQuickFixes().none { it.text.startsWith("Use increment") })
    }

    fun `test a kotlinx html attribute is offered no dsl helper`() {
        myFixture.configureByText(
            "Page.kt",
            "import kotlinx.html.*\nfun FlowContent.f() { button { attributes[\"data-on:click\"] = \"\$count++\" } }",
        )
        val trap = myFixture.doHighlighting().filter { it.description?.contains("Kotlin interpolates") == true }
        assertEquals(listOf("\$count"), trap.map { it.text })
        assertFalse(trap.single().description, trap.single().description.contains("signal("))
        val fixes = myFixture.getAllQuickFixes().map { it.text }
        assertTrue(fixes.toString(), fixes.none { it.startsWith("Use increment") })
    }

    fun `test hover leaves the kotlin helpers out`() {
        myFixture.configureByText("page.html", "<div data-on:click=\"@post('/x')\"></div>")
        val attr = StreamlordDocumentation.at(myFixture.file, 8)!!.first
        assertTrue(attr, attr.contains("<b>data-on</b>") && !attr.contains("Kotlin:"))
        val action = StreamlordDocumentation.at(myFixture.file, myFixture.file.text.indexOf("@post") + 2)!!.first
        assertTrue(action, action.contains("@post(uri, options?)") && !action.contains("Kotlin:"))
        myFixture.configureByText("View.kt", "fun f() { dataOnClick(\"@post('/x', {retry: 'never'})\") }")
        val text = myFixture.file.text
        assertNull(StreamlordDocumentation.at(myFixture.file, text.indexOf("dataOnClick") + 3))
        assertNull(StreamlordDocumentation.at(myFixture.file, text.indexOf("@post") + 2))
        assertNull(StreamlordDocumentation.at(myFixture.file, text.indexOf("retry") + 5))
    }

    // kotlinx.html's own way to write Datastar is Datastar whatever the SDK.
    fun `test hover documents an action in the attributes form`() {
        myFixture.configureByText("View.kt", "fun FlowContent.f() { button { attributes[\"data-on:click\"] = \"@post('/x')\" } }")
        val doc = StreamlordDocumentation.at(myFixture.file, myFixture.file.text.indexOf("@post") + 2)!!.first
        assertTrue(doc, doc.contains("@post(uri, options?)") && !doc.contains("Kotlin:"))
    }

    fun `test the string of a dsl name is neither coloured nor left out of the spell check`() {
        myFixture.configureByText("View.kt", "fun f() { dataText(\"\${'\$'}count + 1\") }")
        val coloured = myFixture.doHighlighting().filter { it.forcedTextAttributesKey != null }.map { it.text }
        assertFalse(coloured.toString(), "count" in coloured)
        val strategy = DatastarSpellcheckingStrategy()
        var claimed = 0
        PsiTreeUtil.processElements(myFixture.file) {
            if (strategy.isMyContext(it)) claimed++
            true
        }
        assertEquals(0, claimed)
    }

    // Kotlin's own completion of a string template is not stopped for signals, and the popup keeps the platform's rule.
    fun `test completion in the string of a dsl name is kotlin's`() {
        myFixture.addFileToProject("page.html", "<div data-signals:count=\"0\"></div>")
        myFixture.configureByText("Feed.kt", "fun f() { val local = 1; val lower = 2; dataText(\"\$l<caret>\") }")
        val items = myFixture.completeBasic()?.map { it.lookupString }
        assertNotNull(items)
        assertTrue(items.toString(), "local" in items!! && "lower" in items)
        assertTrue(items.toString(), items.none { it.startsWith("\$") })
        val offset = myFixture.caretOffset
        val element = myFixture.file.findElementAt(offset - 1)!!
        val confidence = DatastarCompletionConfidence().shouldSkipAutopopup(myFixture.editor, element, myFixture.file, offset)
        assertEquals(ThreeState.UNSURE, confidence)
    }

    // An HTML string is Datastar whatever the project has.
    fun `test completion in an html string offers the signals`() {
        myFixture.addFileToProject("page.html", "<div data-signals:count=\"0\" data-signals:cost=\"0\"></div>")
        myFixture.configureByText("Page.kt", "fun page() = \"\"\"<p data-text=\"\${'\$'}co<caret>\"></p>\"\"\"")
        val items = myFixture.completeBasic()?.map { it.lookupString }.orEmpty()
        assertTrue(items.toString(), items.any { it.contains("count") } && items.any { it.contains("cost") })
    }
}

/** A project with kotlinx.html and nothing of Streamlord: no call is Streamlord's, and no fix sends the author to core. */
class WithoutStreamlordTest : WithoutDslTestCase() {
    override fun getProjectDescriptor(): LightProjectDescriptor = KotlinxHtmlProject

    fun `test the project has none of streamlord, and while the ide indexes that is not known`() {
        val file = myFixture.configureByText("Page.kt", "fun f() = 1")
        for (library in StreamlordLibrary.entries) assertEquals(library.name, false, ProjectLibraries.known(file, library))
        val options = StreamlordAnalysis.getInstance(project).options(file)
        assertEquals(false, options.htmlDsl)
        assertEquals(false, options.core)
        DumbModeTestUtils.runInDumbModeSynchronously(project) {
            for (library in StreamlordLibrary.entries) assertNull(library.name, ProjectLibraries.known(file, library))
            val dumb = StreamlordAnalysis.getInstance(project).options(file)
            assertNull(dumb.htmlDsl)
            assertNull(dumb.core)
        }
        assertEquals(false, ProjectLibraries.known(file, StreamlordLibrary.CORE))
    }

    fun `test a value in element text is offered neither interpolate nor Trusted`() {
        myFixture.configureByText("Card.kt", "fun card(name: String): String = \"<p>\$name</p>\"")
        val shown = myFixture.doHighlighting().filter { it.inspectionToolId == "KotlinInterpolationInspection" }
        assertEquals(listOf("\$name"), shown.map { it.text })
        assertFalse(shown.single().description, shown.single().description.contains("interpolate"))
        val fixes = myFixture.getAllQuickFixes().map { it.text }
        assertTrue(fixes.toString(), fixes.none { "interpolate" in it || it.startsWith("Mark ") })
    }

    // Without core no call is Streamlord's: a patchElements is anybody's, and its HTML a free string.
    fun `test html handed to a patchElements is judged as a free string`() {
        myFixture.configureByText("Feed.kt", "fun f(sse: Any) { sse.patchElements(\"<div data-onn:x=\\\"1\\\">x</div>\") }")
        val shown = myFixture.doHighlighting().mapNotNull { it.description }
        assertTrue(shown.toString(), shown.any { it.contains("Unknown Datastar attribute data-onn.") })
        assertTrue(shown.toString(), shown.none { it.contains("has no id") })
    }

    fun `test the streamlord templates wait for the modules they write calls of`() {
        assertEquals(emptyList<String>(), streamlordTemplates(myFixture, "Routes.kt", "fun f() {\n    <caret>pat\n}\n"))
        assertEquals(listOf("htmlFunction"), streamlordTemplates(myFixture, "Top.kt", "<caret>htm\n"))
    }

    // While the IDE indexes the modules are not known, and the templates are offered as before.
    fun `test the streamlord templates stay while the ide indexes`() {
        myFixture.configureByText("Routes.kt", "fun f() {\n    <caret>pat\n}\n")
        val offered = DumbModeTestUtils.computeInDumbModeSynchronously(project) { streamlordTemplates(myFixture) }
        assertTrue(offered.toString(), "patchSignals" in offered && "dataOnClick" in offered && "ktor-datastar-route" in offered)
    }
}

/** A Ktor user without the DSL: core's calls are Streamlord's, the DSL's are not. */
class CoreWithoutHtmlTest : WithoutDslTestCase() {
    override fun getProjectDescriptor(): LightProjectDescriptor = CoreKtorProject

    fun `test the project has core and ktor but not the dsl`() {
        val file = myFixture.configureByText("Page.kt", "fun f() = 1")
        val found = StreamlordLibrary.entries.associateWith { ProjectLibraries.known(file, it) }
        assertEquals(
            mapOf(
                StreamlordLibrary.HTML to false,
                StreamlordLibrary.CORE to true,
                StreamlordLibrary.KTOR to true,
                StreamlordLibrary.SPRING to false,
            ),
            found,
        )
    }

    fun `test a value in element text is offered interpolate and Trusted`() {
        myFixture.configureByText("Card.kt", "fun card(name: String): String = \"<p>\$name</p>\"")
        myFixture.doHighlighting()
        val fixes = myFixture.getAllQuickFixes().map { it.text }
        assertTrue(fixes.toString(), fixes.any { "interpolate" in it } && fixes.any { it.startsWith("Mark ") })
    }

    fun `test the templates of core and ktor are offered and the dsl's are not`() {
        assertEquals(
            listOf("executeScript", "ktor-datastar-signals", "patchElementsHtml", "patchSignals", "removeElements"),
            streamlordTemplates(myFixture, "Routes.kt", "fun f() {\n    <caret>pat\n}\n"),
        )
    }
}
