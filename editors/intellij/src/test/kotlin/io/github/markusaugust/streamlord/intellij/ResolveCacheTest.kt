package io.github.markusaugust.streamlord.intellij

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiDocumentManager
import com.intellij.util.ThreeState
import io.github.markusaugust.streamlord.intellij.analysis.StreamlordAnalysis
import io.github.markusaugust.streamlord.intellij.completion.DatastarCompletionConfidence
import org.jetbrains.kotlin.psi.KtFile

/** The veto resolves a file's calls once for each version of the file, not again for each edit elsewhere. */
class ResolveCacheTest : StreamlordTestCase() {
    fun `test an edit in another file resolves no call again`() {
        myFixture.addFileToProject(
            "dev/datastar/kotlin/sdk/ServerSentEventGenerator.kt",
            "package dev.datastar.kotlin.sdk\nclass ServerSentEventGenerator { fun patchElements(elements: String) {} }",
        )
        myFixture.addFileToProject("other/Text.kt", "package other\nfun dataText(expression: String) = expression")
        val calls =
            (0 until 50).joinToString("\n") {
                "fun f$it(sse: ServerSentEventGenerator) { sse.patchElements(\"<div data-onn:x=\\\"1\\\">x</div>\"); dataText(\"\\\$n$it\") }"
            }
        val file =
            myFixture.configureByText(
                "Feed.kt",
                "import dev.datastar.kotlin.sdk.ServerSentEventGenerator\nimport other.dataText\n$calls",
            ) as KtFile
        val analysis = StreamlordAnalysis.getInstance(project)
        val before = analysis.resolves.get()
        val issues = analysis.kotlinIssues(file)
        val refs = analysis.streamlordSignalReferences(file)
        assertEquals(50, issues.count { s -> s.issues.any { it.message.contains("Unknown Datastar attribute data-onn.") } })
        assertTrue(refs.toString(), refs.isEmpty())
        val resolved = analysis.resolves.get()
        assertEquals("each call once", 100, resolved - before)

        val elsewhere = myFixture.addFileToProject("Other.kt", "val x = 1")
        WriteCommandAction.runWriteCommandAction(project) {
            val document = PsiDocumentManager.getInstance(project).getDocument(elsewhere)!!
            document.setText("val x = 2")
            PsiDocumentManager.getInstance(project).commitDocument(document)
        }
        val started = System.nanoTime()
        assertSame(issues, analysis.kotlinIssues(file))
        assertSame(refs, analysis.streamlordSignalReferences(file))
        println("After an edit elsewhere: ${(System.nanoTime() - started) / 1_000_000} ms")
        assertEquals(resolved, analysis.resolves.get())

        // The file's own edit may change what its calls resolve to: they are resolved again.
        WriteCommandAction.runWriteCommandAction(project) {
            myFixture.editor.document.insertString(myFixture.editor.document.textLength, "\n")
            PsiDocumentManager.getInstance(project).commitDocument(myFixture.editor.document)
        }
        analysis.kotlinIssues(file)
        assertEquals("the patchElements calls again", resolved + 50, analysis.resolves.get())
    }

    fun `test the autopopup opens in the string of a dsl call`() {
        myFixture.configureByText("Feed.kt", "fun f() { dataText(\"\$<caret>\") }")
        val offset = myFixture.caretOffset
        val element = myFixture.file.findElementAt(offset - 1)!!
        assertEquals(ThreeState.NO, DatastarCompletionConfidence().shouldSkipAutopopup(myFixture.editor, element, myFixture.file, offset))
    }
}
