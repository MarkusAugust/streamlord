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
            val modules = if (case.boolean("withoutDsl") == true) Modules.known(true, false) else Modules.UNKNOWN
            val source = Source(src, language, case.string("prefix") ?: "data-", modules)
            val definitions = collectSignalDefinitions(source)
            case.array("signals")?.let { assertEquals(it.strings(), collectSignals(source).sorted(), name) }
            case.array("definitions")?.let { assertEquals(it.strings(), definitions.sorted(), name) }
            case.array("encoded")?.let { assertEquals(it.strings(), definitions.filter { d -> d == "*" || ':' in d }, name) }
            val declarations = signalDeclarations(source)
            case.array("declarations")?.let { expected ->
                val written = declarations.map { listOf(it.name, src.substring(it.start, it.end)) }
                assertEquals(expected.map { (it as JsonArray).strings() }, written, name)
            }
            // A name Streamlord refuses is still where the call declares it, for go to declaration and rename.
            val named = definitions.map { it.removePrefix(REFUSED) }.filterNot { it == "*" || ':' in it }
            assertEquals(named.toSet(), declarations.map { it.name }.toSet(), name)
        }
    }
}
