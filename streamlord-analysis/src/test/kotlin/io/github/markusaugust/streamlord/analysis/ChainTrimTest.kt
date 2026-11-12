package io.github.markusaugust.streamlord.analysis

import kotlin.test.Test
import kotlin.test.assertEquals

/** A trim call after one literal of a `+` chain trims that literal alone, as Kotlin binds it. */
class ChainTrimTest {
    private val analyzer = Analyzer()
    private val q = "\"\"\""

    private fun judged(src: String): List<Pair<String, String?>> =
        analyzer.analyzeKotlin(src).map { src.substring(it.start, it.end) to it.code }

    @Test
    fun `the margin of a literal that is not the last is trimmed, so a value after it lands where the page has it`() {
        val src = "fun f(u: String, name: String) = $q\n    |<a\n    |href=\"\$u\">$q.trimMargin() + name + \"</a>\""
        assertEquals(listOf("\$u" to "unsafe-interpolation", "name" to "unescaped-interpolation"), judged(src))
    }

    @Test
    fun `the trim call after the last literal leaves a margin marker in a literal before it`() {
        // The first literal keeps its `|`, so the attribute is `|href`, which is no URL.
        val src = "fun f(u: String, name: String) = $q<a\n|href=\"\$u\">$q + name + $q\n    |</a>$q.trimMargin()"
        assertEquals(listOf("\$u" to "unescaped-interpolation", "name" to "unescaped-interpolation"), judged(src))
    }
}
