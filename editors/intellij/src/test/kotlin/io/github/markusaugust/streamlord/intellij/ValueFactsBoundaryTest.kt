package io.github.markusaugust.streamlord.intellij

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The plugin's facts about the values joined into HTML are read from the types the Kotlin plugin
 * resolves. What a type name or a literal's text means is streamlord-analysis's rule
 * (`ValueFact.ofTypeName`, `isMarkupCollection`, `opensWithTag`), and so is what a fact says of
 * a value (`writesItself`, `isMarkup`), each held once there with its TS twin; a copy here would
 * drift from them. When this fails, call the analysis's function, or add one there; do not widen
 * the test.
 */
class ValueFactsBoundaryTest {
    private val sources = File(System.getProperty("streamlord.repo"), "editors/intellij/src/main/kotlin")
    private val adapter = File(sources, "io/github/markusaugust/streamlord/intellij/analysis/HoleFacts.kt")
    private val textRule = Regex("""\bRegex\s*\(|\.toRegex\s*\(""")
    private val comparison =
        Regex(
            """[=!]=\s*ValueFact\.|ValueFact\.[A-Z_]+\s*[=!]=|ValueFact\.[A-Z_]+(?:\s*,\s*ValueFact\.[A-Z_]+)*\s*->|""" +
                """\bin\s+(?:setOf|listOf|arrayOf|setOfNotNull)\(\s*ValueFact\.|\.equals\(\s*ValueFact\.|""" +
                """import\s+[\w.]*\bValueFact\.(?:[A-Z_]+|\*)""",
        )
    private val comment = Regex("""//[^\n]*|/\*[\s\S]*?\*/""")

    private fun found(
        rule: Regex,
        files: Sequence<File>,
    ): String =
        files
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

    @Test
    fun `the value facts read types and leave the text rules to the analysis`() {
        assertTrue("${adapter.absolutePath} is not there; update the path in this test", adapter.isFile)
        assertEquals("", found(textRule, sequenceOf(adapter)))
    }

    @Test
    fun `the plugin leaves what a fact says to the analysis`() {
        assertTrue("${sources.absolutePath} is not there; update the path in this test", sources.isDirectory)
        assertEquals("", found(comparison, sources.walkTopDown().filter { it.extension == "kt" }))
    }
}
