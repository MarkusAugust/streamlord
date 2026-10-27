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
    fun `the pairs form camel-cases its keys as the keyed form does, and a patch keeps them`() {
        assertEquals(listOf("foo-bar", "fooBar"), kotlin("dataSignals(\"foo-bar\" to 7); patchSignals(\"foo-bar\" to 1)"))
    }

    // The client merges the value as it is, so a key inside it is no signal of its own.
    @Test
    fun `a key inside a pair's value declares nothing on its own`() {
        assertEquals(listOf("user"), kotlin("dataSignals(\"user\" to mapOf(\"first-name\" to \"\", \"age\" to 1))"))
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
        // A key of an object under a key is a signal under that one, as the client stores it.
        assertEquals(listOf("nested", "nested.deep", "query"), html("""<div data-signals='{"query": "", "nested": {"deep": 1}}'></div>"""))
        assertEquals(listOf("bare", "single"), html("""<div data-signals__ifmissing="{'single': 1, bare: 2}"></div>"""))
        assertEquals(listOf("esc"), kotlin("""val k = "<div data-signals='{\"esc\": 1}'></div>""""))
    }

    @Test
    fun `a spread of a variable may define any signal, and names none to complete`() {
        val src = """<div data-signals="{a: 1, ...init, b: {...more}}"></div>"""
        assertEquals(listOf("a", "b"), html(src))
        assertEquals(listOf("*", "a", "b"), collectSignalDefinitions(src, SourceLanguage.HTML).sorted())
    }

    @Test
    fun `a key with template syntax in it declares nothing`() {
        val keys =
            "<div data-signals:${'$'}{key}=\"1\"></div><div data-signals:{{ name }}=\"1\"></div><div data-signals:th:text=\"1\"></div>" +
                "<div data-signals:<%= k %>=\"1\"></div><div data-signals:foo{{x}}=\"1\"></div>"
        assertEquals(listOf("user-name", "år"), html(keys + "<div data-bind:user-name__case.kebab></div><div data-signals:år=\"1\"></div>"))
        assertEquals(emptyList(), kotlin("val x = \"<div data-signals:${'$'}{name}=\\\"1\\\"></div>\""))
    }
}
