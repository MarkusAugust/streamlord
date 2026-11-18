package io.github.markusaugust.streamlord.analysis

import io.github.markusaugust.streamlord.fixtures.assertGrowsLinearly
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the corpus cannot hold: the editor's own facts about a placeholder, and the time a large file
 * takes.
 */
class ValueJudgementTest {
    private val analyzer = Analyzer()

    private fun interpolation(
        src: String,
        opts: Source,
    ) = analyzer.analyzeKotlin(opts.copy(text = src)).filter { it.code == IssueCode.UNESCAPED_INTERPOLATION }.map { it.severity }

    @Test
    fun `a value looked up in a map may be null, so marking it is offered and not preferred, and the message says why`() {
        // A template writes null for a key the map does not hold, and interpolate writes nothing.
        for (type in listOf("Map<String, String>", "MutableMap<String, String>", "HashMap<String, String>")) {
            val src = "fun f(m: $type) = \"<p title=\\\"\${m[\"k\"]}\\\">x</p>\""
            val issue = analyzer.analyzeKotlin(src).single()
            val rewrite = issue.fixes.single { it.title.startsWith("Mark the values with +") }
            assertEquals(false, rewrite.preferred, type)
            assertTrue("interpolate writes nothing for" in issue.message, issue.message)
        }
        // An index into a list is no lookup that may miss.
        val list = "fun f(m: List<String>) = \"<p title=\\\"\${m[0]}\\\">x</p>\""
        assertEquals(
            true,
            analyzer
                .analyzeKotlin(list)
                .single()
                .fixes
                .single()
                .preferred,
        )
    }

    @Test
    fun `a block written in the parentheses of interpolate is read as a block`() {
        val head = "import io.github.markusaugust.streamlord.core.domain.interpolate\n\n"
        val unmarked = head + "fun f(name: String) = interpolate({ \"<p title=\\\"\$name\\\">x</p>\" })"
        val issue = analyzer.analyzeKotlin(unmarked).single()
        assertEquals(IssueCode.INTERPOLATE_TEMPLATE, issue.code)
        assertEquals(
            head + "fun f(name: String) = interpolate({ \"<p title=\\\"\${+name}\\\">x</p>\" })",
            issue.fixes.single().apply(unmarked),
        )
        val refused = head + "fun f(name: String) = interpolate( { \"<p data-text=\\\"\${+name}\\\"></p>\" } )"
        assertEquals(
            listOf(IssueCode.UNSAFE_INTERPOLATION to Severity.ERROR),
            analyzer.analyzeKotlin(refused).map { it.code to it.severity },
        )
        val marked = head + "fun f(name: String) = interpolate({ \"<p title=\\\"\${+name}\\\">x</p>\" })"
        assertEquals(emptyList(), analyzer.analyzeKotlin(marked))
    }

    @Test
    fun `a this in a template of a string in a value, or after a quote character, keeps the string out of a block`() {
        val nested = "class Row(val name: String) {\n    fun cell() = \"<p title=\\\"\${\"Hi \${this.name}\".trim()}\\\">x</p>\"\n}"
        val quoted =
            "class Row(val a: String) {\n    fun cell(c: Char) = \"<p title=\\\"\${if (c == '\"') this.a else \"x\"}\\\">y</p>\"\n}"
        for (src in listOf(nested, quoted)) {
            val issues = analyzer.analyzeKotlin(src)
            assertEquals(emptyList(), issues.flatMap { it.fixes }.filter { it.title.startsWith("Mark the values with +") }, src)
            assertTrue(issues.any { "are the block's" in it.message }, issues.map { it.message }.toString())
        }
    }

    @Test
    fun `a name set to a mark is the mark where it is written, whatever letters it has`() {
        val head = "import io.github.markusaugust.streamlord.core.domain.interpolate\n\n"
        val src = head + "fun f(year: String) = interpolate {\n    val år = +year\n    \"<p title=\\\"\$år\\\">x</p>\"\n}"
        assertEquals(emptyList(), analyzer.analyzeKotlin(src).map { it.message })
    }

    @Test
    fun `a block after the prefixes in the parentheses, or passed by name, is read as a block`() {
        val head = "import io.github.markusaugust.streamlord.core.domain.interpolate\n\n"
        val after = head + "fun f(name: String) = interpolate(listOf(\"data-x-\"), { \"<p data-x-text=\\\"\${+name}\\\"></p>\" })"
        assertEquals(listOf(IssueCode.UNSAFE_INTERPOLATION to Severity.ERROR), analyzer.analyzeKotlin(after).map { it.code to it.severity })
        val named = head + "fun f(name: String) = interpolate(block = { \"<p title=\\\"\$name\\\">x</p>\" })"
        assertEquals(listOf(IssueCode.INTERPOLATE_TEMPLATE), analyzer.analyzeKotlin(named).map { it.code })
    }

