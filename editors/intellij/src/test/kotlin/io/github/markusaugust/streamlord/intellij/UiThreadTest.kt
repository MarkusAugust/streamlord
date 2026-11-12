package io.github.markusaugust.streamlord.intellij

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.impl.source.tree.injected.InjectedLanguageEditorUtil
import com.intellij.testFramework.PlatformTestUtil
import io.github.markusaugust.streamlord.intellij.analysis.UiThreadWork
import io.github.markusaugust.streamlord.intellij.completion.ExpressionHelp
import kotlinx.coroutines.runBlocking

/** What the user does on the UI thread, typing and starting a rename, asks the indexes nothing and resolves nothing there. */
class UiThreadTest : StreamlordTestCase() {
    fun `test typing a parenthesis in a Kotlin string asks nothing on the UI thread`() {
        myFixture.configureByText("View.kt", "fun f() { dataOnClick(\"@post<caret>\") }")
        val before = UiThreadWork.count.get()

        myFixture.type("(")

        assertEquals("fun f() { dataOnClick(\"@post(\") }", myFixture.file.text)
        assertEquals(0, UiThreadWork.count.get() - before)
    }

    // The help is asked for in the background, and the answer is the one for the text with the character in it.
    fun `test parameter info is wanted after a parenthesis in a Datastar expression, and nowhere else`() {
        for ((name, text, wanted) in listOf(
            Triple("View.kt", "fun f() { dataOnClick(\"@post<caret>\") }", true),
            Triple("page.html", "<div data-on:click=\"@post<caret>\"></div>", true),
            Triple("Plain.kt", "fun f() { println(\"@post<caret>\") }", false),
            Triple("text.html", "<div title=\"@post<caret>\"></div>", false),
        )) {
            myFixture.configureByText(name, text)
            myFixture.type("(")
            val answer = ExpressionHelp.getInstance(project).parameterInfoWanted(myFixture.editor)
            val deadline = System.currentTimeMillis() + 10_000
            while (!answer.isCompleted && System.currentTimeMillis() < deadline) {
                PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
                Thread.sleep(5)
            }
            assertTrue(name, answer.isCompleted)
            assertEquals(name, wanted, runBlocking { answer.await() })
        }
    }

    // The index is read again as the rename action looks for its handler, in the background; what is left to the
    // rename itself is the call in View.kt, which it resolves to tell whether it is Streamlord's.
    fun `test a rename started right after an edit resolves no call on the UI thread`() {
        val page = myFixture.addFileToProject("page.html", "<div data-signals:count=\"0\"></div>")
        val view = myFixture.addFileToProject("View.kt", "fun h() = patchSignals(mapOf(\"count\" to 1))")
        myFixture.configureByText("other.html", "<b data-text=\"\$co<caret>unt\"></b>")
        // An edit, so no call is resolved yet when the rename starts.
        val document = myFixture.editor.document
        WriteCommandAction.runWriteCommandAction(project) { document.insertString(document.textLength, "<i></i>") }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        val before = UiThreadWork.count.get()

        myFixture.renameElementAtCaretUsingHandler("total")

        assertEquals("<b data-text=\"\$total\"></b><i></i>", myFixture.file.text)
        assertEquals("<div data-signals:total=\"0\"></div>", page.text)
        assertEquals("fun h() = patchSignals(mapOf(\"total\" to 1))", view.text)
        assertEquals(0, UiThreadWork.count.get() - before)
    }

    // The action was found for the text before the edit: applying it reads the text as it is now, in the background.
    fun `test the rewrite to interpolate applied after an edit analyses nothing on the UI thread`() {
        myFixture.configureByText("Applied.kt", "fun card(name: String): String = \"<p cl<caret>ass=\\\"x\\\">\$name</p>\"")
        val action = myFixture.availableIntentions.first { it.text.startsWith("Build this string with interpolate(...)") }
        // The caret stands in HTML injected into the string: the Kotlin file's own document is the one edited.
        val document = InjectedLanguageEditorUtil.getTopLevelEditor(myFixture.editor).document
        WriteCommandAction.runWriteCommandAction(project) { document.insertString(document.textLength, "\n") }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        val before = UiThreadWork.count.get()

        myFixture.launchAction(action)

        assertEquals(
            "import io.github.markusaugust.streamlord.core.domain.interpolate\n\n" +
                "fun card(name: String): String = interpolate(\"<p class=\\\"x\\\">%s</p>\", name).text\n",
            document.text,
        )
        assertEquals(0, UiThreadWork.count.get() - before)
    }
}
