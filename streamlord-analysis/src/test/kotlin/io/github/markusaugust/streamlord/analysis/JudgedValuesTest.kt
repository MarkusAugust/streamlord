package io.github.markusaugust.streamlord.analysis

import io.github.markusaugust.streamlord.core.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

/** The cases of `cases/judged-values.json`, asked of `analyzeHtml`, `analyzeKotlin`, `complete` and `signatureHelp`. */
class JudgedValuesTest {
    private val analyzer = Analyzer()
    private val workspace = Workspace(listOf("foo", "bar"), emptyList(), emptyList(), "workspace")

    private fun issues(found: List<Issue>) = found.map { "${it.code} ${it.start}-${it.end}" }

    /** [value] in [attribute] of a div, quoted with the quote it does not hold; [caret] is where `‸` stood in it, or -1. */
    private fun page(
        attribute: String,
        value: String,
    ): Pair<Source, Int> {
        val quote = if ('"' in value) "'" else "\""
        val opening = "<div $attribute=$quote"
        val caret = value.indexOf('‸')
        val text = opening + value.replace("‸", "") + "$quote></div>"
        return Source(text, SourceLanguage.HTML) to (if (caret < 0) -1 else opening.length + caret)
    }

    private fun check(case: JsonObject) {
        val name = case.string("name")
        case.string("kotlin")?.let { call ->
            val src = "import io.github.markusaugust.streamlord.html.*\nfun f() = elements { div { $call } }"
            assertEquals(case.array("issues")!!.strings(), issues(analyzer.analyzeKotlin(src)), name)
            return
        }
        val attribute = case.string("attribute")!!
        case.string("value")?.let { value ->
            assertEquals(case.array("issues")!!.strings(), issues(analyzer.analyzeHtml(page(attribute, value).first)), name)
            return
        }
        val (source, caret) = page(attribute, case.string("caret")!!)
        assertEquals(case.array("labels")!!.strings(), analyzer.complete(source, caret, workspace).items.map { it.label }, name)
        val expected = case.obj("signature")
        val help = analyzer.signatureHelp(source, caret)
        assertEquals(expected?.string("label") to expected?.int("active"), help?.label to help?.active, name)
    }

    @Test
    fun `each reader says of each value what the cases hold`() =
        eachCase(sharedCases("judged-values.json"), { it.string("name")!! }, ::check)
}
