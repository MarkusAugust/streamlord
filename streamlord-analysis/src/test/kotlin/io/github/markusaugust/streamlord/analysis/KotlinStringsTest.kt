package io.github.markusaugust.streamlord.analysis

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class KotlinStringsTest {
    private val analyzer = Analyzer()

    @Test
    fun `the string literals and call sites of Kotlin source`() {
        for (case in twinCases("kotlin-strings.json")) {
            val name = case.string("name")
            val src = case.string("src")!!
            case.int("at")?.let { at ->
                val s = assertNotNull(readKotlinStringAt(src, at), name)
                case.string("text")?.let { assertEquals(it, s.text, name) }
                case["interpolations"]?.let {
                    assertEquals(it.toKotlin(), s.interpolations.map { i -> listOf(i.kind.name.lowercase(), i.text) }, name)
                }
            }
            case["sites"]?.let { expected ->
                val sites = findCallSites(src, case.array("calls")!!.strings().toSet())
                assertEquals(expected.toKotlin(), sites.map { site -> site.args.map { a -> a.string?.text } }, name)
            }
            val issues = analyzer.analyzeKotlin(src)
            case.array("codes")?.let { assertEquals(it.strings(), issues.map { i -> i.code }, name) }
            case["starts"]?.let { assertEquals(it.toKotlin(), issues.map { i -> i.start.toLong() }, name) }
        }
    }
}
