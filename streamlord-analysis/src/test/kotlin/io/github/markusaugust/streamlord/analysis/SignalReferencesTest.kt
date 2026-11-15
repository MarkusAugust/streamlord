package io.github.markusaugust.streamlord.analysis

import kotlin.test.Test
import kotlin.test.assertEquals

class SignalReferencesTest {
    /** The reads of [refs] that name no signal of [defined], as an editor asks the project. */
    private fun unknown(
        refs: List<SignalReference>,
        defined: Set<String>,
    ) = projectSignalIssues(SourceLanguage.HTML, refs, Defined(defined))

    private val analyzer = Analyzer()

    @Test
    fun `where a file reads a signal, and which reads name none it defines`() {
        for (case in sharedCases("signal-references.json")) {
            val name = case.string("name")
            case.array("defined")?.let { array ->
                val defined = array.strings().toSet()
                val path = case.string("path")!!
                // A read of the path alone, as signalReferences gives one: known where no issue is, and the nearest name its fix writes.
                val issues = unknown(listOf(SignalReference(0, path.length + 1, path)), defined)
                if (case.has("known")) assertEquals(case.boolean("known"), issues.isEmpty(), name)
                if (case.has("nearest")) {
                    assertEquals(
                        case.string("nearest"),
                        issues
                            .singleOrNull()
                            ?.fixes
                            ?.singleOrNull()
                            ?.text,
                        name,
                    )
                }
                continue
            }
            val src = case.string("src")!!
            val language = SourceLanguage.valueOf(case.string("language")!!.uppercase())
            val defined = collectSignalDefinitions(Source(src, language))
            val kotlin = language == SourceLanguage.KOTLIN
            val refs = analyzer.signalReferences(Source(src, language))
            val issues = unknown(refs, defined)
            case["refs"]?.let { assertEquals(it.toKotlin(), refs.map { r -> listOf(src.substring(r.start, r.end), r.name) }, name) }
            case.array("unknown")?.let { assertEquals(it.strings(), issues.map { i -> src.substring(i.start, i.end) }, name) }
            case.array("codes")?.let { assertEquals(it.strings(), issues.map { i -> i.code }, name) }
            case.array("messages")?.let { assertEquals(it.strings(), issues.map { i -> i.message }, name) }
            case["fixes"]?.let { expected ->
                val fixes = issues.map { i -> i.fixes.map { f -> listOf(src.substring(f.start, f.end), f.text) } }
                assertEquals(expected.toKotlin(), fixes, name)
            }
            case.array("signals")?.let { assertEquals(it.strings(), collectSignals(Source(src, language)).sorted(), name) }
            case.array("definitions")?.let { assertEquals(it.strings(), defined.sorted(), name) }
        }
    }
}
