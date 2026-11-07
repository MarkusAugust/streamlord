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
        Regex(
            """[=!]=\s*ValueFact\.|ValueFact\.[A-Z_]+\s*[=!]=|ValueFact\.[A-Z_]+(?:\s*,\s*ValueFact\.[A-Z_]+)*\s*->|""" +
                """\bin\s+(?:setOf|listOf|arrayOf|setOfNotNull)\(\s*ValueFact\.|\.equals\(\s*ValueFact\.|""" +
                """import\s+[\w.]*\bValueFact\.(?:[A-Z_]+|\*)""",
        )

    /** A first tag as a regex writes it: whitespace from the start and a `<`, `<(?:[A-Za-z]`, `<[a-zA-Z]`, or the placeholder as a tag name. */
    private val tagStart = Regex("""\^(?:\\s|\[[^\]]*\])\*<|<\(\?:\[(?:A-Za-z|a-zA-Z|a-z)|<\[(?:A-Za-z|a-zA-Z)\]|__kt__\(\?=""")

    private val comment = Regex("""//[^\n]*|/\*[\s\S]*?\*/""")

    private fun found(
        rule: Regex,
        vararg owners: String,
    ): String {
        assertTrue(sources.isDirectory, "${sources.absolutePath} is not there; update the path in this test")
        return sources
            .walkTopDown()
            .filter { it.extension == "kt" && it.name !in owners }
            .sortedBy { it.path }
            .flatMap { file ->
                val text = file.readText()
                // A comment may name what the code may not do.
                val code = comment.replace(text) { m -> m.value.replace(Regex("[^\n]"), " ") }
                rule.findAll(code).map { m ->
                    val line = code.take(m.range.first).count { it == '\n' }
                    "${file.name}:${line + 1}: ${text.lines()[line].trim()}"
                }
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
