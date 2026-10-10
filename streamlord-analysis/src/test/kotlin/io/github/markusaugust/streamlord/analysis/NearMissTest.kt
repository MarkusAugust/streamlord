package io.github.markusaugust.streamlord.analysis

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Mistakes that parse as something else: an argument after an underscore, a modifier after a dot, an indicator with nothing to show. */
class NearMissTest {
    private val analyzer = Analyzer()
    private val opts = AnalyzeOptions("data-", true)

    private fun fixed(
        src: String,
        issue: Issue,
    ): String = issue.fixes.first().let { src.substring(0, it.start) + it.text + src.substring(it.end) }

    @Test
    fun `an argument joined with an underscore is offered its dot`() {
        val src = """<input data-on:input__debounce_150ms="@get('/search')">"""
        val issue = analyzer.analyzeHtml(src, opts).single()

        assertEquals("unknown-modifier", issue.code)
        assertTrue("__debounce.150ms" in issue.message, issue.message)
        assertEquals("""<input data-on:input__debounce.150ms="@get('/search')">""", fixed(src, issue))
    }

    @Test
    fun `a modifier joined with a dot is offered its two underscores`() {
        val src = """<a data-on:click__prevent.stop="@post('/x')"></a>"""
        val issue = analyzer.analyzeHtml(src, opts).single()

        assertEquals("modifier-args", issue.code)
        assertEquals("""<a data-on:click__prevent__stop="@post('/x')"></a>""", fixed(src, issue))
    }

    @Test
    fun `an underscore after a flag, or after a name that is no modifier, gets no dotted fix`() {
        val issues = analyzer.analyzeHtml("""<a data-on:click__prevent_x="1" data-on:click__nope_5="1"></a>""", opts)

        assertEquals(listOf("unknown-modifier", "unknown-modifier"), issues.map { it.code })
        assertTrue(issues.none { "follow a dot" in it.message })
    }

    @Test
    fun `an indicator on an element that sends nothing is flagged`() {
        val issues = analyzer.analyzeHtml("""<span data-indicator:busy></span>""", opts)

        assertEquals(listOf("indicator-without-action"), issues.map { it.code })
    }

    @Test
    fun `an indicator beside any attribute that sends a request is fine`() {
        val fine =
            listOf(
                """<button data-on:click="@post('/save')" data-indicator:busy></button>""",
                """<div data-init="@get('/feed')" data-indicator="loading"></div>""",
                """<div data-effect="${'$'}q &amp;&amp; @get('/search')" data-indicator:busy></div>""",
                """<form data-on:submit__prevent="@put('/x', {contentType: 'form'})" data-indicator:saving></form>""",
            )
        for (src in fine) assertEquals(emptyList(), analyzer.analyzeHtml(src, opts).map { it.code }, src)
    }

    @Test
    fun `an element with template syntax is left alone`() {
        assertEquals(emptyList(), analyzer.analyzeHtml("""<button data-on:click="{{ action }}" data-indicator:busy></button>""", opts).map { it.code })
    }
}
