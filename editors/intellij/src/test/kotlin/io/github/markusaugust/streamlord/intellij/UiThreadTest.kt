package io.github.markusaugust.streamlord.intellij

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiDocumentManager
import io.github.markusaugust.streamlord.intellij.analysis.UiThreadWork

/** What the user does on the UI thread, typing and starting a rename, asks the indexes nothing and resolves nothing there. */
class UiThreadTest : StreamlordTestCase() {
    fun `test typing a parenthesis in a Kotlin string asks nothing on the UI thread`() {
        myFixture.configureByText("View.kt", "fun f() { dataOnClick(\"@post<caret>\") }")
        val before = UiThreadWork.count.get()

        myFixture.type("(")

        assertEquals("fun f() { dataOnClick(\"@post(\") }", myFixture.file.text)
        assertEquals(0, UiThreadWork.count.get() - before)
    }

    fun `test a rename started right after an edit finds its signal off the UI thread`() {
        val page = myFixture.addFileToProject("page.html", "<div data-signals:count=\"0\"></div>")
        val view = myFixture.addFileToProject("View.kt", "fun h() = patchSignals(mapOf(\"count\" to 1))")
        myFixture.configureByText("other.html", "<b data-text=\"\$co<caret>unt\"></b>")
        // An edit, so nothing about the files is worked out yet when the rename starts.
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
}
