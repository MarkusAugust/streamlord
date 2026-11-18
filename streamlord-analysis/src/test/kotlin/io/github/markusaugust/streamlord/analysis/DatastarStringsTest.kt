package io.github.markusaugust.streamlord.analysis

import io.github.markusaugust.streamlord.core.catalog.CatalogNames
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** [datastarStrings] as an editor uses it: which Kotlin strings are Datastar's, and what is coloured and spelled in each. */
class DatastarStringsTest {
    private val analyzer = Analyzer()
    private val d = "\$"

    private fun strings(
        text: String,
        modules: Modules = Modules.known(true, true),
        prefix: String = CatalogNames.PREFIX,
        foreign: (String, Int) -> Boolean = { _, _ -> false },
    ) = analyzer.datastarStrings(Source(text, SourceLanguage.KOTLIN, prefix, modules, foreign = foreign))

    /** Each string as its role and its text between the quotes. */
    private fun roles(
        text: String,
        found: List<DatastarString> = strings(text),
    ) = found.map { it::class.simpleName to text.substring(it.string.contentStart, it.string.contentEnd) }

    private fun tokens(
        text: String,
        s: DatastarString,
    ) = when (s) {
        is DatastarString.Expression -> s.tokens
        is DatastarString.SignalName -> s.tokens
        is DatastarString.Html -> s.tokens
        is DatastarString.AttributeName -> emptyList()
    }.map { text.substring(it.start, it.end) to it.kind }

    private fun parts(
        text: String,
        parts: List<NameToken>,
    ) = parts.map { text.substring(it.start, it.end) to it.part }

    @Test
    fun `names each string by what datastar reads it as, in the order of the file`() {
        val text =
            """
            fun FlowContent.f() {
                val plain = "hello"
                input { dataBind("name") }
                div { dataOnClick("$d{'$d'}count++") }
                div { dataOnClick(post("/x") { payloadExpr = "1" }) }
                div { attributes["data-text"] = "2" }
                call.patchElements("<p id=\"a\">3</p>")
            }
            """.trimIndent()
        assertEquals(
            listOf(
                "SignalName" to "name",
                "Expression" to "$d{'$d'}count++",
                "Expression" to "1",
                "AttributeName" to "data-text",
                "Expression" to "2",
                "Html" to "<p id=\\\"a\\\">3</p>",
            ),
            roles(text),
        )
    }

    @Test
    fun `gives an expression its tokens where they stand in the source, a kotlin template left out`() {
        val text = "fun FlowContent.f(n: Int) { div { dataText(\"${d}n + \\${d}total\") } }"
        val found = strings(text).single()
        assertEquals(listOf("+" to TokenKind.OPERATOR, "$d" + "total" to TokenKind.SIGNAL), tokens(text, found))
    }

    @Test
    fun `gives a signal's name its tokens, from a dsl call and from the attributes form`() {
        val text = "fun FlowContent.f() { input { dataBind(\"user.name\"); attributes[\"data-bind\"] = \"open\" } }"
        val found = strings(text)
        assertEquals(listOf("SignalName" to "user.name", "AttributeName" to "data-bind", "SignalName" to "open"), roles(text, found))
        assertEquals(listOf("user" to TokenKind.SIGNAL, ".name" to TokenKind.SIGNAL_PATH), tokens(text, found[0]))
        assertEquals(listOf("open" to TokenKind.SIGNAL), tokens(text, found[2]))
    }

    @Test
    fun `gives an attribute's name its parts, under the project's prefix and under either bundle's`() {
        val text =
            "fun T.f() { attributes[\"data-star-on:click__once\"] = \"1\"; attributes[\"data-text\"] = \"2\"; " +
                "attributes[\"data-color\"] = \"3\" }"
        val found = strings(text, prefix = "data-star-")
        assertEquals(
            listOf("AttributeName" to "data-star-on:click__once", "Expression" to "1", "AttributeName" to "data-text", "Expression" to "2"),
            roles(text, found),
        )
        assertEquals(
            listOf(
                "data-star-" to NamePart.PREFIX,
                "on" to NamePart.PLUGIN,
                ":" to NamePart.KEY_SEPARATOR,
                "click" to NamePart.KEY,
                "__" to NamePart.MODIFIER_SIGIL,
                "once" to NamePart.MODIFIER,
            ),
            parts(text, (found[0] as DatastarString.AttributeName).parts),
        )
    }

    @Test
    fun `gives an html string its datastar names and values, each escape counted`() {
        val text = "fun g(n: Int) = \"<p class=\\\"a\\\" data-text=\\\"${d}n + \\${d}m\\\" data-bind=\\\"q\\\"></p>\""
        val found = strings(text).single() as DatastarString.Html
        assertEquals(
            listOf("data-" to NamePart.PREFIX, "text" to NamePart.PLUGIN, "data-" to NamePart.PREFIX, "bind" to NamePart.PLUGIN),
            parts(text, found.parts),
        )
        assertEquals(listOf("+" to TokenKind.OPERATOR, "${d}m" to TokenKind.SIGNAL, "q" to TokenKind.SIGNAL), tokens(text, found))
        assertTrue(found.byLook)
    }

    @Test
    fun `tells markup that only a call makes html from markup that looks it`() {
        val text = "suspend fun f(call: Call) { call.patchElements(\"hello\") }"
        val found = strings(text).single() as DatastarString.Html
        assertEquals(false, found.byLook)
    }

    @Test
    fun `gives an expression its prose, without a kotlin template and broken at an escape`() {
        val text = "fun f(who: String) { dataText(\"${d}m + 'Hello $d{who} and\\nmore' + \\\"x y\\\"\") }"
        val found = strings(text).single() as DatastarString.Expression
        assertEquals(listOf("'Hello ", " and", "more'", "\"x y"), found.prose.map { text.substring(it.first, it.last + 1) })
    }

    @Test
    fun `leaves a dsl call's string alone where the file has no dsl, and where the call is somebody else's`() {
        val text = "fun T.f() { dataBind(\"name\"); dataOnClick(\"$d{'$d'}a++\"); attributes[\"data-text\"] = \"1\" }"
        val kotlinxOnly = listOf("AttributeName" to "data-text", "Expression" to "1")
        assertEquals(kotlinxOnly, roles(text, strings(text, modules = Modules.known(true, false))))
        assertEquals(kotlinxOnly, roles(text, strings(text, foreign = { _, _ -> true })))
        val click = text.indexOf("dataOnClick")
        assertEquals(
            listOf("SignalName" to "name") + kotlinxOnly,
            roles(text, strings(text, foreign = { _, at -> at == click })),
        )
    }

    @Test
    fun `reads a string handed to a helper inside a call as the helper's, and a named one as the expression`() {
        val text = "fun T.f() { dataOnClick(getExpr(\"/a\")); dataOnClick(expression = \"1\"); dataOnClick(other(\"2\")) }"
        assertEquals(listOf("Expression" to "/a", "Expression" to "1"), roles(text))
    }

    @Test
    fun `gives nothing for the value of an attribute that takes no expression, and nothing for half a concatenation`() {
        val text = "fun T.f(x: String) { attributes[\"data-ignore\"] = \"a\"; attributes[\"data-text\"] = \"1 + \" + x }"
        assertEquals(listOf("AttributeName" to "data-ignore", "AttributeName" to "data-text"), roles(text))
    }
}
