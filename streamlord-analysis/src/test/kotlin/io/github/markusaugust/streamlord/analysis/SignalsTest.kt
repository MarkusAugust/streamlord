package io.github.markusaugust.streamlord.analysis

import kotlin.test.Test
import kotlin.test.assertEquals

class SignalsTest {
    @Test
    fun `the signals a file declares and defines`() {
        for (case in twinCases("signals.json")) {
            val name = case.string("name")
            val src = case.string("src")!!
            val language = SourceLanguage.valueOf(case.string("language")!!.uppercase())
            val definitions = collectSignalDefinitions(src, language)
            case.array("signals")?.let { assertEquals(it.strings(), collectSignals(src, language).sorted(), name) }
            case.array("definitions")?.let { assertEquals(it.strings(), definitions.sorted(), name) }
            case.array("encoded")?.let { assertEquals(it.strings(), definitions.filter { d -> d == "*" || ':' in d }, name) }
        }
    }
}
