package io.github.markusaugust.streamlord.analysis

import kotlin.test.Test
import kotlin.test.assertEquals

/** A hyphen in a signal name, which the SDK refuses at runtime, flagged where it is written with the name to write instead. */
class SignalNameHyphensTest {
    private val analyzer = Analyzer()

    @Test
    fun `a hyphen in a signal name`() {
        for (case in twinCases("signal-name-hyphens.json")) {
            val name = case.string("name")
            val src = case.string("src")!!
            val issues = analyzer.analyzeKotlin(src).filter { it.code == "signal-name-hyphen" }
            val fixes = issues.flatMap { it.fixes }.sortedByDescending { it.start }
            val fixed = fixes.fold(src) { s, f -> s.replaceRange(f.start, f.end, f.text) }
            case.array("names")?.let { assertEquals(it.strings(), issues.map { i -> src.substring(i.start + 1, i.end - 1) }, name) }
            case["fixes"]?.let { assertEquals(it.toKotlin(), issues.map { i -> i.fixes.map { f -> f.text } }, name) }
            case.array("messages")?.let { assertEquals(it.strings(), issues.map { i -> i.message }, name) }
            case.array("severities")?.let { assertEquals(it.strings(), issues.map { i -> i.severity.name.lowercase() }, name) }
            case.string("fixed")?.let { assertEquals(it, fixed, name) }
        }
    }
}
