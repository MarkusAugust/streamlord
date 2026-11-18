package io.github.markusaugust.streamlord.analysis

import io.github.markusaugust.streamlord.core.json.JsonArray
import io.github.markusaugust.streamlord.core.json.JsonParser
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** [Analyzer.analyzeProject] and [Analyzer.checkProject] on a folder on disk: what fails a build, and how it is told. */
class AnalyzeProjectTest {
    private val analyzer = Analyzer()
    private val root = createTempDirectory("streamlord-project").toFile()

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    private fun write(
        path: String,
        text: String,
    ) = root.resolve(path).apply { parentFile.mkdirs() }.writeText(text)

    private val ProjectIssue.name get() = path.substringAfterLast(File.separatorChar)

    private val swallowed = "fun f() = dataOnClick(\"\$count++\")\n"

    @Test
    fun `reads a folder on disk, a hidden one too when it is the root, and its folders as the tree's`() {
        val hidden = root.resolve(".worktree").apply { mkdirs() }
        val write = { path: String -> hidden.resolve(path).apply { parentFile.mkdirs() }.writeText(swallowed) }
        write("src/nested/A.kt")
        write("build/B.kt")
        val files = analyzer.analyzeProject(hidden).map { File(it.path).relativeTo(hidden).invariantSeparatorsPath }.distinct()
        assertEquals(listOf("src/nested/A.kt"), files)
    }

    @Test
    fun `formats each issue as a compiler would`() {
        write("src/Click.kt", "package p\n\n$swallowed")
        val found = analyzer.analyzeProject(root).failures().single()
        val file = root.resolve("src/Click.kt")
        assertEquals(3 to 24, found.line to found.column)
        assertEquals("${file.path}:3:24: error: ${found.issue.message} [kotlin-interpolation]", found.format())
        assertEquals(found.issue.format(file.path, file.readText()), found.format())
    }

    @Test
    fun `checkProject says nothing where nothing fails, a hint and an unknown signal among it`() {
        write("kotlin/Cell.kt", "fun cell(n: Int) = \"\"\"<td colspan=\$n>x</td>\"\"\"\n")
        write("resources/cart.html", "<p data-text=\"\$total\"></p>\n")
        write("resources/search.html", "<div data-signals=\"{query: ''}\"></div>\n")
        write(
            "kotlin/Routes.kt",
            "@Serializable\ndata class SearchSignals(val query: String = \"\", val drafft: String = \"\")\n\n" +
                "fun route() {\n    val signals = call.readSignalsOr(SearchSignals())\n}\n",
        )
        val found = analyzer.analyzeProject(root).map { it.issue.code to it.issue.severity }.toSet()
        assertEquals(
            setOf(
                IssueCode.UNSAFE_INTERPOLATION to Severity.HINT,
                IssueCode.UNKNOWN_SIGNAL to Severity.WARNING,
                IssueCode.SIGNAL_NEVER_DECLARED to Severity.WARNING,
            ),
            found,
        )
        analyzer.checkProject(root)
        analyzer.checkProject(root, strict = true)
    }

    @Test
    fun `checkProject throws with each failing finding on a line of its own, as a compiler writes it`() {
        write("kotlin/Click.kt", "package p\n\n$swallowed")
        write("resources/cart.html", "<main>\n<button data-on:click=\"1 +\">x</button>\n</main>\n")
        val lines = assertFailsWith<AssertionError> { analyzer.checkProject(root) }.message!!.lines()
        assertEquals("2 findings in ${root.path}", lines[0])
        val click = root.resolve("kotlin/Click.kt").path
        assertTrue(lines[1].startsWith("$click:3:24: error: Kotlin interpolates") && lines[1].endsWith(" [kotlin-interpolation]"), lines[1])
        val cart = root.resolve("resources/cart.html").path
        assertTrue(lines[2].startsWith("$cart:2:27: error: Datastar expression: ") && lines[2].endsWith(" [expression-syntax]"), lines[2])
        assertEquals(3, lines.size)
    }

    @Test
    fun `checkProject fails with strict on a value written without its mark, and not without`() {
        write("Plain.kt", "fun cell(name: String) = \"<td>\$name</td>\"\n")
        analyzer.checkProject(root)
        val lines = assertFailsWith<AssertionError> { analyzer.checkProject(root, strict = true) }.message!!.lines()
        assertEquals("1 finding in ${root.path}", lines[0])
        assertTrue(lines[1].endsWith(" [unescaped-interpolation]"), lines[1])
    }

    @Test
    fun `checkProject fails on a folder that is not there, which would hold nothing to fail on`() {
        val missing = root.resolve("src/main")
        val error = assertFailsWith<AssertionError> { analyzer.checkProject(missing) }
        assertEquals("${missing.absolutePath} is no folder, so there is nothing to check", error.message)
    }

    @Test
    fun `judges the interpolation test project as the VS Code index does`() {
        val dir = File("../editors/vscode/test/fixtures/interpolationtests")
        val codes =
            setOf(
                IssueCode.KOTLIN_INTERPOLATION,
                IssueCode.UNESCAPED_INTERPOLATION,
                IssueCode.UNSAFE_INTERPOLATION,
                IssueCode.INTERPOLATE_TEMPLATE,
                IssueCode.INTERPOLATE_PLACEHOLDERS,
            )
        val files = JsonParser.parseObject(dir.resolve("expected.json").readText()).obj("files")!!
        val seen = analyzer.analyzeProject(dir).filter { it.issue.code in codes }.groupBy({ it.name }, { it.issue })
        assertTrue(files.keys.containsAll(seen.keys), seen.keys.toString())
        for ((file, issues) in files) {
            val wanted = (issues as JsonArray).objects().map { Triple(it.int("start")!!, it.int("end")!!, it.string("code")) }
            val got =
                seen[file]
                    .orEmpty()
                    .sortedWith(
                        compareBy({ it.start }, { it.end }, { it.code.id }),
                    ).map { Triple(it.start, it.end, it.code.id) }
            assertEquals(wanted, got, file)
        }
    }
}
