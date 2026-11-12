package io.github.markusaugust.streamlord.intellij

import com.intellij.util.ThreeState
import io.github.markusaugust.streamlord.intellij.completion.DatastarCompletionConfidence
import kotlin.time.measureTime

/** Completion and the autopopup's question stay quick on a file of thousands of DSL calls, and the popup opens where it did. */
class CompletionScaleTest : StreamlordTestCase() {
    private val calls = (0 until 2000).joinToString("\n") { "fun f$it() = div { dataSignals(\"s$it\" to 0); dataText(\"\\\$s$it + 1\") }" }

    private fun confidence(): ThreeState {
        val offset = myFixture.caretOffset
        return DatastarCompletionConfidence().shouldSkipAutopopup(
            myFixture.editor,
            myFixture.file.findElementAt(offset - 1)!!,
            myFixture.file,
            offset,
        )
    }

    // Before the analysis answered for both editors, completion here took about 110 ms and the question 13 to 33.
    fun `test completion and the autopopup's question on a file of two thousand dsl functions`() {
        myFixture.configureByText(
            "Many.kt",
            "import io.github.markusaugust.streamlord.html.*\n$calls\nfun g() = div { dataText(\"\\\$s1999<caret>\") }",
        )
        // The first completion reads the file's references as well, which resolves each call that reads a signal once and
        // looks each signal up once; looked up per signal, it ran past the platform's 100 s.
        val first = measureTime { myFixture.completeBasic() }
        var items: List<String> = emptyList()
        val completion = measureTime { items = myFixture.completeBasic()?.map { it.lookupString }.orEmpty() }
        assertTrue(
            items.toString(),
            "\$s1999" in items ||
                myFixture.editor.document.text
                    .contains("\$s1999\""),
        )
        var answer = ThreeState.UNSURE
        val question = measureTime { answer = confidence() }
        assertEquals(ThreeState.NO, answer)
        println("2,000 DSL functions: first completion $first, completion $completion, the autopopup's question $question")
        assertTrue("the first completion took $first", first.inWholeMilliseconds < 20_000)
        assertTrue("completion took $completion", completion.inWholeMilliseconds < 1000)
        assertTrue("the question took $question", question.inWholeMilliseconds < 100)
    }

    // In plain HTML the popup is let through in a Datastar value, as before; a tag is the HTML plugin's.
    fun `test the autopopup opens in a datastar value of an html file, and not in a tag`() {
        myFixture.configureByText("a.html", "<div data-text=\"\$<caret>\"></div>")
        assertEquals(ThreeState.NO, confidence())
        myFixture.configureByText("b.html", "<div data-<caret>></div>")
        assertEquals(ThreeState.UNSURE, confidence())
    }
}
