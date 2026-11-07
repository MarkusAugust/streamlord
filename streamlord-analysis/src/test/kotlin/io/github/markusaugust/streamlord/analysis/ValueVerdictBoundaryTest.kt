package io.github.markusaugust.streamlord.analysis

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Which fact a value has is asked in ValueVerdict.kt, and what a literal's text opens with in
 * ValueTypes.kt, each with its TS twin, so a rule is given once and named. A copy anywhere else
 * drifts from them, as the tag starts did. When this fails, call the function there, or add one
 * there; do not widen the test.
 */
class ValueVerdictBoundaryTest {
    private val sources = File("src/main/kotlin/io/github/markusaugust/streamlord/analysis")
    private val comparison =
        Regex("""[=!]=\s*ValueFact\.|ValueFact\.[A-Z_]+\s*[=!]=|ValueFact\.[A-Z_]+(?:\s*,\s*ValueFact\.[A-Z_]+)*\s*->""")
    private val tagStart = Regex(Regex.escape("<(?:[A-Za-z]"))

    private fun found(
        rule: Regex,
        vararg owners: String,
    ): String {
        assertTrue(sources.isDirectory, "${sources.absolutePath} is not there; update the path in this test")
        return sources
            .listFiles { f -> f.extension == "kt" && f.name !in owners }!!
            .sortedBy { it.name }
            .flatMap { file ->
                file
                    .readLines()
                    .withIndex()
                    .filter { (_, line) -> rule.containsMatchIn(line) }
                    .map { (n, line) -> "${file.name}:${n + 1}: ${line.trim()}" }
            }.joinToString("\n")
    }

    @Test
    fun `only the verdict asks which fact a value has`() {
        assertEquals("", found(comparison, "ValueVerdict.kt", "ValueTypes.kt"))
    }

    @Test
    fun `only the value types say what a literal opens with`() {
        assertEquals("", found(tagStart, "ValueTypes.kt"))
    }
}
