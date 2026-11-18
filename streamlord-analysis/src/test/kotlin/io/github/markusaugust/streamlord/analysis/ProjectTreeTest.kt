package io.github.markusaugust.streamlord.analysis

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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

    private val written = LinkedHashMap<String, String>()

    private fun write(
        path: String,
        text: String,
    ) {
        written[path] = text
    }

    /** The issues of the files [write] has put in the project. */
    private fun project() = analyzer.analyzeProject(MemoryTree(written))

    private val ProjectIssue.name get() = path.substringAfterLast('/')

    private val swallowed = "fun f() = dataOnClick(\"\$count++\")\n"

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

    // The DSL's short helpers share their names with other APIs, so one counts only where the file names the DSL.
    @Test
    fun `a call named as a short helper of the DSL defines a signal only in a file that imports the DSL`() {
        val reads = "cart.html" to "<p data-text=\"\$total\"></p>\n"
        val call = "fun f() = toggle(\"total\")\n"
        assertEquals(listOf(Triple("cart.html", IssueCode.UNKNOWN_SIGNAL, "1:15")), found(reads, "Own.kt" to call))
        assertEquals(emptyList(), found(reads, "Page.kt" to "import io.github.markusaugust.streamlord.html.*\n\n$call"))
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

    @Test
    fun `fails a build on an error and on an unsafe interpolation that is a warning, not on a hint`() {
        write("Click.kt", swallowed)
        write("Link.kt", "fun link(url: String) = \"\"\"<a href=\"\$url\">x</a>\"\"\"\n")
        write("Cell.kt", "fun cell(n: Int) = \"\"\"<td colspan=\$n>x</td>\"\"\"\n")
        val all = project()
        val failing = all.failures().map { Triple(it.name, it.issue.code, it.issue.severity) }
        assertEquals(
            listOf(
                Triple("Click.kt", IssueCode.KOTLIN_INTERPOLATION, Severity.ERROR),
                Triple("Link.kt", IssueCode.UNSAFE_INTERPOLATION, Severity.WARNING),
            ),
            failing,
        )
        assertTrue(all.any { it.name == "Cell.kt" && it.issue.severity == Severity.HINT }, all.toString())
    }

    @Test
    fun `strict also fails a build on a value written without its mark, which failures alone lets through`() {
        val head = "import io.github.markusaugust.streamlord.core.domain.interpolate\n\n"
        // A value left unmarked in a block, a template in a format, and a String in a string that is not interpolate's.
        write("Block.kt", head + "fun card(name: String) = interpolate { \"<p title=\\\"\$name\\\">x</p>\" }\n")
        write("Format.kt", head + "fun row(name: String) = interpolate(\"<li>\$name</li>\")\n")
        write("Plain.kt", "fun cell(name: String) = \"<td>\$name</td>\"\n")
        // What strict lets through too: a marked value, and a hint.
        write("Marked.kt", head + "fun card(name: String) = interpolate { \"<p title=\\\"\${+name}\\\">x</p>\" }\n")
        write("Number.kt", "fun cell(n: Int) = \"\"\"<td colspan=\$n>x</td>\"\"\"\n")
        val all = project()
        assertEquals(emptyList(), all.failures())
        assertEquals(emptyList(), all.failures(strict = false))
        assertEquals(
            listOf(
                Triple("Block.kt", IssueCode.INTERPOLATE_TEMPLATE, Severity.WARNING),
                Triple("Format.kt", IssueCode.INTERPOLATE_TEMPLATE, Severity.WARNING),
                Triple("Plain.kt", IssueCode.UNESCAPED_INTERPOLATION, Severity.WARNING),
            ),
            all.failures(strict = true).map { Triple(it.name, it.issue.code, it.issue.severity) },
        )
    }

    @Test
    fun `strict fails on markup held as a String that interpolate would print as text`() {
        val domain = "io.github.markusaugust.streamlord.core.domain"
        val head = "import $domain.Html\nimport $domain.interpolate\n\n"
        write("Held.kt", head + "fun list(row: Html) = interpolate { \"<ul>\${+row.text}</ul>\" }\n")
        val all = project()
        assertEquals(emptyList(), all.failures())
        assertEquals(
            listOf(IssueCode.INTERPOLATE_TEMPLATE to Severity.WARNING),
            all.failures(strict = true).map {
                it.issue.code to
                    it.issue.severity
            },
        )
    }

    @Test
    fun `strict fails on a value written with a bare dollar into a Datastar expression, which reads as a signal`() {
        val head = "import io.github.markusaugust.streamlord.core.domain.interpolate\n\n"
        write("Text.kt", head + "fun f(name: String) = interpolate { \"\"\"<div data-text=\"\$name\"></div>\"\"\" }\n")
        val all = project()
        val strict = all.failures(strict = true).map { it.issue.code to it.issue.severity }
        assertEquals(listOf(IssueCode.KOTLIN_INTERPOLATION to Severity.WARNING), strict)
        assertEquals(emptyList(), all.failures())
    }

    @Test
    fun `strict keeps what failures alone fails on`() {
        write("Click.kt", swallowed)
        write("Link.kt", "fun link(url: String) = \"\"\"<a href=\"\$url\">x</a>\"\"\"\n")
        val all = project()
        assertEquals(all.failures(), all.failures(strict = true))
        assertEquals(2, all.failures().size)
    }

    @Test
    fun `places an issue by line and column from 1, a CRLF line ending being one line break`() {
        write("A.kt", swallowed)
        write("B.kt", "package p\r\n\r\n$swallowed")
        val found = project().failures().map { Triple(it.name, it.line, it.column) }
        assertEquals(listOf(Triple("A.kt", 1, 24), Triple("B.kt", 3, 24)), found)
    }
}
