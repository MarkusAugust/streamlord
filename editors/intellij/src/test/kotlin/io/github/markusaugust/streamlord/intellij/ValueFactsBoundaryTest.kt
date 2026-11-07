package io.github.markusaugust.streamlord.intellij

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The plugin's facts about the values joined into HTML are read from the types the Kotlin plugin
 * resolves. What a type name or a literal's text means is streamlord-analysis's rule
 * (`ValueFact.ofTypeName`, `isMarkupCollection`, `opensWithTag`), held once there with its TS
 * twin, and a copy here would drift from them. When this fails, call the analysis's function, or
 * add one there; do not widen the test.
 */
class ValueFactsBoundaryTest {
    private val adapter =
        File(
            System.getProperty("streamlord.repo"),
            "editors/intellij/src/main/kotlin/io/github/markusaugust/streamlord/intellij/analysis/HoleFacts.kt",
        )
    private val textRule = Regex("""\bRegex\s*\(|\.toRegex\s*\(""")

    @Test
    fun `the value facts read types and leave the text rules to the analysis`() {
        assertTrue("${adapter.absolutePath} is not there; update the path in this test", adapter.isFile)
        val found =
            adapter
                .readLines()
                .withIndex()
                .filter { (_, line) -> textRule.containsMatchIn(line) }
                .map { (n, line) -> "${adapter.name}:${n + 1}: ${line.trim()}" }
        assertEquals("", found.joinToString("\n"))
    }
}
