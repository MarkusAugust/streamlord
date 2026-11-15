package io.github.markusaugust.streamlord.analysis

import io.github.markusaugust.streamlord.core.json.JsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SignalsTest {
    /** The reads of [refs] that name no signal of [defined], as an editor asks the project. */
    private fun unknown(
        refs: List<SignalReference>,
        defined: Set<String>,
    ) = projectSignalIssues(SourceLanguage.HTML, refs, Defined(defined))

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
            // Every signal the definitions name is where a declaration names it, and every declaration's name counts as
            // defined, one Streamlord refuses too, for go to declaration and rename.
            val declared = declarations.map { it.name }.toSet()
            assertTrue(declared.containsAll(definitions.filterNot { it == "*" || ':' in it }), name)
            assertEquals(emptyList(), unknown(declared.map { SignalReference(0, it.length + 1, it) }, definitions), name)
        }
    }
}
