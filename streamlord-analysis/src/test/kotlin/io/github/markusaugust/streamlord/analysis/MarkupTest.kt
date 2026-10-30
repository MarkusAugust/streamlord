package io.github.markusaugust.streamlord.analysis

import io.github.markusaugust.streamlord.core.json.JsonArray
import io.github.markusaugust.streamlord.core.json.JsonParser
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** How long the markup takes to read, and where a comment ends; what it reports is in the shared corpus. */
class MarkupTest {
    private val analyzer = Analyzer()
    private val opts = AnalyzeOptions("data-", true)

    private fun html(src: String) = analyzer.analyzeHtml(src, opts)

    // Each of these took seconds, or minutes, with the form it replaces and takes milliseconds now:
    // the limit leaves a slow machine a wide margin.
    private fun within(
        ms: Long,
        block: () -> Unit,
    ) {
        // The first analysis loads the catalog, which is no part of what is timed.
        html("<div data-text=\"1\"></div>")
        val start = System.nanoTime()
        block()
        val took = (System.nanoTime() - start) / 1_000_000
        assertTrue(took < ms, "took $took ms")
    }

    @Test
    fun `reads a filter string full of escapes without backtracking`() {
        within(1000) { html("<pre data-json-signals=\"{include: '/${"\\\\".repeat(19)}x'}\"></pre>") }
        val path = (0 until 16).joinToString("\\\\.") { "a$it" }
        within(1000) { html("<pre data-json-signals='{\"include\": \"/^$path\"}'></pre>") }
    }

    @Test
    fun `reads a line of slashes that never close in linear time`() {
        // A tenth of it first, so that the time measured is not the compiler's.
        html("<div data-text=\"${"(/[".repeat(2000)}\"></div>")
        within(2000) { html("<div data-text=\"${"(/[".repeat(20000)}\"></div>") }
    }

    @Test
    fun `tells each signal in a string the client pairs otherwise in linear time`() {
        // 60 KB of rewrites inside one string, each told which quote opens it; a tenth first, so the compiler is not timed.
        fun page(n: Int) = "<div data-text=\"/'/.test('x') + '${"\$a ".repeat(n)}'\"></div>"
        html(page(2000))
        within(2000) { html(page(20000)) }
    }

    /** `conformance/dom/comment-ends.json`: where a comment ends, as the browser's parser reads it. */
    @Test
    fun `a comment ends where the HTML tokenizer ends it`() {
        val cases = (JsonParser.parse(File("../conformance/dom/comment-ends.json").readText()) as JsonArray).objects()
        assertEquals(16, cases.size)
        for (case in cases) {
            val input = case.string("input")!!
            assertEquals(case.boolean("element"), tokenize(input).tags.any { it.name == "b" && !it.closing }, input)
        }
    }
}
