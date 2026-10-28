package io.github.markusaugust.streamlord.analysis

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A hyphen in a signal name, which the SDK refuses at runtime, flagged where it is written with the name to write instead. */
class SignalNameHyphensTest {
    private val analyzer = Analyzer()

    private fun hyphens(src: String): List<Issue> = analyzer.analyzeKotlin(src).filter { it.code == "signal-name-hyphen" }

    private fun fixed(src: String): String =
        hyphens(
            src,
        ).flatMap { it.fixes }.sortedByDescending { it.start }.fold(src) { s, f -> s.replaceRange(f.start, f.end, f.text) }

    private val dsl = "import io.github.markusaugust.streamlord.html.*\n"

    @Test
    fun `a hyphen in each place that names a signal is an error with the camelCase fix`() {
        val src =
            dsl +
                """
                fun page() = div {
                  dataSignals("foo-bar", "1")
                  dataSignals("count" to 0, "user-name" to "")
                  dataComputed("full-name", "1")
                  dataBind("form.first-name")
                  dataRef("my-ref")
                  dataIndicator("is-busy")
                  dataMatchMedia("is-dark", "(prefers-color-scheme: dark)")
                  dataText(signal("form.first-name"))
                  dataOnClick(statements(increment("click-count"), toggle("is-open"), set("a-b", 1)))
                }
                suspend fun send(stream: DatastarStream) {
                  stream.patchSignals("foo-bar" to 1, "ok" to 2)
                  stream.removeSignals("ok", "foo-bar")
                }
                """.trimIndent()
        val issues = hyphens(src)
        assertTrue(issues.all { it.severity == Severity.ERROR })
        assertEquals(
            listOf(
                "foo-bar",
                "user-name",
                "full-name",
                "form.first-name",
                "my-ref",
                "is-busy",
                "is-dark",
                "form.first-name",
                "click-count",
                "is-open",
                "a-b",
                "foo-bar",
                "foo-bar",
            ),
            issues.map { src.substring(it.start + 1, it.end - 1) },
        )
        val after = fixed(src)
        assertEquals(emptyList(), hyphens(after))
        assertTrue("dataBind(\"form.firstName\")" in after && "signal(\"form.firstName\")" in after, after)
        assertTrue("or form['first-name'] for a key with a hyphen in it" in issues[7].message, issues[7].message)
    }

    @Test
    fun `a key with a hyphen that is data is left alone`() {
        val src =
            dsl +
                """
                fun page() = div {
                  dataSignals("user" to mapOf("first-name" to ""))
                  dataSignals("{'foo-bar': 1}")
                  dataSignals("{'foo-bar': 1}", true)
                  dataComputed("{'full-name': () => 1}")
                  dataText(signal("user['first-name']"))
                  dataOn("my-event", "x()")
                  dataClass("is-open", "true")
                }
                suspend fun send(stream: DatastarStream) {
                  stream.patchSignals("user" to mapOf("first-name" to null))
                  stream.patchSignals(${"\"\"\""}{"foo-bar": 1}${"\"\"\""})
                }
                """.trimIndent()
        assertEquals(emptyList(), hyphens(src))
    }

    // set("user-id", x) on a cache, Hamcrest's not("x"): somebody else's helpers.
    @Test
    fun `an expression helper that is not the DSL's is left alone`() {
        assertEquals(emptyList(), hyphens("fun f() { cache.set(\"user-id\", 1); signal(\"a-b\") }"))
        assertEquals(emptyList(), hyphens(dsl + "fun f() { cache.set(\"user-id\", 1) }"))
        assertEquals(emptyList(), hyphens(dsl + "import org.hamcrest.Matchers.not\nfun f() = not(\"a-b\")"))
    }

    @Test
    fun `a name passed by name is read too`() {
        val src =
            dsl +
                "fun p() = div { dataBind(signal = \"a-b\"); dataSignals(name = \"c-d\", expression = \"1\"); dataText(signal(name = \"e-f\")) }"
        assertEquals(listOf("a-b", "c-d", "e-f"), hyphens(src).map { src.substring(it.start + 1, it.end - 1) })
    }

    // A header's set, a map's set, a codec's respondSignals and a Boolean ifMissing in a variable.
    @Test
    fun `a name that may not be a signal's is left alone`() {
        val src =
            dsl +
                """
                fun t() = testApplication { client.get("/") { headers { set("datastar-request", "true") } } }
                fun m(map: MutableMap<String, Int>) = with(map) { set("user-id", 1) }
                suspend fun r(call: ApplicationCall) = call.respondSignals("a-b" to 1)
                fun p(flag: Boolean) = div { dataSignals("a-b", flag); dataSignals("x-${'$'}{flag}", "1") }
                """.trimIndent()
        assertEquals(emptyList(), hyphens(src))
    }

    @Test
    fun `the fix and the bracket form leave a key inside brackets as it is`() {
        val src =
            dsl +
                "fun p() = div { dataText(signal(\"form.first-name['x-y']\")); dataText(signal(\"items[0]-x\")); dataText(signal(\"a-b.c-d\")) }"
        val issues = hyphens(src)
        assertEquals(listOf("form.firstName['x-y']", "items[0]X", "aB.cD"), issues.map { it.fixes.single().text })
        assertTrue(issues.none { "for a key with a hyphen" in it.message }, issues.joinToString { it.message })
        val one = hyphens(dsl + "fun p() = div { dataText(signal(\"form.first-name.x\")) }").single()
        assertTrue("or form['first-name'].x for a key" in one.message, one.message)
        val named =
            dsl + "fun p() = div { dataComputed(name = \"a-b\", expression = \"1\"); dataMatchMedia(signal = \"c-d\", mediaQuery = \"x\") }"
        assertEquals(listOf("a-b", "c-d"), hyphens(named).map { named.substring(it.start + 1, it.end - 1) })
    }

    @Test
    fun `a name written with escapes is reported without a fix`() {
        val issue = hyphens(dsl + "fun p() = div { dataBind(\"a\\u002db\") }").single()
        assertEquals(emptyList(), issue.fixes)
    }
}
