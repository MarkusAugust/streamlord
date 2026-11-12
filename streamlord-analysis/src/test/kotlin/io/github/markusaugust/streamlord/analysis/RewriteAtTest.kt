package io.github.markusaugust.streamlord.analysis

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The rewrite to `interpolate` an editor offers at a caret as a context action: where a string can take it and no issue there offers it. */
class RewriteAtTest {
    private val analyzer = Analyzer()
    private val import = "import io.github.markusaugust.streamlord.core.domain.interpolate\n\n"

    /** What the rewrite offered in [src] at the first [at] leaves of the file, or null where none is offered. */
    private fun rewritten(
        src: String,
        at: String,
    ): String? = analyzer.rewriteAt(src, src.indexOf(at))?.apply(src)

    @Test
    fun `it is offered in the markup of a string whose value has a warning`() {
        val src = "fun card(name: String) = \"<p class=\\\"x\\\">\$name</p>\""
        assertEquals(import + "fun card(name: String) = interpolate(\"<p class=\\\"x\\\">%s</p>\", name).text", rewritten(src, "class"))
    }

    @Test
    fun `it is left to the issue under the caret that offers it`() {
        assertNull(rewritten("fun card(name: String) = \"<p class=\\\"x\\\">\$name</p>\"", "name</p>"))
    }

    @Test
    fun `it is offered where no value has an issue, and is never preferred`() {
        val src = "fun total(count: Int) = \"<p>total \$count</p>\""
        val fix = analyzer.rewriteAt(src, src.indexOf("total \$"))!!
        assertEquals(import + "fun total(count: Int) = interpolate(\"<p>total %s</p>\", count).text", fix.apply(src))
        assertEquals("Build it with interpolate(...), which escapes the values" to false, fix.title to fix.preferred)
    }

    @Test
    fun `it is offered in the string the caret stands in, the innermost`() {
        val src = "fun list(xs: List<Int>, t: Int) = \"<ul title=\\\"\$t\\\">\${xs.joinToString(\"\") { \"<li>\$it</li>\" }}</ul>\""
        assertEquals(
            import +
                "fun list(xs: List<Int>, t: Int) = \"<ul title=\\\"\$t\\\">\${xs.joinToString(\"\") { interpolate(\"<li>%s</li>\", it).text }}</ul>\"",
            rewritten(src, "li>"),
        )
    }

    @Test
    fun `it is not offered where interpolate refuses a value, where there is none, or outside a string`() {
        assertNull(rewritten("fun f(a: String, b: Int) = \"<p onclick=\\\"\$a\\\">\$b</p>\"", "<p"))
        assertNull(rewritten("fun f() = \"<p>x</p>\"", "<p"))
        assertNull(rewritten("fun card(name: String) = \"<p>\$name</p>\"", "card"))
    }
}
