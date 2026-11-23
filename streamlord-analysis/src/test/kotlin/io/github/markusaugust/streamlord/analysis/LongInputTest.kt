package io.github.markusaugust.streamlord.analysis

import io.github.markusaugust.streamlord.fixtures.assertGrowsLinearly
import kotlin.test.Test
import kotlin.test.assertEquals

/** Long and unended text a file being typed can hold: each is read without overflowing the stack, in time that grows with it. */
class LongInputTest {
    // Each input is a file of its own: the facts of the file last read are kept.
    private var made = 0

    @Test
    fun `a lambda with thousands of parameters is read, comments among them too`() {
        for (name in listOf("a", "a /* c */")) {
            val src = "fun f() {\n    foo { " + "$name, ".repeat(6_000) + "b: Int -> }\n}\n"
            assertEquals(listOf("Int"), fileFacts(src).names["b"].orEmpty().map { it.type }, name)
        }
        // Without an arrow the names are no parameters.
        assertEquals(null, fileFacts("fun f() {\n    foo { " + "a, ".repeat(6_000) + "b }\n}\n").names["b"])
        assertGrowsLinearly("a lambda's parameters", 200, { n: Int ->
            "// ${made++}\nfun f() {\n    foo { " + "a, ".repeat(n) +
                "b -> }\n}\n"
        }) {
            fileFacts(it)
        }
    }

    @Test
    fun `type arguments and a literal that never close are read`() {
        fileFacts("val title: List<" + "x".repeat(60_000) + "\n")
        fileFacts("fun f(title: List<" + "x".repeat(60_000) + "\n")
        fileFacts("val a = \"@A<" + "x".repeat(150_000) + "\n")
        Analyzer().analyzeKotlin("val a = \"@A<" + "x".repeat(150_000) + "\n")
        // What closes is read as before.
        val d = fileFacts("val title: List<Map<String, Int>> = load()\nconst val NAME = \"n\\\"\"\n")
        assertEquals(listOf("Map<String, Int>"), d.names["title"].orEmpty().map { it.typeArguments })
        assertEquals(listOf("\"n\\\"\""), d.names["NAME"].orEmpty().map { it.text })
    }

    @Test
    fun `unclosed annotations, and blank or comment lines in an unended value, are read in time that grows with them`() {
        assertGrowsLinearly("unclosed annotations", 200, { n: Int -> "// ${made++}\n" + "@A(\n".repeat(n) }) { fileFacts(it) }
        assertGrowsLinearly("comment lines after an operator", 200, { n: Int ->
            "// ${made++}\nclass P { val a = b +\n" +
                "    // c\n".repeat(n) +
                "}\n"
        }) {
            fileFacts(it)
        }
        assertGrowsLinearly(
            "blank lines after an operator",
            200,
            { n: Int -> "// ${made++}\nval a = b +" + "\n".repeat(n) + "val c = 1\n" },
        ) {
            fileFacts(it)
        }
    }
}
