package io.github.markusaugust.streamlord.analysis

import io.github.markusaugust.streamlord.core.json.JsonArray
import io.github.markusaugust.streamlord.core.json.JsonParser
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** [Analyzer.analyzeProject]: the walk, the constants it gathers, and the issues that fail a build. */
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

    private val swallowed = "fun f() = dataOnClick(\"\$count++\")\n"
    private val page = "import org.intellij.lang.annotations.Language\n\n@Language(\"html\")\nfun page() = \"\"\"<h1>\$title</h1>\"\"\"\n"

    @Test
    fun `reads every Kotlin file but those under build and hidden folders`() {
        write("src/main/kotlin/A.kt", swallowed)
        write("src/main/kotlin/nested/B.kt", swallowed)
        write("build/generated/C.kt", swallowed)
        write(".gradle/D.kt", swallowed)
        write("src/main/resources/page.html", "<p>\$count</p>")
        val files = analyzer.analyzeProject(root).map { it.file.relativeTo(root).invariantSeparatorsPath }.distinct()
        assertEquals(listOf("src/main/kotlin/A.kt", "src/main/kotlin/nested/B.kt"), files)
    }

    @Test
    fun `leaves out the folders the VS Code index leaves out, below the root only`() {
        val hidden = root.resolve(".worktree").apply { mkdirs() }
        val write = { path: String -> hidden.resolve(path).apply { parentFile.mkdirs() }.writeText(swallowed) }
        write("src/A.kt")
        for (folder in listOf("build", "node_modules", ".gradle", "dist", "out", "target", ".idea", "src/.cache", "src/target")) {
            write("$folder/B.kt")
        }
        val files = analyzer.analyzeProject(hidden).map { it.file.relativeTo(hidden).invariantSeparatorsPath }.distinct()
        assertEquals(listOf("src/A.kt"), files)
    }

    @Test
    fun `gives each file the constants of every other`() {
        write("Keys.kt", "const val title = \"Home\"\n")
        write("Page.kt", page)
        assertEquals(emptyList(), analyzer.analyzeProject(root).filter { it.issue.code == "unescaped-interpolation" })
        write("Request.kt", "val title get() = call.request.queryParameters[\"title\"]\n")
        val found = analyzer.analyzeProject(root).filter { it.issue.code == "unescaped-interpolation" }
        assertEquals(listOf("Page.kt" to 4), found.map { it.file.name to it.line })
    }

    @Test
    fun `fails a build on an error and on an unsafe interpolation that is a warning, not on a hint`() {
        write("Click.kt", swallowed)
        write("Link.kt", "fun link(url: String) = \"\"\"<a href=\"\$url\">x</a>\"\"\"\n")
        write("Cell.kt", "fun cell(n: Int) = \"\"\"<td colspan=\$n>x</td>\"\"\"\n")
        val all = analyzer.analyzeProject(root)
        val failing = all.failures().map { Triple(it.file.name, it.issue.code, it.issue.severity) }
        assertEquals(
            listOf(
                Triple("Click.kt", "kotlin-interpolation", Severity.ERROR),
                Triple("Link.kt", "unsafe-interpolation", Severity.WARNING),
            ),
            failing,
        )
        assertTrue(all.any { it.file.name == "Cell.kt" && it.issue.severity == Severity.HINT }, all.toString())
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
    fun `judges the interpolation test project as the VS Code index does`() {
        val dir = File("../editors/vscode/test/fixtures/interpolationtests")
        val codes =
            setOf("kotlin-interpolation", "unescaped-interpolation", "unsafe-interpolation", "interpolate-template", "interpolate-holes")
        val files = JsonParser.parseObject(dir.resolve("expected.json").readText()).obj("files")!!
        val seen = analyzer.analyzeProject(dir).filter { it.issue.code in codes }.groupBy({ it.file.name }, { it.issue })
        assertTrue(files.keys.containsAll(seen.keys), seen.keys.toString())
        for ((file, issues) in files) {
            val wanted = (issues as JsonArray).objects().map { Triple(it.int("start")!!, it.int("end")!!, it.string("code")) }
            val got =
                seen[file]
                    .orEmpty()
                    .sortedWith(
                        compareBy({ it.start }, { it.end }, { it.code }),
                    ).map { Triple(it.start, it.end, it.code) }
            assertEquals(wanted, got, file)
        }
    }
}