    @Test
    fun `the editor's fact wins over what the text shows`() {
        // The text takes both for the author's own: a literal of markup, a workspace constant.
        val src = "fun f(b: Boolean) = \"<p>\${if (b) \"<b>x</b>\" else \"<i>y</i>\"} \$LABEL</p>\""
        val text = Source("", SourceLanguage.KOTLIN, constants = setOf("LABEL"))
        assertEquals(emptyList(), interpolation(src, text))
        val typed = text.copy(valueAnswer = { _, _ -> answerGiving(ValueFact.STRING) })
        assertEquals(listOf(Severity.WARNING, Severity.WARNING), interpolation(src, typed))
    }

    @Test
    fun `a message that advises interpolate without offering the rewrite says why`() {
        val src = "fun card(title: String) = \"<h2>\${title.escaped()}</h2>\""
        val issue = analyzer.analyzeKotlin(src).single()
        assertTrue("mark it with + in interpolate { }" in issue.message, issue.message)
        assertEquals(emptyList(), issue.fixes.filter { it.title.startsWith("Mark the values with +") })
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
                "x * 7 % 13",
                "-n",
                "!flag",
                "s.isEmpty()",
                "xs.indexOf(s)",
            )
        val declared = "n: Int, a: Int, b: Int, flag: Boolean, s: String, xs: List<String>, x: Any"
        for (value in values) {
            val src = "fun f($declared) = \"<td>\${$value}</td>\""
            val titles =
                analyzer
                    .analyzeKotlin(src)
                    .single()
                    .fixes
                    .map { it.title }
            assertTrue(titles.any { it.startsWith("Mark the values with +") }, "$value: $titles")
            assertTrue(titles.none { it.endsWith(" as markup") }, "$value: $titles")
        }
        // A comparison or a test is a Boolean whatever it compares, and arithmetic of numbers the text shows is a number.
        for (value in listOf("a == b", "a < b", "s in xs", "n * 7 % 13", "a - b", "n.toInt() / 2")) {
            assertEquals(emptyList(), analyzer.analyzeKotlin("fun f($declared) = \"<td>\${$value}</td>\""), value)
        }
        // A generic call is no comparison, and a call of the author's may still give markup.
        val generic = "fun <T> foo(a: T) = \"\$a\"\nfun f(a: String) = \"<td>\${foo<String>(a)}</td>\""
        assertTrue(
            analyzer
                .analyzeKotlin(generic)
                .single()
                .fixes
                .none { it.title.startsWith("Mark the values with +") },
        )
    }

    /** The issues on [src] where the editor says the last [value] in it is [fact], as code and the text they cover. */
    private fun told(
        src: String,
        value: String,
        fact: ValueFact,
    ): List<Pair<IssueCode, String>> {
        val at = src.lastIndexOf(value)
        val opts =
            Source(src, SourceLanguage.KOTLIN, valueAnswer = { start, end ->
                answerGiving(fact).takeIf {
                    start == at &&
                        end == at + value.length
                }
            })
        return analyzer.analyzeKotlin(opts).map { it.code to src.substring(it.start, it.end) }
    }

    @Test
    fun `a Trusted the editor resolves to another package is no markup the text reads in place`() {
        // A star import leaves the name Streamlord's to the text, which reads the literal as an open <a href=.
        val src = "import com.acme.*\n\nfun f(x: String) = \"<div>\${Trusted(\"<a href=\")}\$x</div>\""
        assertEquals(listOf(IssueCode.UNSAFE_INTERPOLATION to "\$x"), told(src, "", ValueFact.STRING))
        assertEquals(
            listOf(IssueCode.UNESCAPED_INTERPOLATION to "\${Trusted(\"<a href=\")}", IssueCode.UNESCAPED_INTERPOLATION to "\$x"),
            told(src, "\${Trusted(\"<a href=\")}", ValueFact.OTHER),
        )
    }

    @Test
    fun `a Trusted the editor resolves to another package is not let into a script`() {
        val src = "import com.acme.*\n\nfun f(x: String) = interpolate(\"<script>%s</script>\", Trusted(x))"
        assertEquals(emptyList(), told(src, "", ValueFact.STRING))
        assertEquals(listOf(IssueCode.UNSAFE_INTERPOLATION to "%s"), told(src, "Trusted(x)", ValueFact.OTHER))
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
        assertEquals(listOf(IssueCode.UNSAFE_INTERPOLATION to "%s"), told(src, "", ValueFact.NUMBER))
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
            assertTrue(
                analyzer.analyzeKotlin(src).count {
                    it.code == IssueCode.UNESCAPED_INTERPOLATION ||
                        it.code == IssueCode.UNSAFE_INTERPOLATION
                } >
                    0,
            )
        }
    }
}
