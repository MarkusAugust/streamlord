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
    private val adapter =
        File(
            System.getProperty("streamlord.repo"),
            "editors/intellij/src/main/kotlin/io/github/markusaugust/streamlord/intellij/analysis/HoleFacts.kt",
        )
    private val textRule = Regex("""\bRegex\s*\(|\.toRegex\s*\(""")
    private val comparison =
        Regex("""[=!]=\s*ValueFact\.|ValueFact\.[A-Z_]+\s*[=!]=|ValueFact\.[A-Z_]+(?:\s*,\s*ValueFact\.[A-Z_]+)*\s*->""")

    private fun found(rule: Regex): String {
        assertTrue("${adapter.absolutePath} is not there; update the path in this test", adapter.isFile)
        return adapter
            .readLines()
            .withIndex()
            .filter { (_, line) -> rule.containsMatchIn(line) }
            .joinToString("\n") { (n, line) -> "${adapter.name}:${n + 1}: ${line.trim()}" }
    }

    @Test
    fun `the value facts read types and leave the text rules to the analysis`() {
        assertEquals("", found(textRule))
    }

    @Test
    fun `the value facts leave what a fact says to the analysis`() {
        assertEquals("", found(comparison))
    }
}
