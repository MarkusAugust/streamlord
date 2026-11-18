package io.github.markusaugust.streamlord.analysis

import io.github.markusaugust.streamlord.core.catalog.CatalogNames
import kotlin.test.Test
import kotlin.test.assertEquals

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
    ) = found.map {
        when (it) {
            is DatastarString.Expression -> "Expression"
            is DatastarString.SignalName -> "SignalName"
            is DatastarString.AttributeName -> "AttributeName"
            is DatastarString.Html -> "Html"
        } to text.substring(it.string.contentStart, it.string.contentEnd)
    }

    private fun tokens(
        text: String,
        s: DatastarString,
    ) = s.tokens.map { text.substring(it.start, it.end) to it.kind }

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
            parts(text, found[0].parts),
        )
    }

    @Test
    fun `gives an html string its datastar names and values, each escape counted`() {
        val text = "fun g(n: Int) = \"<p class=\\\"a\\\" data-text=\\\"${d}n + \\${d}m\\\" data-bind=\\\"q\\\"></p>\""
        val found = strings(text).single()
        assertEquals(
            listOf("data-" to NamePart.PREFIX, "text" to NamePart.PLUGIN, "data-" to NamePart.PREFIX, "bind" to NamePart.PLUGIN),
            parts(text, found.parts),
        )
        assertEquals(listOf("+" to TokenKind.OPERATOR, "${d}m" to TokenKind.SIGNAL, "q" to TokenKind.SIGNAL), tokens(text, found))
    }

    @Test
    fun `takes the markup handed to a call for html where the file has core, and a string that reads as html anywhere`() {
        val text = "suspend fun f(call: Call) { call.patchElements(\"hello\"); val p = \"<p id=\\\"a\\\">x</p>\" }"
        assertEquals(listOf("Html" to "hello", "Html" to "<p id=\\\"a\\\">x</p>"), roles(text))
        assertEquals(listOf("Html" to "<p id=\\\"a\\\">x</p>"), roles(text, strings(text, modules = Modules.known(false, false))))
    }

    @Test
    fun `reads a string that looks like html as html though a dsl call is handed it`() {
        val text = "fun T.f() { dataOnClick(\"<p data-text=\\\"1\\\"></p>\") }"
        assertEquals(listOf("Html" to "<p data-text=\\\"1\\\"></p>"), roles(text))
    }

    @Test
    fun `gives an expression its prose, without a kotlin template and broken at an escape`() {
        val text = "fun f(who: String) { dataText(\"${d}m + 'Hello $d{who} and\\nmore' + \\\"x y\\\"\") }"
        val found = strings(text).single() as DatastarString.Expression
        // The words a speller is left with: a quote is no letter, the template and the escape are gone.
        val words = found.prose.flatMap { Regex("[A-Za-z]+").findAll(text.substring(it.first, it.last + 1)).map { m -> m.value } }
        assertEquals(listOf("Hello", "and", "more", "x", "y"), words)
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

    @Test
    fun `reads a filter as the expression it is, and each value of a map option`() {
        val text =
            "fun T.f() { attributes[\"data-persist\"] = \"{include: /^a/}\"; " +
                "dataOnClick(get(\"/x\") { headerExprs = mapOf(\"X-Id\" to \"$d{'$d'}id\") }) }"
        val found = strings(text)
        assertEquals(
            listOf("AttributeName" to "data-persist", "Expression" to "{include: /^a/}", "Expression" to "$d{'$d'}id"),
            roles(text, found),
        )
        assertEquals(listOf("include" to TokenKind.OPTION_KEY, "/^a/" to TokenKind.REGEX), tokens(text, found[1]))
    }

    @Test
    fun `counts the dollars before a multi-dollar literal into it, and reads a raw string trimmed as written`() {
        val text = "fun T.f(id: Int) { dataShow($d$d\"${d}open && $d$d{id} > 0\"); dataText(\"\"\"$d{'$d'}n + 'Hey'\"\"\".trimIndent()) }"
        val found = strings(text)
        assertEquals(text.indexOf("$d$d\""), found[0].string.start)
        assertEquals(
            listOf("${d}open" to TokenKind.SIGNAL, "&&" to TokenKind.OPERATOR, ">" to TokenKind.OPERATOR, "0" to TokenKind.NUMBER),
            tokens(text, found[0]),
        )
        val prose = (found[1] as DatastarString.Expression).prose.joinToString(" ") { text.substring(it.first, it.last + 1) }
        assertEquals(listOf("Hey"), Regex("[A-Za-z]+").findAll(prose).map { it.value }.toList())
    }

    @Test
    fun `gives no tokens for a signal's name with a kotlin template in it, nor for a blank expression`() {
        val text = "fun T.f(i: Int) { dataBind(\"row${d}i\"); dataText(\" \\n\") }"
        val found = strings(text)
        assertEquals(listOf("SignalName", "Expression"), roles(text, found).map { it.first })
        assertEquals(emptyList(), tokens(text, found[0]) + tokens(text, found[1]))
    }

    @Test
    fun `reads an attribute's name under the prefix given and either bundle's, and its value by its kind`() {
        val text = analyzer.datastarName("DATA-ON:click", "x-")!!
        assertEquals(listOf(NamePart.PREFIX, NamePart.PLUGIN, NamePart.KEY_SEPARATOR, NamePart.KEY), text.parts.map { it.part })
        assertEquals(null, analyzer.datastarName("data-color", "data-"))
        assertEquals(null, analyzer.datastarName("x-nothing", "x-"))

        val expression = analyzer.datastarName("x-text", "x-")!!
        // The signal and the operator; what the engine writes gets no token.
        assertEquals(listOf(0 to 6, 7 to 8), expression.valueTokens("${d}count + {{ x }}", listOf(9..15)).map { it.start to it.end })
        assertEquals(listOf(9..12), expression.prose("${d}count + 'Hi'"))

        val signal = analyzer.datastarName("data-bind", "data-")!!
        assertEquals(listOf(TokenKind.SIGNAL), signal.valueTokens("name").map { it.kind })
        assertEquals(emptyList(), signal.prose("name"))

        val other = analyzer.datastarName("data-ignore", "data-")!!
        assertEquals(emptyList(), other.valueTokens("anything"))
        assertEquals(null, other.prose("anything"))
    }
}
