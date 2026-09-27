package io.github.markusaugust.streamlord.analysis

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The cases of the VS Code extension's `fixes.test.ts`. */
class FixesTest {
    private val analyzer = Analyzer()
    private val opts = AnalyzeOptions("data-", true)

    private fun withCode(
        issues: List<Issue>,
        code: String,
    ) = issues.filter { it.code == code }

    @Test
    fun `offers the dsl helper and the two escapes for an interpolated signal`() {
        val src = "dataOnClick(\"\$count++\")"
        val issue = withCode(analyzer.analyzeKotlin(src, opts), "kotlin-interpolation")[0]
        assertEquals(
            listOf(
                "Use increment(\"count\")",
                "Make it a \$\$ literal, where \$count is a signal",
                "Escape as \${'\$'}count",
                "Escape as \\\$count",
            ),
            issue.fixes.map { it.title },
        )
        assertEquals("dataOnClick(increment(\"count\"))", issue.fixes[0].apply(src))
        assertEquals("dataOnClick(\$\$\"\$count++\")", issue.fixes[1].apply(src))
        assertEquals("dataOnClick(\"\${'\$'}count++\")", issue.fixes[2].apply(src))
        assertEquals("dataOnClick(\"\\\$count++\")", issue.fixes[3].apply(src))
    }

    @Test
    fun `uses one more dollar than the longest run already in the text`() {
        val src = "dataOnClick(\"a \$\$count++ and \$\$\$x\")"
        val issues = withCode(analyzer.analyzeKotlin(src, opts), "kotlin-interpolation")
        val onCount = issues.first { src.substring(it.start, it.end) == "\$count" }
        val fix = onCount.fixes.first { it.title.startsWith("Make it") }
        assertEquals("Make it a \$\$\$\$ literal, where \$count is a signal", fix.title)
        assertEquals("dataOnClick(\$\$\$\$\"a \$\$count++ and \$\$\$\$x\")", fix.apply(src))
    }

    @Test
    fun `turns an html string into a multi-dollar literal keeping the kotlin templates kotlin`() {
        val src = "fun f(d: String, t: String) = \"\"\"<h2>\$t \${'\$'}x \${t.length}</h2><span data-text=\"\$d\"></span>\"\"\""
        val issues = withCode(analyzer.analyzeKotlin(src, opts), "kotlin-interpolation")
        val onD = issues.first { src.substring(it.start, it.end) == "\$d" }
        assertEquals(Severity.WARNING, onD.severity)
        assertEquals(listOf("Make it a \$\$ literal, where \$d is a signal", "Escape as \${'\$'}d"), onD.fixes.map { it.title })
        val fixed = onD.fixes[0].apply(src)
        assertEquals(
            "fun f(d: String, t: String) = \$\$\"\"\"<h2>\$\$t \$x \$\${t.length}</h2><span data-text=\"\$d\"></span>\"\"\"",
            fixed,
        )
        assertEquals(emptyList(), analyzer.analyzeKotlin(fixed, opts), "the result is clean")
        val onT = issues.first { src.substring(it.start, it.end) == "\$t" }
        assertEquals(Severity.HINT, onT.severity)
    }

    @Test
    fun `recognises the other helpers and skips them for mixed expressions`() {
        fun helper(src: String) = withCode(analyzer.analyzeKotlin(src, opts), "kotlin-interpolation")[0].fixes[0].title
        assertEquals("Use signal(\"user.name\")", helper("dataText(\"\$user.name\")"))
        assertEquals("Use not(\"open\")", helper("dataShow(\"!\$open\")"))
        assertEquals("Use decrement(\"n\")", helper("dataOnClick(\"\$n--\")"))
        assertEquals("Use toggle(\"open\")", helper("dataOnClick(\"\$open = !\$open\")"))
        assertEquals("Make it a \$\$ literal, where \$a is a signal", helper("dataOnClick(\"\$a + \$b\")"))
        val both = withCode(analyzer.analyzeKotlin("dataOnClick(\"\$a + \$b\")", opts), "kotlin-interpolation")
        assertEquals("dataOnClick(\$\$\"\$a + \$\$b\")", both[0].fixes[0].apply("dataOnClick(\"\$a + \$b\")"))
        assertEquals("dataOnClick(\$\$\"\$\$a + \$b\")", both[1].fixes[0].apply("dataOnClick(\"\$a + \$b\")"))
        val raw = withCode(analyzer.analyzeKotlin("dataText(\"\"\"\$count\"\"\")", opts), "kotlin-interpolation")[0]
        assertFalse(raw.fixes.any { it.title.startsWith("Escape as \\") }, "no backslash escape in raw strings")
    }

    @Test
    fun `adds a selector before the mode argument`() {
        val src = "s.patchElements(\"<li>x</li>\", mode = ElementPatchMode.APPEND)"
        val issue = withCode(analyzer.analyzeKotlin(src, opts), "mode-needs-selector")[0]
        assertEquals("s.patchElements(\"<li>x</li>\", selector = \"\", mode = ElementPatchMode.APPEND)", issue.fixes[0].apply(src))
    }

    @Test
    fun `fixes markup inside kotlin strings with source offsets`() {
        val src = "s.patchElements(\"\"\"<div>x</div><p id=\"a\" data-on:click__debunce.500ms=\"y()\"></p>\"\"\")"
        val issues = analyzer.analyzeKotlin(src, opts)
        val id = withCode(issues, "missing-id")[0]
        assertEquals(src.replace("<div>", "<div id=\"div\">"), id.fixes[0].apply(src))
        val mod = withCode(issues, "unknown-modifier")[0]
        assertEquals(src.replace("__debunce", "__debounce"), mod.fixes[0].apply(src))
        val action = withCode(analyzer.analyzeKotlin("dataOnClick(\"@Post('/x')\")", opts), "unknown-action")[0]
        assertEquals("dataOnClick(\"@post('/x')\")", action.fixes[0].apply("dataOnClick(\"@Post('/x')\")"))
    }

    @Test
    fun `links diagnostics to the reference`() {
        val issues =
            analyzer.analyzeKotlin(
                "dataOnClick(\"@Post('/x')\"); s.patchElements(\"\"\"<div data-signal:x=\"1\" data-on-intersect__threshold.150=\"y()\"></div>\"\"\")",
                opts,
            )
        assertTrue(issues.all { it.link?.startsWith("https://data-star.dev/") == true }, issues.map { it.code to it.link }.toString())
    }

    @Test
    fun `renames attributes and modifiers and adds a missing duration in html`() {
        val src = "<div data-signal:foo=\"1\" data-on:click__debunce=\"x()\" data-on-intersect__debounce=\"y()\"></div>"
        val issues = analyzer.analyzeHtml(src, opts)
        val attr = withCode(issues, "unknown-attribute")[0]
        assertEquals(src.replace("data-signal:foo", "data-signals:foo"), attr.fixes[0].apply(src))
        val mod = withCode(issues, "unknown-modifier")[0]
        assertEquals(src.replace("__debunce", "__debounce"), mod.fixes[0].apply(src))
        val dur = withCode(issues, "modifier-args")[0]
        assertEquals(src.replace("__debounce=", "__debounce.500ms="), dur.fixes[0].apply(src))
    }
}
