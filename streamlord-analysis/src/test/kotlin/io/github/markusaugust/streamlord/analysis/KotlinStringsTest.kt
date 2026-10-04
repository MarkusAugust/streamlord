package io.github.markusaugust.streamlord.analysis

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** The cases of the VS Code extension's `kotlinStrings.test.ts`. */
class KotlinStringsTest {
    private val analyzer = Analyzer()
    private val opts = AnalyzeOptions("data-", true)
    private val raw = "\"\"\""

    private fun codes(issues: List<Issue>) = issues.map { it.code }

    private fun sites(src: String) = findCallSites(src, setOf("dataOnClick", "dataText"))

    @Test
    fun `points an end-of-input error in a raw string at its closing quotes`() {
        val src = "dataOnClick($raw@get('/x') +$raw)"
        val issue = analyzer.analyzeKotlin(src, opts).single()
        assertEquals("expression-syntax", issue.code)
        assertEquals(src.indexOf("$raw)"), issue.start)
        val quoted = "dataOnClick($raw\"x\" +$raw)"
        assertEquals(quoted.indexOf("$raw)"), analyzer.analyzeKotlin(quoted, opts)[0].start)
    }

    @Test
    fun `does not end a template at a brace in a character literal`() {
        assertEquals("__kt__ +", readKotlinStringAt($$"\"${if (c == '}') 1 else 2} +\"", 0)!!.text)
        assertEquals("__kt__ +", readKotlinStringAt($$"\"${x.map { '{' }} +\"", 0)!!.text)
    }

    @Test
    fun `reads a template whose name is not ascii`() {
        val s = readKotlinStringAt($$"\"$år + 1\"", 0)!!
        assertEquals(listOf(InterpolationKind.SIMPLE to "år"), s.interpolations.map { it.kind to it.text })
        assertEquals("__kt__ + 1", s.text)
    }

    @Test
    fun `takes a literal followed by trimIndent or trimMargin as the string argument`() {
        assertNotNull(sites($$"dataOnClick($$raw$count++$$raw.trimIndent())")[0].args[0].string)
        assertNotNull(sites($$"dataText($$raw\n  |$a\n$$raw.trimMargin(\"|\"))")[0].args[0].string)
        assertNull(sites("dataOnClick(\"x\".trim())")[0].args[0].string)
        assertNull(sites("dataOnClick(\"x\" + y)")[0].args[0].string)
        assertEquals(
            listOf("kotlin-interpolation"),
            codes(analyzer.analyzeKotlin($$"dataOnClick($$raw$count++$$raw.trimIndent())", opts)),
        )
        assertEquals(
            listOf("mode-needs-selector"),
            codes(analyzer.analyzeKotlin("patchElements($raw<li>x</li>$raw.trimIndent(), mode = ElementPatchMode.APPEND)", opts)),
        )
    }

    @Test
    fun `takes a literal with a comment before or after it as the string argument`() {
        assertNotNull(sites("dataOnClick(/* why */ \"x\")")[0].args[0].string)
        assertNotNull(sites("dataOnClick(\n  // why\n  \"x\",\n)")[0].args[0].string)
        assertNotNull(sites("dataText(\"x\" /* after */)")[0].args[0].string)
        assertEquals(1, sites("dataText(\n  \"x\",\n  // trailing\n)")[0].args.size)
    }
}
