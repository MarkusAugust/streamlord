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
    fun `a name is collected as written, wherever it is written`() {
        assertEquals(listOf("fooBar", "x"), kotlin("dataSignals(\"fooBar\" to 7); patchSignals(\"fooBar\" to 1); dataBind(\"x\")"))
        // A name with a hyphen is refused at runtime, and collected as it stands until it is fixed.
        assertEquals(listOf("foo-bar"), kotlin("dataSignals(\"foo-bar\" to 7); signal(\"foo-bar\")"))
    }

    // Only a value the codec made, or one typed as JSON, may declare any name; a string or a pair
    // held in a variable is no such value, and the nearest declaration names the variable's class.
    @Test
    fun `a page declared from a variable is read by what the file says about it`() {
        fun encoded(src: String) = collectSignalDefinitions(src, SourceLanguage.KOTLIN).filter { it == "*" || ':' in it }
        val cases =
            mapOf(
                "val init = \"{count: 0}\"\nfun p() = div { dataSignals(init) }" to emptyList(),
                "fun p(pairs: Array<Pair<String, Any?>>) = div { dataSignals(*pairs) }" to emptyList(),
                "val p = \"count\" to 0\nfun f() = div { dataSignals(p) }" to emptyList(),
                "fun p(s: JsonObject) = div { dataSignals(s) }" to listOf("*"),
                "class X(val form: String)\nfun p() { val form = Form(); div { dataSignals(codec.toJsonValue(form)) } }" to
                    listOf("toJsonValue:Form"),
                "fun p() = div { dataSignals(codec.toJsonValue(forms.Form())) }" to listOf("toJsonValue:Form"),
                "fun p() = div { dataSignals(codec.toJsonValue(Form()), true) }" to listOf("toJsonValue:Form"),
                "fun p(text: String) = div { dataSignals(codec.toJsonValue(text)) }" to emptyList(),
                "const val INIT = \"{count: 0}\"\nfun p() = div { dataSignals(INIT); dataSignals(initial(0)) }" to emptyList(),
                "fun p() = div { dataSignals(codec.toJsonValue(Page<String>())) }" to listOf("toJsonValue:Page"),
                "fun a(form: LoginForm) = div { dataSignals(codec.toJsonValue(form)) }\n" +
                    "fun b(form: SearchSignals) = div { dataSignals(codec.toJsonValue(form)) }" to
                    listOf("toJsonValue:LoginForm", "toJsonValue:SearchSignals"),
            )
        for ((src, expected) in cases) assertEquals(expected, encoded(src), src)
    }

    // patchSignals(mapOf(...)) hands a map over as the signals, so its own keys are signals.
    @Test
    fun `the keys of a map handed over as the signals are signals`() {
        assertEquals(
            listOf("count", "open", "user"),
            kotlin(
                "stream.patchSignals(mapOf(\"count\" to 1, \"user\" to mapOf(\"name\" to \"\"))); respondSignals(hashMapOf(\"open\" to true))",
            ),
        )
        assertEquals(listOf("count"), kotlin("dataSignals((\"count\" to 0))"))
    }

    // The server's calls take the signals as JSON text too, and its keys are signals.
    @Test
    fun `the keys of the JSON a server call sends are signals`() {
        for (call in listOf("stream.patchSignals", "call.respondSignals", "datastarSignals")) {
            assertEquals(listOf("open"), kotlin("$call(\"\"\"{\"open\": true}\"\"\")"), call)
        }
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
