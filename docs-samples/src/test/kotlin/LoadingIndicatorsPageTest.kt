import kotlinx.html.div
import kotlinx.html.stream.createHTML
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The loading-indicators page shows each sample in three tabs. A reader copies one, so the three
 * have to be the same markup: the same tags, the same attributes in the same order, since the order
 * is one of the page's rules, and the same text. A value broken over lines reads as it would on one.
 * The Template tabs are read from the page itself.
 */
class LoadingIndicatorsPageTest {
    private val templates =
        Regex("""```html tab="Template" group=markup\n(.*?)\n```""", RegexOption.DOT_MATCHES_ALL)
            .findAll(File("../docs/src/content/docs/loading-indicators.md").readText())
            .map { it.groupValues[1] }
            .toList()

    @Test
    fun `the button and its message are the same in every tab`() {
        assertSame(fromDsl { nextPage() }, nextPageHtml, templates[0])
    }

    @Test
    fun `the indicator before data-init is the same in every tab`() {
        assertSame(fromDsl { ordersOnArrival() }, ordersOnArrivalHtml, templates[1])
    }

    @Test
    fun `the widget that sends for its buttons is the same in every tab`() {
        assertSame(fromDsl { ordersWidget() }, ordersWidgetHtml, templates[2])
    }

    @Test
    fun `the live view and its command are the same in every tab`() {
        assertSame(fromDsl { ordersView() }, ordersViewHtml, templates[4])
    }

    @Test
    fun `the answer is the same in every tab`() {
        for (page in listOf(1, 3)) {
            val consumer = createHTML(prettyPrint = false)
            consumer.ordersPage(page)
            assertEquals(markup(ordersPageHtml(page)), markup(consumer.finalize()), "page $page")
        }
        assertEquals(markup(ordersPageHtml(1)), markup(templates[3]), "the Template tab shows page 1")
    }

    private fun assertSame(
        dsl: List<String>,
        string: String,
        template: String,
    ) {
        assertEquals(markup(string), dsl, "the DSL tab against the String tab")
        assertEquals(markup(string), markup(template), "the Template tab against the String tab")
    }

    // What the DSL writes inside a wrapping div, so a sample with two elements at the top renders.
    private fun fromDsl(block: kotlinx.html.DIV.() -> Unit): List<String> =
        markup(createHTML(prettyPrint = false).div { block() }).drop(1).dropLast(1)

    /**
     * Markup as a reader of it sees it: each start tag with its attributes in order and unescaped,
     * each end tag, and the text between them with its whitespace collapsed. Quotes are honoured,
     * since a `>` may stand inside an attribute's value.
     */
    private fun markup(html: String): List<String> {
        val tokens = mutableListOf<String>()
        var i = 0
        while (i < html.length) {
            if (html[i] != '<') {
                val end = html.indexOf('<', i).takeIf { it >= 0 } ?: html.length
                val text = html.substring(i, end).replace(Regex("""\s+"""), " ").trim()
                if (text.isNotEmpty()) tokens += unescape(text)
                i = end
                continue
            }
            var j = i + 1
            var quote: Char? = null
            while (j < html.length && (quote != null || html[j] != '>')) {
                if (quote == null && (html[j] == '"' || html[j] == '\'')) {
                    quote = html[j]
                } else if (html[j] == quote) {
                    quote = null
                }
                j++
            }
            tokens += tag(html.substring(i + 1, j))
            i = j + 1
        }
        return tokens
    }

    private fun tag(inside: String): String {
        if (inside.startsWith("/")) return "</${inside.drop(1).trim()}>"
        val name = inside.takeWhile { !it.isWhitespace() }
        val attributes =
            Regex("""([^\s=]+)(?:="([^"]*)")?""")
                .findAll(inside.drop(name.length))
                .map { m -> attribute(m.groupValues[1], m.groups[2]?.value?.let { unescape(it).replace(Regex("""\s+"""), " ") }) }
                .toList()
        return "<$name ${attributes.joinToString(" | ")}>"
    }

    // A boolean attribute reads the same bare, empty or holding its own name, as kotlinx.html writes it.
    private fun attribute(
        name: String,
        value: String?,
    ): String = if (value == null || value == "" || value == name) name else "$name=$value"

    private fun unescape(text: String): String =
        text
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&gt;", ">")
            .replace("&lt;", "<")
            .replace("&amp;", "&")
}
