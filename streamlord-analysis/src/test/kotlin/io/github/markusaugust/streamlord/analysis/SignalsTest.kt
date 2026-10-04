package io.github.markusaugust.streamlord.analysis

import kotlin.test.Test
import kotlin.test.assertEquals

/** The cases of the VS Code extension's `signals.test.ts`. */
class SignalsTest {
    private fun kotlin(src: String) = collectSignals(src, SourceLanguage.KOTLIN).sorted()

    private fun html(src: String) = collectSignals(src, SourceLanguage.HTML).sorted()

    @Test
    fun `a signals class may carry any modifier`() {
        val src =
            """
            @Serializable public data class Signals(val count: Int = 0, var name: String)
            @Serializable internal class Inner(val inner: Int)
            @Serializable(with = X::class) private value class Wrapped(val wrapped: Int)
            """.trimIndent()
        assertEquals(listOf("count", "inner", "name", "wrapped"), kotlin(src))
    }

    @Test
    fun `a property goes by its serial name, and a parenthesis does not end the class`() {
        val src =
            """
            @Serializable
            data class PageSignals(
                @SerialName("page_no") val page: Int = 0,
                val tags: List<String> = listOf(),
                @SerialName(value = "sort-by") @Required private val sort: String,
                val after: Int = 0,
            )
            """.trimIndent()
        assertEquals(listOf("after", "page_no", "sort-by", "tags"), kotlin(src))
    }

    @Test
    fun `the object form declares quoted keys in either kind of quote`() {
        assertEquals(listOf("deep", "nested", "query"), html("""<div data-signals='{"query": "", "nested": {"deep": 1}}'></div>"""))
        assertEquals(listOf("bare", "single"), html("""<div data-signals__ifmissing="{'single': 1, bare: 2}"></div>"""))
        assertEquals(listOf("esc"), kotlin("""val k = "<div data-signals='{\"esc\": 1}'></div>""""))
    }
}
