package io.github.markusaugust.streamlord.intellij

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.util.IdempotenceChecker
import com.intellij.util.ThreeState
import io.github.markusaugust.streamlord.intellij.completion.DatastarCompletionConfidence
import kotlin.time.measureTime

/** Completion and the autopopup's question grow with a file of DSL calls as its size does, and the popup opens where it did. */
class CompletionScaleTest : StreamlordTestCase() {
    /** The text of a file of [n] DSL functions, each defining a signal and reading it, which ends reading the last. */
    private class Many(
        val text: String,
        val n: Int,
    )

    private var copy = 0

    private fun many(n: Int): Many {
        val calls = (0 until n).joinToString("\n") { "fun f$it() = div { dataSignals(\"s$it\" to 0); dataText(\"\\\$s$it + 1\") }" }
        return Many("import io.github.markusaugust.streamlord.html.*\n$calls\nfun g() = div { dataText(\"\\\$s${n - 1}\") }", n)
    }

    // The last signal is offered: in the list, or alone and so written in at once, which leaves the text as it was.
    private fun complete(many: Many) {
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

    /** Microseconds for completion, twice, and the autopopup's question, on a file of [n] DSL functions alone in the project. */
    private fun timed(n: Int): Long {
        val many = many(n)
        val file = myFixture.configureByText("Many${copy++}.kt", many.text)
        myFixture.editor.caretModel.moveToOffset(many.text.length - "\") }".length)
        val took =
            measureTime {
                complete(many)
                complete(many)
                assertEquals(ThreeState.NO, confidence())
            }
        WriteCommandAction.runWriteCommandAction(project) { file.delete() }
        return took.inWholeMicroseconds
    }

    // The first completion on a file reads its references as well, which resolves each call that reads a signal once and
    // looks each signal up once; looked up per signal, it ran past the platform's 100 s on 2,000 functions. Two files are
    // timed, of 250 and 2,000 functions, each alone in the project, since signals are looked up project-wide. Not with
    // assertGrowsLinearly, whose 64 small files each pay tens of milliseconds whatever their size, which hid the lookup
    // when it was put back. Eight times the functions may cost eight times as much; the lookup cost 64 times.
    fun `test completion and the autopopup's question grow linearly with the dsl functions of a file`() {
        IdempotenceChecker.disableRandomChecksUntil(testRootDisposable)
        timed(SMALL)
        var small = Long.MAX_VALUE
        var large = Long.MAX_VALUE
        repeat(3) {
            small = minOf(small, timed(SMALL))
            large = minOf(large, timed(SMALL * 8))
        }
        assertTrue("${small / 1000} ms for $SMALL DSL functions, ${large / 1000} ms for ${SMALL * 8}", large < small * 24)
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

    private companion object {
        const val SMALL = 250
    }
}
