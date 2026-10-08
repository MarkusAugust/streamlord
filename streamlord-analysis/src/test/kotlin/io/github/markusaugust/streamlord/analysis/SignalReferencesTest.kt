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
        assertEquals(setOf("count", "form", "email"), collectSignalDefinitions(src, SourceLanguage.KOTLIN))
    }

    @Test
    fun `no fix when nothing is close`() {
        assertNull(nearestSignal("telefon", setOf("teller")))
        assertEquals("teller", nearestSignal("telelr", setOf("teller", "total")))
    }
}
