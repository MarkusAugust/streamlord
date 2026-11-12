package io.github.markusaugust.streamlord.analysis

import io.github.markusaugust.streamlord.core.json.JsonArray
import io.github.markusaugust.streamlord.core.json.JsonParser
import io.github.markusaugust.streamlord.fixtures.assertGrowsLinearly
import io.github.markusaugust.streamlord.fixtures.fixtureText
import kotlin.test.Test
import kotlin.test.assertEquals

/** How long the markup takes to read, and where a comment ends; what it reports is in the shared corpus. */
class MarkupTest {
    private val analyzer = Analyzer()
    private val opts = AnalyzeOptions("data-", true)

    private fun html(src: String) = analyzer.analyzeHtml(src, opts)

    // Each of these took seconds, or minutes, with the form it replaces, which read the markup again
    // or backtracked for each part of it.
    private fun grows(
        what: String,
        size: Int,
        page: (Int) -> String,
    ) = assertGrowsLinearly(what, size, page) { html(it) }

    @Test
    fun `reads a filter string full of escapes without backtracking`() {
        grows("a filter of escapes", 2000) { n -> "<pre data-json-signals=\"{include: '/${"\\\\".repeat(n)}x'}\"></pre>" }
        grows("a filter of escaped dots", 1000) { n ->
            "<pre data-json-signals='{\"include\": \"/^${(0 until n).joinToString("\\\\.") { "a$it" }}\"}'></pre>"
        }
    }

    @Test
    fun `reads a line of slashes that never close in linear time`() {
        grows("slashes that never close", 2000) { n -> "<div data-text=\"${"(/[".repeat(n)}\"></div>" }
    }

    @Test
    fun `tells each signal in a string the client pairs otherwise in linear time`() {
        // Rewrites inside one string, each told which quote opens it, and inside one template literal, each told why.
        grows("signals in one string", 2000) { n -> "<div data-text=\"/'/.test('x') + '${"\$a ".repeat(n)}'\"></div>" }
        grows("signals in one template literal", 2000) { n -> "<div data-text=\"`\${{a: 1}.a} ${"\$a ".repeat(n)}`\"></div>" }
    }

    /** `conformance/dom/comment-ends.json`: where a comment ends, as the browser's parser reads it. */
    @Test
    fun `a comment ends where the HTML tokenizer ends it`() {
        val cases = (JsonParser.parse(fixtureText("conformance/dom/comment-ends.json")) as JsonArray).objects()
        assertEquals(16, cases.size)
        for (case in cases) {
            val input = case.string("input")!!
            assertEquals(case.boolean("element"), tokenize(input).tags.any { it.name == "b" && !it.closing }, input)
        }
    }
}
