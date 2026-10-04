package io.github.markusaugust.streamlord.analysis

import kotlin.test.Test
import kotlin.test.assertEquals

/** The cases of the VS Code extension's `callSites.test.ts`. */
class CallSitesTest {
    private val analyzer = Analyzer()
    private val opts = AnalyzeOptions("data-", true)
    private val raw = "\"\"\""

    private fun kotlin(src: String) = analyzer.analyzeKotlin(src, opts)

    private fun codes(src: String) = kotlin(src).map { it.code }

    private fun fix(
        issue: Issue,
        title: String,
    ) = issue.fixes.first { it.title.startsWith(title) }

    @Test
    fun `sees a selector and a mode given by position`() {
        assertEquals(emptyList(), kotlin("patchElements(\"<tr></tr>\", \"#rows\", ElementPatchMode.APPEND)"))
        assertEquals(listOf("mode-needs-selector"), codes("patchElements(\"<li>x</li>\", null, ElementPatchMode.APPEND)"))
    }

    @Test
    fun `sees the arguments given by name`() {
        assertEquals(listOf("missing-id"), codes("patchElements(elements = \"<b>x</b>\")"))
        assertEquals(emptyList(), kotlin("patchElements(elements = \"<b>x</b>\", selector = \"#a\")"))
        assertEquals(listOf("blank-selector"), codes("removeElements(selector = \"\")"))
        assertEquals(listOf("script-close"), codes("executeScript(script = \"x</script>\")"))
    }

    @Test
    fun `reads an imported mode constant and does not guess at a variable`() {
        assertEquals(listOf("mode-needs-selector"), codes("patchElements(\"<li>x</li>\", mode = APPEND)"))
        assertEquals(emptyList(), kotlin("patchElements(\"<li>x</li>\", mode = someMode)"))
        assertEquals(listOf("missing-id"), codes("patchElements(\"<li>x</li>\", mode = ElementPatchMode.DEFAULT)"))
    }

    @Test
    fun `finds the end of a script whatever comes before it`() {
        val src = "executeScript(\"İİ x</SCRIPT>\")"
        val issue = kotlin(src).single()
        assertEquals("</SCRIPT", src.substring(issue.start, issue.end))
    }

    @Test
    fun `keeps the escape of a dollar when it renames the signal after it`() {
        val backslash = "dataText(\"\\\$a-b\")"
        assertEquals("dataText(\"\\\$aB\")", fix(kotlin(backslash)[0], "Change to").apply(backslash))
        val idiom = "dataText(\"\${'\$'}a-b\")"
        assertEquals("dataText(\"\${'\$'}a - b\")", fix(kotlin(idiom)[0], "Write").apply(idiom))
        val multi = "dataText(\$\$\"\$a-b\")"
        assertEquals("dataText(\$\$\"\$aB\")", fix(kotlin(multi)[0], "Change to").apply(multi))
    }

    @Test
    fun `keeps a literal dollar in front of a template in the multi-dollar fix`() {
        val src = "dataOnClick(\"\$a + \$\$b\")"
        val onA = kotlin(src).first { src.substring(it.start, it.end) == "\$a" }
        assertEquals("dataOnClick(\$\$\$\"\$a + \$\$\$\$b\")", fix(onA, "Make it").apply(src))
    }

    @Test
    fun `replaces the trim call together with the literal when it offers a helper`() {
        val src = "dataOnClick($raw\$count++$raw.trimIndent())"
        assertEquals("dataOnClick(increment(\"count\"))", fix(kotlin(src)[0], "Use").apply(src))
    }
}
