package io.github.markusaugust.streamlord.analysis

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The rewrite to `interpolate` an editor offers for a caret or a selection as a context action: `rewriteAt` over the analysed file. */
class RewriteAtTest {
    private val analyzer = Analyzer()
    private val import = "import io.github.markusaugust.streamlord.core.domain.interpolate\n\n"

    /** The rewrite offered in [src] from the first [from] to the end of the first [to]. */
    private fun offered(
        src: String,
        from: String,
        to: String = "",
    ): Fix? =
        rewriteAt(
            analyzer.analyzeKotlinSites(Source(src, SourceLanguage.KOTLIN)),
            src.indexOf(from),
            src.indexOf(to, src.indexOf(from)) + to.length,
        )

    private val card = "fun card(name: String) = \"<p class=\\\"x\\\">\$name</p>\""

    @Test
    fun `it is offered in the markup of a string whose value has a warning, and is never preferred`() {
        val fix = offered(card, "class")!!
        assertEquals(import + "fun card(name: String) = interpolate(\"<p class=\\\"x\\\">%s</p>\", name).text", fix.apply(card))
        assertEquals("Build this string with interpolate(...), which escapes the values" to false, fix.title to fix.preferred)
    }

    @Test
    fun `it is offered for a selection that lies in one string, and for none that reaches out of it`() {
        assertEquals(offered(card, "class")!!.apply(card), offered(card, "<p", "\\\"x\\\"")?.apply(card))
        assertNull(offered(card, "= \"", "class"))
    }

    @Test
    fun `it is left to the issue under the caret or the selection that offers it`() {
        assertNull(offered(card, "name</p>"))
        assertNull(offered(card, "class", "\$na"))
    }

    @Test
    fun `an issue with another fix over the whole literal does not stand for the rewrite`() {
        // The dollar trap's fix to a multi-dollar literal replaces the literal too, and is no rewrite to interpolate.
        val src = "fun f(count: String, name: String) = \"<p data-text=\\\"\$count\\\" title=\\\"\$name\\\">x</p>\""
        val trap = analyzer.analyzeKotlin(src).single { it.code == "kotlin-interpolation" }
        assertEquals(true, trap.fixes.any { it.start == src.indexOf("\"<p") })
        assertNull(offered(src, "data-text"))
        val plain = "fun f(n: Int, name: String) = \"<p data-text=\\\"\$n\\\" title=\\\"\$name\\\">x</p>\""
        assertEquals(true, analyzer.analyzeKotlin(plain).any { it.code == "kotlin-interpolation" })
        assertEquals("Build this string with interpolate(...), which escapes the values", offered(plain, "\$n\\\"")?.title)
    }

    @Test
    fun `it is offered in the string the caret stands in, the innermost`() {
        val src = "fun list(xs: List<String>, t: String) = \"<ul title=\\\"\$t\\\">\${xs.joinToString(\"\") { \"<li>\$it</li>\" }}</ul>\""
        assertEquals(
            import + "fun list(xs: List<String>, t: String) = \"<ul title=\\\"\$t\\\">" +
                "\${xs.joinToString(\"\") { interpolate(\"<li>%s</li>\", it).text }}</ul>\"",
            offered(src, "li>")?.apply(src),
        )
    }

    @Test
    fun `it is not offered where no value is escaped, where interpolate refuses one, where there is none, or outside a string`() {
        assertNull(offered("fun total(count: Int) = \"<p>total \$count</p>\"", "total \$"))
        assertNull(offered("const val GREETING = \"hi\"\nfun f() = \"<p>\$GREETING</p>\"", "<p"))
        assertNull(offered("fun f(a: String, b: String) = \"<p onclick=\\\"\$a\\\">\$b</p>\"", "<p"))
        assertNull(offered("fun f() = \"<p>x</p>\"", "<p"))
        assertNull(offered(card, "card"))
    }
}
