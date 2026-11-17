package io.github.markusaugust.streamlord.analysis

import kotlin.test.Test
import kotlin.test.assertEquals

/** A project held in memory: the [ProjectTree] the tests hand [Analyzer.analyzeProject], each file by its path from the root. */
class MemoryTree(
    private val files: Map<String, String>,
) : ProjectTree {
    override fun list(folder: String): List<String> {
        val under = if (folder.isEmpty()) "" else "$folder/"
        return files.keys
            .filter { it.startsWith(under) }
            .map { it.removePrefix(under) }
            .map { if ('/' in it) it.substringBefore('/') + "/" else it }
            .distinct()
    }

    override fun read(file: String): String? = files[file]
}

/** [Analyzer.analyzeProject] over a [ProjectTree]: which files it reads, how each is judged, and what the files say of each other. */
class ProjectTreeTest {
    private val analyzer = Analyzer()

    private fun found(vararg files: Pair<String, String>) =
        analyzer.analyzeProject(MemoryTree(files.toMap())).map { Triple(it.path, it.issue.code, "${it.line}:${it.column}") }

    @Test
    fun `judges a Kotlin file and an HTML file, each as its kind`() {
        assertEquals(
            listOf(
                Triple("kotlin/Page.kt", IssueCode.KOTLIN_INTERPOLATION, "1:24"),
                // A syntax error sits at the end of the expression, which is the closing quote of the attribute.
                Triple("resources/cart.html", IssueCode.EXPRESSION_SYNTAX, "2:27"),
            ),
            found(
                "kotlin/Page.kt" to "fun f() = dataOnClick(\"\$count++\")\n",
                "resources/cart.html" to "<main>\n<button data-on:click=\"1 +\">x</button>\n</main>\n",
            ),
        )
    }

    @Test
    fun `leaves out build output, dependencies and hidden folders, wherever they stand below the root`() {
        val swallowed = "fun f() = dataOnClick(\"\$count++\")\n"
        val left = listOf("build", "node_modules", ".gradle", "dist", "out", "target", ".idea", "src/.cache", "src/target")
        val files = left.map { "$it/B.kt" to swallowed } + ("src/A.kt" to swallowed) + ("src/building/C.kt" to swallowed)
        assertEquals(listOf("src/A.kt", "src/building/C.kt"), found(*files.toTypedArray()).map { it.first })
    }

    @Test
    fun `reads no file that is neither Kotlin nor markup`() {
        assertEquals(
            emptyList(),
            found("notes.md" to "<button data-on:click=\"1 +\">x</button>", "Page.java" to "class Page { String s = \"\$count++\"; }"),
        )
    }

    // FreeMarker writes ${...} before the browser reads the value, so inside a JavaScript template it is the engine's too.
    @Test
    fun `judges a template file by the rules of the engine its name gives it`() {
        val page = "<p data-text=\"`Hi \${user.name!'x'}`\"></p>\n"
        assertEquals(
            listOf("templates/hello.html" to IssueCode.EXPRESSION_SYNTAX),
            found("templates/hello.ftl" to page, "templates/hello.html" to page).map { it.first to it.second },
        )
    }

    @Test
    fun `a constant one file declares is known in another, until a third declares the name as something else`() {
        val page = "import org.intellij.lang.annotations.Language\n\n@Language(\"html\")\nfun page() = \"\"\"<h1>\$title</h1>\"\"\"\n"
        val keys = "Keys.kt" to "const val title = \"Home\"\n"
        assertEquals(emptyList(), found("Page.kt" to page, keys))
        assertEquals(
            listOf(Triple("Page.kt", IssueCode.UNESCAPED_INTERPOLATION, "4:21")),
            found("Page.kt" to page, keys, "Request.kt" to "val title get() = call.request.queryParameters[\"title\"]\n"),
        )
    }

    @Test
    fun `a signal a file reads and no file defines is unknown, and known once another file defines it`() {
        val reads = "cart.html" to "<p data-text=\"\$total\"></p>\n"
        assertEquals(listOf(Triple("cart.html", IssueCode.UNKNOWN_SIGNAL, "1:15")), found(reads))
        assertEquals(emptyList(), found(reads, "Page.kt" to "fun f() = dataSignals(\"total\", \"0\")\n"))
        assertEquals(emptyList(), found(reads, "layout/base.jte" to "<body data-signals:total=\"0\"></body>\n"))
    }

    @Test
    fun `a signal the server reads that no page declares is told where the class names it`() {
        val handler =
            """
            @Serializable
            data class SearchSignals(val query: String = "", val drafft: String = "")

            fun route() {
                val signals = call.readSignalsOr(SearchSignals())
            }
            """.trimIndent()
        val page = "search.html" to "<div data-signals=\"{query: ''}\"><input data-bind=\"draft\"></div>\n"
        assertEquals(listOf(Triple("Routes.kt", IssueCode.SIGNAL_NEVER_DECLARED, "2:54")), found("Routes.kt" to handler, page))
    }
}
