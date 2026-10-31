package io.github.markusaugust.streamlord.analysis

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Which `const val` names count as the author's own across a project; the VS Code twin holds the
 * same in `interpolationProject.test.ts`.
 */
class ProjectConstantsTest {
    private val analyzer = Analyzer()

    @Test
    fun `collects a const val other files can see, internal too, and no private one`() {
        val src =
            listOf(
                """const val title = "Home"""",
                """internal const val label = "Name"""",
                """private const val secret = "s"""",
                """object Keys { const val KEY = "k"; private const val HIDDEN = "h" }""",
                """private object Quiet { const val inner = "i" }""",
                """val text = "const val fake = 1"""",
            ).joinToString("\n")
        assertEquals(setOf("KEY", "label", "title"), constantNames(src))
    }

    @Test
    fun `collects every other name another file could mean by a bare name`() {
        val src =
            listOf(
                """val title get() = request.queryParameters["title"]""",
                """class Page(val heading: String, private val hidden: String, plain: String) {""",
                """    var count = 0""",
                """    private val own = 1""",
                """    fun render(param: String) { val inside = 1 }""",
                """}""",
                """fun page(query: String) = query""",
                """private val quiet = 1""",
                """internal var shared = 2""",
            ).joinToString("\n")
        assertEquals(setOf("count", "heading", "shared", "title"), otherDeclaredNames(src))
    }

    @Test
    fun `drops a constant that another file declares as something else`() {
        val a = "const val title = \"Home\"\nconst val label = \"Name\""
        val b = "class Request { val title get() = header(\"title\") }"
        assertEquals(setOf("label"), projectConstants(listOf(a, b)))
    }

    @Test
    fun `keeps a constant that another file only takes as a parameter`() {
        val a = "const val title = \"Home\""
        val b = "fun page(title: String) = title\nfun list() { val title = 3 }"
        assertEquals(setOf("title"), projectConstants(listOf(a, b)))
    }

    @Test
    fun `warns on a hole that names a private constant in one file and a getter in another`() {
        val a = "private const val title = \"Home\""
        val b = "val title get() = call.request.queryParameters[\"title\"]"
        val c = "import org.intellij.lang.annotations.Language\n\n@Language(\"html\")\nfun page() = \"\"\"<h1>\$title</h1>\"\"\""
        val issues = analyzer.analyzeKotlin(c, AnalyzeOptions("data-", true, projectConstants(listOf(a, b, c))))
        assertTrue(issues.any { it.code == "unescaped-interpolation" }, issues.toString())
        val silent = analyzer.analyzeKotlin(c, AnalyzeOptions("data-", true, projectConstants(listOf("const val title = \"Home\"", c))))
        assertEquals(emptyList(), silent.filter { it.code == "unescaped-interpolation" })
    }
}
