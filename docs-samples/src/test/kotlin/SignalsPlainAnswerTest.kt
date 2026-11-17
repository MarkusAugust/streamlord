import kotlinx.html.stream.createHTML
import kotlin.test.Test
import kotlin.test.assertEquals

/** The element that sets signals in a plain answer, as the signals page shows it in the DSL and in a string. */
class SignalsPlainAnswerTest {
    @Test
    fun `the DSL and the string write the same element`() {
        for (page in listOf(0, 7)) {
            val consumer = createHTML(prettyPrint = false)
            consumer.ordersSignals(page)
            assertEquals(ordersSignalsHtml(page), consumer.finalize().replace("hidden=\"hidden\"", "hidden"), "page $page")
        }
    }
}
