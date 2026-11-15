package io.github.markusaugust.streamlord.intellij

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.IdempotenceChecker
import com.intellij.util.ThreeState
import io.github.markusaugust.streamlord.fixtures.assertGrowsLinearly
import io.github.markusaugust.streamlord.intellij.completion.DatastarCompletionConfidence
import io.github.markusaugust.streamlord.intellij.index.SignalIndex

/** Completion and the autopopup's question grow with a file of DSL calls as its size does, and the popup opens where it did. */
class CompletionScaleTest : StreamlordTestCase() {
    /** A file of [n] DSL functions, each defining a signal and reading it, and the caret after the last name read. */
    private class Many(
        val file: PsiFile,
        val caret: Int,
        val n: Int,
    )

    private var copy = 0

    // The files completed in a timed batch, which the next batch's go before it is built: signal names are offered
    // project-wide, so the files of earlier batches would make each completion slower than the last.
    private val used = ArrayList<PsiFile>()

    // Its syntax tree and the project's signal index built untimed, so that what is timed is completion.
    private fun many(n: Int): Many {
        if (used.isNotEmpty()) WriteCommandAction.runWriteCommandAction(project) { used.forEach { it.delete() } }
        used.clear()
        val calls = (0 until n).joinToString("\n") { "fun f$it() = div { dataSignals(\"s$it\" to 0); dataText(\"\\\$s$it + 1\") }" }
        val src = "import io.github.markusaugust.streamlord.html.*\n$calls\nfun g() = div { dataText(\"\\\$s${n - 1}\") }"
        val file = myFixture.addFileToProject("Many${copy++}.kt", src)
        PsiTreeUtil.processElements(file) { true }
        SignalIndex.getInstance(project).all()
        return Many(file, src.length - "\") }".length, n)
    }

    private fun open(many: Many) {
        myFixture.openFileInEditor(many.file.virtualFile)
        myFixture.editor.caretModel.moveToOffset(many.caret)
    }

    // The last signal is offered: in the list, or alone and so written in at once, which leaves the text as it was.
    private fun complete(many: Many) {
        open(many)
        val items = myFixture.completeBasic()?.map { it.lookupString }
        assertTrue(items.toString(), items == null || "\$s${many.n - 1}" in items)
    }

    private fun confidence(): ThreeState {
        val offset = myFixture.caretOffset
        return DatastarCompletionConfidence().shouldSkipAutopopup(
            myFixture.editor,
            myFixture.file.findElementAt(offset - 1)!!,
            myFixture.file,
            offset,
        )
    }

    // On 2,000 functions, before the analysis answered for both editors, completion took about 110 ms and the question 13 to 33.
    fun `test completion and the autopopup's question grow linearly with the dsl functions of a file`() {
        IdempotenceChecker.disableRandomChecksUntil(testRootDisposable)
        // The first completion on a file reads its references as well, which resolves each call that reads a signal once and
        // looks each signal up once; looked up per signal, it ran past the platform's 100 s on 2,000 functions.
        assertGrowsLinearly("the first completion on a file of DSL functions", 10, ::many) {
            complete(it)
            used += it.file
        }
        // Once read, completion and the question together, each a fraction of what the first completion costs.
        val completed = { n: Int -> many(n).also(::complete) }
        assertGrowsLinearly("completion and the autopopup's question on a file of DSL functions", 10, completed) {
            complete(it)
            assertEquals(ThreeState.NO, confidence())
            used += it.file
        }
    }

    // In plain HTML the popup is let through in a Datastar value, as before; a tag is the HTML plugin's.
    fun `test the autopopup opens in a datastar value of an html file, and not in a tag`() {
        myFixture.configureByText("a.html", "<div data-text=\"\$<caret>\"></div>")
        assertEquals(ThreeState.NO, confidence())
        myFixture.configureByText("b.html", "<div data-<caret>></div>")
        assertEquals(ThreeState.UNSURE, confidence())
        myFixture.configureByText("c.html", "<div data-on:keydown=\"evt.<caret>\"></div>")
        assertEquals(ThreeState.NO, confidence())
    }
}
