package io.github.markusaugust.streamlord.analysis

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class CallSitesTest {
    private val analyzer = Analyzer()

    @Test
    fun `the issues and fixes at a call site`() {
        for (case in twinCases("call-sites.json")) {
            val name = case.string("name")
            val src = case.string("src")!!
            val issues = analyzer.analyzeKotlin(src)
            case.array("codes")?.let { assertEquals(it.strings(), issues.map { i -> i.code }, name) }
            case.array("spans")?.let { assertEquals(it.strings(), issues.map { i -> src.substring(i.start, i.end) }, name) }
            case.obj("fix")?.let { want ->
                val on = want.string("on")
                val issue = issues.firstOrNull { on == null || src.substring(it.start, it.end) == on }
                val fix = assertNotNull(issue?.fixes?.firstOrNull { it.title.startsWith(want.string("title")!!) }, name)
                assertEquals(want.string("result"), fix.apply(src), name)
            }
            case.int("at")?.let { assertEquals(case.boolean("html"), analyzer.htmlStringAt(src, it) != null, name) }
        }
    }
}
