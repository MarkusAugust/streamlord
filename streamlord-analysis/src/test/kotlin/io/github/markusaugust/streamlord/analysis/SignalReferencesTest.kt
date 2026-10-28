package io.github.markusaugust.streamlord.analysis

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SignalReferencesTest {
    private val analyzer = Analyzer()

    private fun refs(src: String) = analyzer.signalReferencesInKotlin(src).map { src.substring(it.start, it.end) to it.name }

    private fun unknown(
        src: String,
        language: SourceLanguage = SourceLanguage.KOTLIN,
    ): List<Issue> {
        val defined = collectSignalDefinitions(src, language)
        val refs = if (language == SourceLanguage.KOTLIN) analyzer.signalReferencesInKotlin(src) else analyzer.signalReferencesInHtml(src)
        return refs.mapNotNull { unknownSignalIssue(it, defined) }
    }

    @Test
    fun `a misspelt signal in a multi-dollar string is unknown`() {
        val q = "\"\"\""
        val src =
            """
            fun f(): String = $$$q
                <div data-signals:teller="0">
                  <button data-on:click="${'$'}telefon++">Øk</button>
                  <span data-text="${'$'}teller"></span>
                </div>
            $q
            """.trimIndent()
        val issues = unknown(src)
        assertEquals(listOf("\$telefon"), issues.map { src.substring(it.start, it.end) })
        assertEquals("unknown-signal", issues[0].code)
    }

    @Test
    fun `a near name is offered as the fix, and the dollar is kept as written`() {
        val src = "fun f() = \"\"\"<div data-signals:count=\"0\" data-text=\"\${'\$'}coutn\"></div>\"\"\""
        val issue = unknown(src).single()
        assertEquals("\${'\$'}coutn", src.substring(issue.start, issue.end))
        val fix = issue.fixes.single()
        assertEquals("count", fix.text)
        assertEquals("coutn", src.substring(fix.start, fix.end))
        assertTrue(issue.message.contains("Did you mean \$count?"), issue.message)
    }

    @Test
    fun `the expression handed to a dsl call is read`() {
        val src = "fun f() { dataText(\"\\\$total + \\\$tax\") }"
        assertEquals(listOf("\\\$total" to "total", "\\\$tax" to "tax"), refs(src))
    }

    @Test
    fun `a kotlin template is not a signal`() {
        val src = "fun f(teller: Int) = \"\"\"<button data-on:click=\"\$teller++\">x</button>\"\"\""
        assertEquals(emptyList(), refs(src))
    }

    @Test
    fun `a path reaches into a defined signal, and an object holds its paths`() {
        val defined = setOf("user", "form.email")
        assertTrue(isKnownSignal("user.name.length", defined))
        assertTrue(isKnownSignal("form", defined))
        assertTrue(isKnownSignal("form.email", defined))
        assertTrue(!isKnownSignal("forms", defined))
    }

    @Test
    fun `an assignment defines the signal, a comparison does not`() {
        val html = "<button data-on:click=\"\$open = true\"></button><p data-show=\"\$open && \$shown == 1\"></p>"
        assertEquals(listOf("\$shown"), unknown(html, SourceLanguage.HTML).map { html.substring(it.start, it.end) })
    }

    // signal("count") is $count: the helpers that read a signal by name read it, and set, setExpr
    // and toggle assign it, which defines it.
    @Test
    fun `a helper that reads a signal by name reads it, and one that assigns defines it`() {
        val src =
            """
            import io.github.markusaugust.streamlord.html.*

            fun f() = div {
                dataSignals("count", "0")
                dataText(signal("cuont"))
                dataOn("click", increment("count"))
                dataShow(not("opn"))
                dataOn("click", toggle("open"))
                dataOn("click", set("total", 1))
                dataText(signal("total") + signal("foo-bar") + signal("items[0]") + signal("user.name"))
            }
            """.trimIndent()
        val issues = unknown(src)
        assertEquals(listOf("cuont", "opn", "user.name"), issues.map { src.substring(it.start, it.end) })
        assertEquals("count", issues[0].fixes.single().text)
        assertEquals("open", issues[1].fixes.single().text)
        assertTrue("cuont" in collectSignals(src, SourceLanguage.KOTLIN))
    }

    // A helper of the same name elsewhere, Hamcrest's not or a Redis increment, is somebody else's.
    @Test
    fun `a helper is read only in a file that uses the DSL, and only without a receiver`() {
        val dsl = "import io.github.markusaugust.streamlord.html.*\n"
        assertEquals(emptyList(), unknown("fun t() { assertThat(x, not(\"error\")) }"))
        assertEquals(emptyList(), unknown(dsl + "fun t() { redis.opsForValue().increment(\"visits\"); filter.not(\"x\") }"))
        val hamcrest = "import io.github.markusaugust.streamlord.html.dataText\nimport org.hamcrest.Matchers.not\n"
        assertEquals(emptyList(), unknown(hamcrest + "fun t() { assertThat(status, not(\"error\")) }"))
        assertEquals(emptyList(), unknown(dsl + "import org.hamcrest.Matchers.*\nfun t() { assertThat(status, not(\"error\")) }"))
        assertEquals(emptyList(), unknown(dsl + "val m = Matchers.\n" + " ".repeat(60) + "not(\"zzz\")"))
        val named = dsl + "fun t() = signal(name = \"nope\")"
        assertEquals(listOf("nope"), unknown(named).map { named.substring(it.start, it.end) })
    }

    // signal("foo-bar") is refused at runtime, so a fix never writes a hyphen into a helper.
    @Test
    fun `a helper's fix offers only a name it writes as it stands`() {
        val src =
            "import io.github.markusaugust.streamlord.html.*\n" +
                "val p = \"\"\"<div data-signals=\"{'foo-bar': 1}\"></div>\"\"\"\nval t = signal(\"fooBar\")"
        val issues = unknown(src)
        assertEquals(listOf("fooBar"), issues.map { src.substring(it.start, it.end) })
        assertEquals(emptyList(), issues.single().fixes)
    }

    // The codec names the fields. For a @Serializable class the analysis reads them; for a class it
    // cannot read, or a value of no named class, any name is defined.
    @Test
    fun `a page declared from toJsonValue defines what the codec can write`() {
        val form = "@Serializable data class Form(val email: String = \"\")\n"
        val reads = "; dataText(signal(\"emial\")) }"
        val cases =
            mapOf(
                "fun page() = div { dataSignals(codec.toJsonValue(Form()))" to listOf("emial"),
                "fun page(form: Form) = div { dataSignals(codec.toJsonValue(form))" to listOf("emial"),
                "val j = codec.toJsonValue(Form())\nfun page() = div { dataSignals(j)" to listOf("emial"),
                "fun page() = div { dataSignals(codec.toJsonValue(JacksonForm()))" to emptyList(),
                "fun page(form: JsonObject) = div { dataSignals(form)" to emptyList(),
                "fun page() = div { dataSignals(codec.toJsonValue(load()))" to emptyList(),
            )
        for ((page, expected) in cases) {
            val src = "import io.github.markusaugust.streamlord.html.*\n" + form + page + reads
            assertEquals(expected, unknown(src).map { src.substring(it.start, it.end) }, page)
        }
    }

    @Test
    fun `markup files read bare signals without defining them`() {
        val html = "<div data-signals:count=\"0\"><span data-text=\"\$cuont\"></span><span data-text=\"\$count\"></span></div>"
        assertEquals(listOf("\$cuont"), unknown(html, SourceLanguage.HTML).map { html.substring(it.start, it.end) })
        // Completion still offers every name it sees.
        assertTrue("cuont" in collectSignals(html, SourceLanguage.HTML))
    }

    @Test
    fun `strings, kebab-case and template syntax are left alone`() {
        val html =
            "<p data-text=\"'\$nope' + \$foo-bar\"></p><p data-text=\"{{ x }} + \$nope\"></p>"
        assertEquals(emptyList(), unknown(html, SourceLanguage.HTML).map { html.substring(it.start, it.end) })
    }

    @Test
    fun `serializable properties and dsl writes define signals`() {
        val src =
            """
            @Serializable data class S(val query: String = "")
            fun f() { patchSignals("total" to 1); dataText("\${'$'}query + \${'$'}total") }
            """.trimIndent()
        assertEquals(emptyList(), unknown(src))
    }

    @Test
    fun `a dollar inside a javascript identifier is not a signal`() {
        val html = "<button data-on:click=\"foo\$bar() + \$\$x\"></button>"
        assertEquals(emptyList(), analyzer.signalReferencesInHtml(html))
    }

    @Test
    fun `a case modifier names the signal`() {
        val html =
            "<div data-signals:my-value__case.snake=\"1\" data-signals:big-box__case.pascal=\"1\" data-signals:plain-key=\"1\">" +
                "<span data-text=\"\$my_value + \$BigBox + \$plainKey\"></span></div>"
        assertEquals(emptyList(), unknown(html, SourceLanguage.HTML))
        assertEquals(setOf("my_value", "BigBox", "plainKey"), collectSignalDefinitions(html, SourceLanguage.HTML))
    }

    @Test
    fun `json text handed to patchSignals defines its keys`() {
        val q = "\"\"\""
        val src = "fun f() { sse.patchSignals($q{\"count\": 1, \"form\": {\"email\": \"\"}}$q) }"
        assertEquals(setOf("count", "form", "form.email"), collectSignalDefinitions(src, SourceLanguage.KOTLIN))
    }

    @Test
    fun `no fix when nothing is close`() {
        assertNull(nearestSignal("telefon", setOf("teller")))
        assertEquals("teller", nearestSignal("telelr", setOf("teller", "total")))
    }
}
