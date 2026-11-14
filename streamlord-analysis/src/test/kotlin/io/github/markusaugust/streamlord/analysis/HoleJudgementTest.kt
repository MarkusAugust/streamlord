package io.github.markusaugust.streamlord.analysis

import io.github.markusaugust.streamlord.fixtures.assertGrowsLinearly
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the corpus cannot hold: the editor's own facts about a hole, and the time a large file
 * takes.
 */
class HoleJudgementTest {
    private val analyzer = Analyzer()

    private fun interpolation(
        src: String,
        opts: Source,
    ) = analyzer.analyzeKotlin(opts.copy(text = src)).filter { it.code == "unescaped-interpolation" }.map { it.severity }

    @Test
    fun `the editor's fact wins over what the text shows`() {
        // The text takes both for the author's own: a literal of markup, a workspace constant.
        val src = "fun f(b: Boolean) = \"<p>\${if (b) \"<b>x</b>\" else \"<i>y</i>\"} \$LABEL</p>\""
        val text = Source("", SourceLanguage.KOTLIN, constants = setOf("LABEL"))
        assertEquals(emptyList(), interpolation(src, text))
        val typed = text.copy(valueFact = { _, _ -> ValueFact.STRING })
        assertEquals(listOf(Severity.WARNING, Severity.WARNING), interpolation(src, typed))
    }

    @Test
    fun `a message that advises interpolate without offering the rewrite says why`() {
        val src = "fun card(title: String) = \"<h2>\${title.escaped()}</h2>\""
        val issue = analyzer.analyzeKotlin(src).single()
        assertTrue("build the string with interpolate(...)" in issue.message, issue.message)
        assertEquals(emptyList(), issue.fixes.filter { it.title.startsWith("Build this string") })
        assertTrue("is not offered here" in issue.message, issue.message)
    }

    @Test
    fun `a value whose shape says it is a number or a boolean gets the rewrite and is never marked as markup`() {
        // Trusted takes a String, so Trusted(n.dec()) does not compile for an Int; interpolate writes a number as it is.
        val values =
            listOf(
                "n.dec()",
                "n.inc()",
                "xs.size",
                "s.length",
                "xs.count()",
                "xs.count { it.isEmpty() }",
                "n * 7 % 13",
                "a - b",
                "-n",
                "!flag",
                "a == b",
                "a < b",
                "s.isEmpty()",
                "xs.indexOf(s)",
                "s in xs",
                "n.toInt() / 2",
            )
        for (value in values) {
            val src = "fun f(n: Int, a: Int, b: Int, flag: Boolean, s: String, xs: List<String>) = \"<td>\${$value}</td>\""
            val titles =
                analyzer
                    .analyzeKotlin(src)
                    .single()
                    .fixes
                    .map { it.title }
            assertTrue(titles.any { it.startsWith("Build this string") }, "$value: $titles")
            assertTrue(titles.none { it.startsWith("Mark") }, "$value: $titles")
        }
        // A generic call is no comparison, and a call of the author's may still give markup.
        val generic = "fun <T> foo(a: T) = \"\$a\"\nfun f(a: String) = \"<td>\${foo<String>(a)}</td>\""
        assertTrue(
            analyzer
                .analyzeKotlin(generic)
                .single()
                .fixes
                .none { it.title.startsWith("Build this string") },
        )
    }

    /** The issues on [src] where the editor says the last [value] in it is [fact], as code and the text they cover. */
    private fun told(
        src: String,
        value: String,
        fact: ValueFact,
    ): List<Pair<String, String>> {
        val at = src.lastIndexOf(value)
        val opts =
            Source(src, SourceLanguage.KOTLIN, valueFact = { start, end ->
                if (start == at &&
                    end == at + value.length
                ) {
                    fact
                } else {
                    null
                }
            })
        return analyzer.analyzeKotlin(opts).map { (it.code ?: "") to src.substring(it.start, it.end) }
    }

    @Test
    fun `a Trusted the editor resolves to another package is no markup the text reads in place`() {
        // A star import leaves the name Streamlord's to the text, which reads the literal as an open <a href=.
        val src = "import com.acme.*\n\nfun f(x: String) = \"<div>\${Trusted(\"<a href=\")}\$x</div>\""
        assertEquals(listOf("unsafe-interpolation" to "\$x"), told(src, "", ValueFact.STRING))
        assertEquals(
            listOf("unescaped-interpolation" to "\${Trusted(\"<a href=\")}", "unescaped-interpolation" to "\$x"),
            told(src, "\${Trusted(\"<a href=\")}", ValueFact.OTHER),
        )
    }

    @Test
    fun `a Trusted the editor resolves to another package is not let into a script`() {
        val src = "import com.acme.*\n\nfun f(x: String) = interpolate(\"<script>%s</script>\", Trusted(x))"
        assertEquals(emptyList(), told(src, "", ValueFact.STRING))
        assertEquals(listOf("unsafe-interpolation" to "%s"), told(src, "Trusted(x)", ValueFact.OTHER))
    }

    @Test
    fun `a value in a Datastar expression or a script is sent to an attribute read with el dataset, never to a signal`() {
        // A String written into data-signals is refused by the same rule, so a signal is no way out.
        val sources =
            listOf(
                "fun f(q: String) = \"<p data-show=\\\"\${q}\\\">x</p>\"",
                "fun f(q: String) = interpolate(\"<p data-text=\\\"%s\\\">x</p>\", q)",
                "fun f(q: String) = interpolate(\"<p data-text=\\\"\${q}\\\">x</p>\")",
                "fun f(q: String) = interpolate(\"<script>%s</script>\", q)",
                "fun f(q: String) = \"<script>\$q</script>\"",
            )
        for (src in sources) {
            val message = analyzer.analyzeKotlin(src).single().message
            assertTrue("in a signal" !in message && "or a signal" !in message, message)
            if ("script" !in src) assertTrue("el.dataset" in message, message)
        }
    }

    @Test
    fun `the editor is asked about a value without the comment after it`() {
        // Only the editor knows the value is a number, which a style takes.
        val src = "fun f(n: Any) = interpolate(\"<div style=\\\"%s\\\"></div>\", n /* id */ // id\n)"
        assertEquals(listOf("unsafe-interpolation" to "%s"), told(src, "", ValueFact.NUMBER))
        assertEquals(emptyList(), told(src, "n", ValueFact.NUMBER))
    }

    @Test
    fun `a file of many functions with HTML is analysed in time that grows with it`() {
        val q = "\"\"\""
        var copy = 0
        // A package of its own each time, so nothing of an earlier pass is cached for it.
        val file = { n: Int ->
            buildString {
                append("package p${copy++}\n")
                for (i in 0 until n) {
                    append("fun f$i(q: String, k: Int): String {\n    val x = 1\n    // language=HTML\n")
                    append("    return $q<div title=\"\$q\"><p>\${k}</p><a href=\"/x/\$q\">y</a></div>$q\n}\n")
                }
            }
        }
        assertGrowsLinearly("functions with HTML", 100, file) { src ->
            assertTrue(analyzer.analyzeKotlin(src).count { it.code == "unescaped-interpolation" || it.code == "unsafe-interpolation" } > 0)
        }
    }
}
