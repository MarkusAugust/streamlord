package io.github.markusaugust.streamlord.intellij

import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.markusaugust.streamlord.intellij.analysis.ProjectLibraries
import io.github.markusaugust.streamlord.intellij.analysis.StreamlordLibrary

/** A project with every Streamlord module the plugin knows: each is found, and each template is offered where it belongs. */
class StreamlordModulesTest : BasePlatformTestCase() {
    override fun getProjectDescriptor(): LightProjectDescriptor = AllModulesProject

    // The plugin knows each module by one class; a rename or a move in the SDK must fail here, not in a user's IDE.
    // By its class file: loading ElementPatchModeConverter would need Spring, which is compileOnly in the adapter.
    fun `test each marker class is in its module`() {
        for (library in StreamlordLibrary.entries) {
            val root = markerJar(library)
            assertNotNull(library.marker, root)
            val module = "streamlord-" + library.name.lowercase()
            assertTrue("${library.marker} in $root", module in root!!.invariantSeparatorsPath)
        }
    }

    fun `test every module is found`() {
        val file = myFixture.configureByText("Page.kt", "fun f() = 1")
        for (library in StreamlordLibrary.entries) assertEquals(library.name, true, ProjectLibraries.known(file, library))
    }

    fun `test the templates are offered with the modules they write calls of`() {
        assertEquals(STATEMENTS, streamlordTemplates(myFixture, "Routes.kt", "fun f() {\n    <caret>pat\n}\n"))
        assertEquals(listOf("htmlFunction", "spring-datastar-endpoint"), streamlordTemplates(myFixture, "Top.kt", "<caret>spr\n"))
        assertEquals(
            listOf("htmlFunction", "spring-datastar-endpoint"),
            streamlordTemplates(myFixture, "Web.kt", "class Web {\n    <caret>spr\n}\n"),
        )
    }

    // Where an expression stands but no statement: each template that writes a call is offered there too.
    fun `test the templates are offered where an expression stands`() {
        assertEquals(STATEMENTS, streamlordTemplates(myFixture, "Value.kt", "val x = <caret>pat\n"))
        assertEquals(STATEMENTS, streamlordTemplates(myFixture, "Argument.kt", "fun g(a: Any) = a\nfun f() = g(<caret>pat)\n"))
    }

    private companion object {
        val STATEMENTS =
            listOf(
                "dataInit",
                "dataOnClick",
                "dataSignals",
                "executeScript",
                "ktor-datastar-flow",
                "ktor-datastar-route",
                "ktor-datastar-signals",
                "patchElements",
                "patchElementsHtml",
                "patchSignals",
                "removeElements",
            )
    }
}
