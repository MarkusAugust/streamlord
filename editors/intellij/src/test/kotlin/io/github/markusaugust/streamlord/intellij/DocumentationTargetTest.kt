package io.github.markusaugust.streamlord.intellij

import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.injected.editor.DocumentWindow
import com.intellij.lang.documentation.ide.IdeDocumentationTargetProvider
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.platform.backend.documentation.DocumentationTarget
import com.intellij.psi.PsiDocumentManager
import io.github.markusaugust.streamlord.intellij.documentation.StreamlordDocumentationTarget
import io.github.markusaugust.streamlord.intellij.documentation.StreamlordDocumentationTargetProvider

/** Quick documentation as the platform asks for it: by a place in a file, and by an item of the completion list. */
class DocumentationTargetTest : StreamlordTestCase() {
    private fun <T> inBackground(block: () -> T): T =
        ApplicationManager
            .getApplication()
            .executeOnPooledThread<T> { ReadAction.nonBlocking<T>(block).executeSynchronously() }
            .get()

    /** What the platform would show at [offset] of the open file, with Streamlord's own told from the rest. */
    private fun at(offset: Int): List<DocumentationTarget> {
        val provider = IdeDocumentationTargetProvider.getInstance(project)
        return inBackground { provider.documentationTargets(myFixture.editor, myFixture.file, offset) }
    }

    private fun of(item: LookupElement): List<DocumentationTarget> {
        val provider = IdeDocumentationTargetProvider.getInstance(project)
        return inBackground { provider.documentationTargets(myFixture.editor, myFixture.file, item) }
    }

    private fun List<DocumentationTarget>.streamlord() = filterIsInstance<StreamlordDocumentationTarget>()

    fun `test an attribute and an action in html are documented by Streamlord, in place of what html says of them`() {
        myFixture.configureByText("a.html", "<div data-on:click=\"@post('/x')\"></div>")
        val attribute = at(myFixture.file.text.indexOf("data-on") + 6)
        assertEquals(1, attribute.size)
        assertTrue(attribute.streamlord().single().html, "data-on" in attribute.streamlord().single().html)

        val action = at(myFixture.file.text.indexOf("@post") + 2)
        assertEquals(1, action.size)
        assertTrue(action.streamlord().single().html, "@post" in action.streamlord().single().html)
    }

    fun `test an action in a Kotlin string is documented, and a call Kotlin resolves is left to its KDoc`() {
        myFixture.configureByText(
            "View.kt",
            "import io.github.markusaugust.streamlord.html.*\nimport kotlinx.html.*\nfun DIV.f() { dataOnClick(\"@post('/x')\") }",
        )
        assertEquals(1, at(myFixture.file.text.indexOf("@post") + 2).streamlord().size)

        val call = at(myFixture.file.text.indexOf("dataOnClick") + 3)
        assertEquals(emptyList<StreamlordDocumentationTarget>(), call.streamlord())
        assertFalse(call.isEmpty())
    }

    // With the caret in HTML injected into a string, the platform asks for the injected file at its own offset.
    fun `test html inside a Kotlin string is documented, asked as the injected file and as the Kotlin file`() {
        myFixture.configureByText("Page.kt", "fun page() = \"\"\"<div data-on:click=\"@post('/x')\"></div>\"\"\"")
        val host = myFixture.file
        for (word in listOf("data-on", "@post")) {
            val offset = host.text.indexOf(word) + 2
            assertEquals(word, 1, at(offset).streamlord().size)
            val injected =
                inBackground {
                    val manager = InjectedLanguageManager.getInstance(project)
                    val file = manager.findInjectedElementAt(host, offset)!!.containingFile
                    val window = PsiDocumentManager.getInstance(project).getDocument(file) as DocumentWindow
                    StreamlordDocumentationTargetProvider().documentationTargets(file, window.hostToInjected(offset))
                }
            assertEquals(word, 1, injected.size)
        }
    }

    fun `test a word that is no Datastar is nobody's`() {
        myFixture.configureByText("b.html", "<div title=\"plain\"></div>")
        assertEquals(emptyList<StreamlordDocumentationTarget>(), at(myFixture.file.text.indexOf("plain") + 2).streamlord())
        myFixture.configureByText("C.java", "class C { String s = \"data-text\"; }")
        assertEquals(emptyList<StreamlordDocumentationTarget>(), at(myFixture.file.text.indexOf("data-text") + 6).streamlord())
    }

    // F1 or Ctrl+Q while the list is open.
    fun `test an option in the completion list is documented`() {
        myFixture.configureByText("j.html", "<div data-on:click=\"@get('/x', {<caret>})\"></div>")
        myFixture.completeBasic()
        val retry = of(myFixture.lookupElements!!.first { it.lookupString == "retry" })
        assertTrue(retry.streamlord().single().html, "<b>retry</b>" in retry.streamlord().single().html)
    }
}
