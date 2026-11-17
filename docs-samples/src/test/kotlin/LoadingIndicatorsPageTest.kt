import kotlinx.html.div
import kotlinx.html.stream.createHTML
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The counter on the loading-indicators page, in its three tabs: the DSL has to write the attributes
 * the String and Template tabs show, or a reader who copies one tab gets another widget.
 */
class LoadingIndicatorsPageTest {
    @Test
    fun `the DSL counter writes what the String tab shows`() {
        val dsl = createHTML(prettyPrint = false).div { ordersPanel() }.removePrefix("<div>")
        assertEquals(attributes(ordersWidget), attributes(dsl))
        assertEquals(3, attributes(dsl).size, "the signal, the listener and aria-busy")
    }

    // The data-* attributes of the outer tag, unescaped the way the browser reads them. The tag ends
    // before the first child's `<`, since a `>` may stand inside a value.
    private fun attributes(html: String): Map<String, String> =
        Regex("""(data-[^\s=]+)="([^"]*)"""")
            .findAll(html.substring(0, html.indexOf('<', 1).takeIf { it > 0 } ?: html.length))
            .associate {
                it.groupValues[1] to
                    it.groupValues[2]
                        .replace("&amp;", "&")
                        .replace("&gt;", ">")
                        .replace("&lt;", "<")
            }
}
