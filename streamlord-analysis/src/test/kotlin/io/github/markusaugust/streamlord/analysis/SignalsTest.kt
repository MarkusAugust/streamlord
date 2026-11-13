package io.github.markusaugust.streamlord.analysis

import io.github.markusaugust.streamlord.core.json.JsonArray
import kotlin.test.Test
import kotlin.test.assertEquals

class SignalsTest {
    @Test
    fun `the signals a file declares and defines`() {
        for (case in sharedCases("signals.json")) {
            val name = case.string("name")
            val src = case.string("src")!!
            val language = SourceLanguage.valueOf(case.string("language")!!.uppercase())
            val source = Source(src, language, htmlDsl = if (case.boolean("withoutDsl") == true) false else null)
            val definitions = collectSignalDefinitions(source)
            case.array("signals")?.let { assertEquals(it.strings(), collectSignals(source).sorted(), name) }
            case.array("definitions")?.let { assertEquals(it.strings(), definitions.sorted(), name) }
            case.array("encoded")?.let { assertEquals(it.strings(), definitions.filter { d -> d == "*" || ':' in d }, name) }
            val declarations = signalDeclarations(source)
            case.array("declarations")?.let { expected ->
                val written = declarations.map { listOf(it.name, src.substring(it.start, it.end)) }
                assertEquals(expected.map { (it as JsonArray).strings() }, written, name)
            }
            assertEquals(definitions.filterNot { it == "*" || ':' in it }.toSet(), declarations.map { it.name }.toSet(), name)
        }
    }
}
