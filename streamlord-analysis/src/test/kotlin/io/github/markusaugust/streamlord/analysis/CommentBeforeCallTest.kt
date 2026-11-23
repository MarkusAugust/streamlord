package io.github.markusaugust.streamlord.analysis

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A comment that ends in a full stop on the line above a call does not make it a call on a receiver. */
class CommentBeforeCallTest {
    private val analyzer = Analyzer()
    private val dsl = "import io.github.markusaugust.streamlord.html.*\n"

    private fun ends(
        src: String,
        at: Int,
    ) = fileFacts(src).let { lineEnds(src, it.code, it.comments, at) }

    @Test
    fun `a literal handed to a parameter marked HTML is markup under such a comment`() {
        val src =
            "import org.intellij.lang.annotations.Language\nfun show(@Language(\"HTML\") html: String) = html\n" +
                "fun f() {\n    // Shows it.\n    show(\"<p>x</p>\")\n}"
        assertTrue(src.indexOf("\"<p>") in fileFacts(src).htmlArguments)
    }

    @Test
    fun `what Trusted takes is markup under such a comment`() {
        val src = "import io.github.markusaugust.streamlord.core.domain.*\nfun f() {\n    // Trusted.\n    Trusted(\"<p>x</p>\")\n}"
        assertTrue(src.indexOf("\"<p>") in fileFacts(src).markupLiterals)
    }

    @Test
    fun `a catch under such a comment has its block after the line`() {
        val src = "fun f() {\n    try { g() } // tried.\n    catch (e: Exception)\n        { h() }\n}"
        assertEquals(false, ends(src, src.indexOf('\n', src.indexOf("catch"))))
    }

    @Test
    fun `a literal whose text after a template reads like a comment is no comment before a while`() {
        for (literal in listOf("\"\${dir}/*.kt\"", "\"\${dir}// y\"")) {
            val src = "fun f(dir: String, c: Boolean) {\n    val p = $literal\n    while (c)\n        g()\n}"
            assertEquals(false, ends(src, src.indexOf('\n', src.indexOf("while"))), literal)
        }
    }

    @Test
    fun `a comment in a template's code is passed over at a line's end`() {
        val src = "fun f() = \"\${listOf(1, // one.\n    2)}\""
        assertEquals(false, ends(src, src.indexOf('\n')))
    }

    @Test
    fun `a comment against a literal at the next line's start does not carry the line on`() {
        val src = "fun f() {\n    val x = a\n        /* c */\"x\".length\n    val y = 2\n}"
        assertEquals(true, ends(src, src.indexOf('\n', src.indexOf("= a"))))
    }

    @Test
    fun `a short helper on a receiver in a template is no call of the helper`() {
        assertEquals(true, callsShortHelper("// Sets it.\nfun f() = set(\"x\", 1)"))
        assertEquals(false, callsShortHelper("fun f() = \"\${obj.set(\"x\", 1)}\""))
        // A call in a comment or a string is none, as the readings take it.
        assertEquals(false, callsShortHelper("// Someone else's set(\"count\", 1) defines no signal.\nfun f() = 1"))
        assertEquals(false, callsShortHelper("val note = \"set(x)\""))
        assertEquals(true, callsShortHelper("val s = \"\${set(\"x\", 1)}\""))
    }

    @Test
    fun `an option of an action under such a comment is an expression`() {
        val src =
            "${dsl}fun FlowContent.f() {\n    div { dataOnClick(post(\"/x\") {\n        // Sends it.\n        payloadExpr = \"1\" }) }\n}"
        assertEquals(listOf("payloadExpr"), analyzer.expressionOptions(src).map { it.name })
    }

    @Test
    fun `a helper under such a comment reads its signal`() {
        val src = "${dsl}fun FlowContent.f() {\n    div { dataShow(\n        // Shown.\n        signal(\"open\")) }\n}"
        val refs = analyzer.signalReferences(Source(src, SourceLanguage.KOTLIN))
        assertTrue(refs.any { it.name == "open" }, refs.toString())
    }

    @Test
    fun `a stream's call under such a comment is checked as core's`() {
        val src =
            "import io.github.markusaugust.streamlord.core.*\nfun f(sse: DatastarStream) { sse.run {\n" +
                "    // Sends the update to the page.\n    patchSignals(\"{bad\") } }"
        val issues = analyzer.analyzeKotlin(src).map { it.message }
        assertTrue(issues.any { "signals must be one JSON object" in it }, issues.toString())
    }

    @Test
    fun `a call under such a comment reads no signal off the comment's last word`() {
        val src = "fun h(call: ApplicationCall) {\n    // Not one of the signals.\n    string(\"query\")\n}"
        assertEquals(emptyList(), collectSignalFacts(Source(src, SourceLanguage.KOTLIN)).lookups.map { it.name })
    }

    @Test
    fun `a short helper under such a comment is read without the DSL where the file has none`() {
        val index = ProjectIndex(hasDsl = { false })
        val src = "fun FlowContent.x() {\n    // Sets it.\n    set(\"ghost\", 1)\n    input { dataBind(\"own\") }\n}"
        index.put("Other.kt", src, SourceLanguage.KOTLIN)
        assertEquals(listOf("own"), index.declarations("Other.kt") { src }?.map { it.name })
    }
}
