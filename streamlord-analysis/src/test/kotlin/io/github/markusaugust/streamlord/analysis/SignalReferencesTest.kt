package io.github.markusaugust.streamlord.analysis

import kotlin.test.Test
import kotlin.test.assertEquals

class SignalReferencesTest {
    private val analyzer = Analyzer()

    @Test
    fun `where a file reads a signal, and which reads name none it defines`() {
        for (case in twinCases("signal-references.json")) {
            val name = case.string("name")
            case.array("defined")?.let { array ->
                val defined = array.strings().toSet()
                val path = case.string("path")!!
                if (case.has("known")) assertEquals(case.boolean("known"), isKnownSignal(path, defined), name)
                if (case.has("nearest")) assertEquals(case.string("nearest"), nearestSignal(path, defined), name)
                continue
            }
            val src = case.string("src")!!
            val language = SourceLanguage.valueOf(case.string("language")!!.uppercase())
            val defined = collectSignalDefinitions(src, language)
            val kotlin = language == SourceLanguage.KOTLIN
            val refs = if (kotlin) analyzer.signalReferencesInKotlin(src) else analyzer.signalReferencesInHtml(src)
            val issues = refs.mapNotNull { unknownSignalIssue(it, defined) }
            case["refs"]?.let { assertEquals(it.toKotlin(), refs.map { r -> listOf(src.substring(r.start, r.end), r.name) }, name) }
            case.array("unknown")?.let { assertEquals(it.strings(), issues.map { i -> src.substring(i.start, i.end) }, name) }
            case.array("codes")?.let { assertEquals(it.strings(), issues.map { i -> i.code }, name) }
            case.array("messages")?.let { assertEquals(it.strings(), issues.map { i -> i.message }, name) }
            case["fixes"]?.let { expected ->
                val fixes = issues.map { i -> i.fixes.map { f -> listOf(src.substring(f.start, f.end), f.text) } }
                assertEquals(expected.toKotlin(), fixes, name)
            }
            case.array("signals")?.let { assertEquals(it.strings(), collectSignals(src, language).sorted(), name) }
            case.array("definitions")?.let { assertEquals(it.strings(), defined.sorted(), name) }
        }
    }
}
