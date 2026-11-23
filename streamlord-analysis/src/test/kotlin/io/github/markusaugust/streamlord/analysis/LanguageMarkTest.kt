package io.github.markusaugust.streamlord.analysis

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
    fun `a mark written in a parameter's default marks nothing`() {
        val src = "fun f(example: String = \"\"\"@Language(\"HTML\")\"\"\", title: String) = \"<p>\$example</p>\""
        assertEquals(emptySet(), marked(src, "f"))
        val told = Analyzer().analyzeKotlin(language + src).filter { it.code == IssueCode.UNESCAPED_INTERPOLATION }
        assertEquals(listOf("\$example"), told.map { (language + src).substring(it.start, it.end) })
    }
}
