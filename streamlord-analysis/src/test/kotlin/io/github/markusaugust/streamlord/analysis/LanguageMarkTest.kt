package io.github.markusaugust.streamlord.analysis

import io.github.markusaugust.streamlord.fixtures.assertGrowsLinearly
import kotlin.test.Test
import kotlin.test.assertEquals

/** Which literals and parameters IntelliJ's `@Language("HTML")` and `// language=HTML` mark, read as the Kotlin plugin reads them. */
class LanguageMarkTest {
    private val language = "import org.intellij.lang.annotations.Language\n"

    private fun marked(
        src: String,
        name: String,
    ) = fileFacts(language + src)
        .functions
        .getValue(name)
        .single()
        .html

    @Test
    fun `a comparison among the parameters moves no mark`() {
        assertEquals(setOf(1, "body"), marked("fun f(keep: (Int) -> Boolean = { it > 0 }, @Language(\"HTML\") body: String) = body", "f"))
        assertEquals(setOf(1, "body"), marked("fun g(big: Boolean = 1 >= 2, @Language(\"HTML\") body: String) = body", "g"))
        assertEquals(setOf(1, "body"), marked("fun h(less: Boolean = 1 < 2, @Language(\"HTML\") body: String) = body", "h"))
    }

    @Test
    fun `a comparison without spaces among the parameters hides none after it`() {
        for (default in listOf("x<y", "1<2", "x<=y", "if (i<0) 0 else i", "{ it<0 }", "\"\${x<y}\"")) {
            val src = "fun f(a: Any = $default, @Language(\"HTML\") b: String, count: Int) = \"<p>\$count</p>\""
            assertEquals(setOf(1, "b"), marked(src, "f"), src)
            assertEquals(emptyList(), Analyzer().analyzeKotlin(language + src).filter { it.code == IssueCode.UNESCAPED_INTERPOLATION }, src)
        }
    }

    @Test
    fun `a type's brackets after a space or a backticked name cut nothing`() {
        assertEquals(setOf(1, "b"), marked("fun f(a: Map <String, Int>, @Language(\"HTML\") b: String) = b", "f"))
        assertEquals(setOf(1, "b"), marked("fun f(a: Map\n<String, Int>, @Language(\"HTML\") b: String) = b", "f"))
        assertEquals(setOf(1, "b"), marked("fun f(a: `My Type`<Int, Int>, @Language(\"HTML\") b: String) = b", "f"))
        assertEquals(setOf(1, "b"), marked("fun f(a: Map<String, (Int) -> Unit>?, @Language(\"HTML\") b: String) = b", "f"))
        assertEquals(setOf(1, "b"), marked("fun f(a: Map<String, Int> = emptyMap<String, Int>(), @Language(\"HTML\") b: String) = b", "f"))
        assertEquals(setOf(1, "b"), marked("fun f(a: Map<String, (x: Int) -> Unit>, @Language(\"HTML\") b: String) = b", "f"))
        assertEquals(setOf(1, "b"), marked("fun <T> f(a: Map<T, T & Any>, @Language(\"HTML\") b: String) = b", "f"))
        assertEquals(setOf(1, "b"), marked("fun f(a: Boolean = n < 0 || v is Pair<*, *>, @Language(\"HTML\") b: String) = b", "f"))
        assertEquals(setOf(1, "b"), marked("fun f(a: Boolean = x < g<A, B>(), @Language(\"HTML\") b: String) = b", "f"))
        assertEquals(setOf(1, "b"), marked("fun f(a: Map<String, @Ann(x = 1) Int>, @Language(\"HTML\") b: String) = b", "f"))
    }

    @Test
    fun `a parameter with a backticked name is marked by its place`() {
        assertEquals(setOf(1), marked("fun f(x: Int, @Language(\"HTML\") `the body`: String) = 1", "f"))
        assertEquals(setOf(1), marked("fun f(x: Int, @param:Language(\"HTML\") `the body`: String) = 1", "f"))
        val src = "fun f(x: Int, @Language(\"HTML\") `body`: String) = 1\nval p = f(1, \"<p>x</p>\")"
        assertEquals(setOf(1), marked(src, "f").filterIsInstance<Int>().toSet())
        assertEquals(true, (language + src).indexOf("\"<p>") in fileFacts(language + src).htmlArguments)
    }

    @Test
    fun `brackets that never close as a type's are read in time that grows with them`() {
        var made = 0
        assertGrowsLinearly("comparisons in a default", 2_000, { n: Int ->
            "// ${made++}\nfun f(a: Boolean = " + "a<".repeat(n) + "a, @Language(\"HTML\") b: String) = b\n"
        }) { fileFacts(it) }
    }

    @Test
    fun `the returns on one line are read in time that grows with them`() {
        var made = 0
        val returns = { n: Int -> (0 until n).joinToString(" else ") { "if (x == $it) return $it" } }
        assertGrowsLinearly("returns on one line", 2_000, { n: Int ->
            "// ${made++}\nfun f(x: Int): Int { ${returns(n)} else return -1 }\n"
        }) { fileFacts(it) }
        val texts = { n: Int -> (0 until n).joinToString(" else ") { "if (x == $it) return \"$it\"" } }
        assertGrowsLinearly("marked returns on one line", 2_000, { n: Int ->
            "// ${made++}\n@Language(\"HTML\") fun f(x: Int): String { ${texts(n)} else return \"\" }\n"
        }) { fileFacts(language + it) }
    }

    @Test
    fun `blank lines after a mark's annotated line, one long sum and nested blocks are read in time that grows with them`() {
        var made = 0
        assertGrowsLinearly("blank lines after an annotation", 2_000, { n: Int ->
            "// ${made++}\n// language=HTML\n@Foo(1)\n" + "\n".repeat(n) + "val x = \"a\"\n"
        }) { fileFacts(it) }
        assertGrowsLinearly("a sum on one line", 2_000, { n: Int ->
            "// ${made++}\nval x = " + "\"a\" + ".repeat(n) + "\"a\"\n"
        }) { fileFacts(it) }
        assertGrowsLinearly("nested blocks", 500, { n: Int ->
            "// ${made++}\nfun f(c: Boolean) {\n" + "if (c) {\n\"a\"\n".repeat(n) + "}\n".repeat(n) + "}\n"
        }) { fileFacts(it) }
    }

    @Test
    fun `a mark written in a parameter's default marks nothing`() {
        val src = "fun f(example: String = \"\"\"@Language(\"HTML\")\"\"\", title: String) = \"<p>\$example</p>\""
        assertEquals(emptySet(), marked(src, "f"))
        val told = Analyzer().analyzeKotlin(language + src).filter { it.code == IssueCode.UNESCAPED_INTERPOLATION }
        assertEquals(listOf("\$example"), told.map { (language + src).substring(it.start, it.end) })
    }

    @Test
    fun `a literal in a marked literal's template is marked, as the Kotlin plugin injects it`() {
        val src = "@Language(\"HTML\")\nval t = \"Outer <i>\${\"Inner <b>x</b>\"}</i>\"\n"
        assertEquals(true, src.indexOf("\"Inner") in fileFacts(src).languageMarks)
    }
}
