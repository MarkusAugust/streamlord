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

    private val ProjectIssue.name get() = path.substringAfterLast(File.separatorChar)

    private val swallowed = "fun f() = dataOnClick(\"\$count++\")\n"

    @Test
    fun `reads a folder on disk, leaving out folders below the root only`() {
        val hidden = root.resolve(".worktree").apply { mkdirs() }
        val write = { path: String -> hidden.resolve(path).apply { parentFile.mkdirs() }.writeText(swallowed) }
        write("src/A.kt")
        for (folder in listOf("build", "node_modules", ".gradle", "dist", "out", "target", ".idea", "src/.cache", "src/target")) {
            write("$folder/B.kt")
        }
        val files = analyzer.analyzeProject(hidden).map { File(it.path).relativeTo(hidden).invariantSeparatorsPath }.distinct()
        assertEquals(listOf("src/A.kt"), files)
    }

    @Test
    fun `fails a build on an error and on an unsafe interpolation that is a warning, not on a hint`() {
        write("Click.kt", swallowed)
        write("Link.kt", "fun link(url: String) = \"\"\"<a href=\"\$url\">x</a>\"\"\"\n")
        write("Cell.kt", "fun cell(n: Int) = \"\"\"<td colspan=\$n>x</td>\"\"\"\n")
        val all = analyzer.analyzeProject(root)
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
        val all = analyzer.analyzeProject(root)
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
        val all = analyzer.analyzeProject(root)
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
        val all = analyzer.analyzeProject(root)
        val strict = all.failures(strict = true).map { it.issue.code to it.issue.severity }
        assertEquals(listOf(IssueCode.KOTLIN_INTERPOLATION to Severity.WARNING), strict)
        assertEquals(emptyList(), all.failures())
    }

    @Test
    fun `strict keeps what failures alone fails on`() {
        write("Click.kt", swallowed)
        write("Link.kt", "fun link(url: String) = \"\"\"<a href=\"\$url\">x</a>\"\"\"\n")
        val all = analyzer.analyzeProject(root)
        assertEquals(all.failures(), all.failures(strict = true))
        assertEquals(2, all.failures().size)
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
    fun `places an issue by line and column from 1, a CRLF line ending being one line break`() {
        write("A.kt", swallowed)
        write("B.kt", "package p\r\n\r\n$swallowed")
        val found = analyzer.analyzeProject(root).failures().map { Triple(it.name, it.line, it.column) }
        assertEquals(listOf(Triple("A.kt", 1, 24), Triple("B.kt", 3, 24)), found)
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
