package io.github.markusaugust.streamlord.intellij

import com.intellij.codeHighlighting.HighlightDisplayLevel
import com.intellij.codeInsight.daemon.HighlightDisplayKey
import com.intellij.codeInsight.intention.HighPriorityAction
import com.intellij.codeInspection.ex.QuickFixWrapper
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.profile.codeInspection.InspectionProjectProfileManager
import com.intellij.psi.PsiDocumentManager
import io.github.markusaugust.streamlord.analysis.IssueCode
import io.github.markusaugust.streamlord.core.json.JsonArray
import io.github.markusaugust.streamlord.core.json.JsonParser
import io.github.markusaugust.streamlord.intellij.analysis.StreamlordAnalysis
import io.github.markusaugust.streamlord.intellij.inspections.KotlinInterpolationInspection
import io.github.markusaugust.streamlord.intellij.inspections.ReplaceTextFix
import org.jetbrains.kotlin.psi.KtFile
import java.io.File

/**
 * The interpolation test project with the Kotlin plugin's types: what IntelliJ says of each placeholder
 * beyond the text alone, which expected.json gives for both analyses.
 */
class TemplateValueTypesTest : StreamlordTestCase() {
    private val dir = File(System.getProperty("streamlord.repo"), "editors/vscode/test/fixtures/interpolationtests")

    /**
     * Where the types change the verdict of expected.json, by placeholder and code: null is silent. A
     * number in the text is safe, and a value typed String, inferred or written, is a warning
     * rather than a hint: `everything` among them, since one of the pages it joins adds a
     * value to its markup with `+`.
     */
    private val withTypes: Map<Pair<String, String>, String?> =
        mapOf(
            ("\$count" to "unescaped-interpolation") to null,
            // An inferred Int where a signal could stand: the value is safe, only the signal question is left.
            ("\$count" to "kotlin-interpolation") to "hint",
            ("\${everything(query)}" to "unescaped-interpolation") to "warning",
            ("\$name" to "unescaped-interpolation") to "warning",
            ("\$error" to "unescaped-interpolation") to "warning",
            ("\${query.trim()}" to "unescaped-interpolation") to "warning",
            ("\${if (query.isBlank()) \"nothing\" else query}" to "unescaped-interpolation") to "warning",
            ("\${applicant.name}" to "unescaped-interpolation") to "warning",
            ("\${applicant.email}" to "unescaped-interpolation") to "warning",
            ("applicant.name" to "unescaped-interpolation") to "warning",
            ("\${buttonAttributes()}" to "unsafe-interpolation") to "warning",
        )

    override fun setUp() {
        super.setUp()
        myFixture.enableInspections(KotlinInterpolationInspection())
        for ((path, text) in STUBS) myFixture.addFileToProject(path, text)
    }

    fun `test the types decide what the text cannot`() {
        val expected = JsonParser.parseObject(File(dir, "expected.json").readText())
        val files = expected.obj("files")!!
        for (name in files.keys) myFixture.addFileToProject("interpolationtests/$name", File(dir, name).readText())
        for ((name, issues) in files) {
            myFixture.configureFromTempProjectFile("interpolationtests/$name")
            val seen =
                myFixture
                    .doHighlighting()
                    .filter { it.inspectionToolId == "KotlinInterpolationInspection" }
                    .sortedWith(compareBy({ it.startOffset }, { it.endOffset }))
                    .map { "${it.startOffset}-${it.endOffset} ${it.text} ${severity(it.severity)}" }
            val wanted =
                (issues as JsonArray).objects().mapNotNull {
                    val placeholder = it.string("placeholder")!!
                    val key = placeholder to it.string("code")!!
                    val severity = if (key in withTypes) withTypes[key] else it.string("severity")
                    severity?.let { s -> "${it.int("start")}-${it.int("end")} $placeholder $s" }
                }
            assertEquals(name, wanted.joinToString("\n"), seen.joinToString("\n"))
        }
    }

    fun `test markup by type, a nullable number, and a constant from another file`() {
        myFixture.addFileToProject("Limits.kt", "package limits\nconst val MAX = \"10\"")
        myFixture.configureByText(
            "Page.kt",
            """
            import io.github.markusaugust.streamlord.core.domain.interpolate
            import limits.MAX
            fun card(n: Int?, s: String): String {
                val body = interpolate("<b>x</b>")
                val total = n ?: 0
                return "<p>${'$'}body ${'$'}n ${'$'}total ${'$'}MAX ${'$'}{s.length}</p>"
            }
            """.trimIndent(),
        )
        val seen =
            myFixture
                .doHighlighting()
                .filter { it.inspectionToolId == "KotlinInterpolationInspection" }
                .map { "${it.text} ${severity(it.severity)}" }
        // An Int? prints digits or null, both safe in element text.
        assertEquals(emptyList<String>(), seen)
    }

    fun `test no Trusted is offered for a collection, which a template writes in brackets`() {
        myFixture.configureByText(
            "Page.kt",
            "fun f(m: Map<String, Int>, a: IntArray, s: Set<String>) = \"<style>\$m \$a \$s</style>\"",
        )
        assertEquals(3, myFixture.doHighlighting().count { it.inspectionToolId == "KotlinInterpolationInspection" })
        assertEmpty(myFixture.getAllQuickFixes().mapNotNull { (QuickFixWrapper.unwrap(it) as? ReplaceTextFix)?.familyName })
    }

    fun `test interpolate refuses an unsigned number and rereads an Html in the format, and a pattern is no tag`() {
        myFixture.configureByText("Unsigned.kt", UNSIGNED_CALLS)
        val seen =
            myFixture
                .doHighlighting()
                .filter { it.inspectionToolId == "KotlinInterpolationInspection" }
                .sortedBy { it.startOffset }
                .map { "${it.text} ${severity(it.severity)}" }
        assertEquals(
            listOf(
                // `interpolate("<p data-signals=\"{n: %s}\">", u)` with `u: UInt`, and the same in style.
                "%s error",
                "%s error",
                // An unsigned number Kotlin writes into data-signals: digits, but interpolate would refuse it.
                "\${u} hint",
                // An Html a Kotlin template writes into the format.
                "\$inner warning",
                // `rows.joinToString("")` of a List<Html>, which interpolate escapes in element text.
                "%s warning",
            ),
            seen,
        )
        val fixes =
            myFixture
                .getAllQuickFixes()
                .mapNotNull { QuickFixWrapper.unwrap(it) as? ReplaceTextFix }
                .map { it.familyName }
        assertTrue(fixes.joinToString(), fixes.contains("Convert u with toLong()"))
        assertTrue(fixes.joinToString(), fixes.contains("Make \$inner a %s placeholder, where interpolate writes it as it is"))
    }

    fun `test a value marked in a block is judged by its type where it stands, and one left unmarked gets the fix that marks it`() {
        myFixture.configureByText("Block.kt", BLOCK_CALLS)
        val seen =
            myFixture
                .doHighlighting()
                .filter { it.inspectionToolId == "KotlinInterpolationInspection" }
                .sortedBy { it.startOffset }
                .map { "${it.text} ${severity(it.severity)}" }
        assertEquals(
            listOf(
                // A String marked in a Datastar expression: interpolate always throws.
                "\${+name} error",
                // A String written without the mark.
                "\$name warning",
            ),
            seen,
        )
        val fixes =
            myFixture
                .getAllQuickFixes()
                .mapNotNull { QuickFixWrapper.unwrap(it) as? ReplaceTextFix }
                .map { it.familyName }
        assertTrue(fixes.joinToString(), fixes.contains("Mark \$name with +, which interpolate escapes"))
    }

    fun `test a level set in the profile lowers the errors and leaves the hints and warnings alone`() {
        fun shown(name: String): List<String> {
            // A fresh file each time: the daemon keeps the highlights of an unchanged document.
            myFixture.configureByText(name, SEVERITY_PAGE)
            return myFixture
                .doHighlighting()
                .filter { it.inspectionToolId == "KotlinInterpolationInspection" }
                .sortedBy { it.startOffset }
                .map { "${it.text} ${severity(it.severity)}" }
        }
        val own = shown("Own.kt")
        assertEquals(listOf("interpolate error", "\$count hint", "\$name warning"), own)
        val key = HighlightDisplayKey.find("KotlinInterpolationInspection")!!
        val profile = InspectionProjectProfileManager.getInstance(project).currentProfile
        try {
            profile.setErrorLevel(key, HighlightDisplayLevel.WARNING, project)
            assertEquals(listOf("interpolate warning", "\$count hint", "\$name warning"), shown("Warning.kt"))
            profile.setErrorLevel(key, HighlightDisplayLevel.WEAK_WARNING, project)
            assertEquals(listOf("interpolate hint", "\$count hint", "\$name hint"), shown("Weak.kt"))
        } finally {
            profile.setErrorLevel(key, HighlightDisplayLevel.ERROR, project)
        }
    }

    fun `test a String in element text offers the rewrite first and Trusted after it, lifted above nothing`() {
        myFixture.configureByText("Choice.kt", "fun card(name: String): String = \"<p>\$name</p>\"")
        myFixture.doHighlighting()
        val fixes =
            myFixture
                .getAllQuickFixes()
                .mapNotNull { QuickFixWrapper.unwrap(it) as? ReplaceTextFix }
        assertEquals(2, fixes.size)
        assertTrue(fixes.joinToString { it.familyName }, fixes[0].familyName.startsWith("Mark the values with + in interpolate { }"))
        assertTrue(fixes[0] is HighPriorityAction)
        assertTrue(fixes.joinToString { it.familyName }, fixes[1].familyName.endsWith(" as markup"))
        assertFalse(fixes[1] is HighPriorityAction)
    }

    fun `test a Trusted the file does not declare gets no rewrite into an attribute, where interpolate would not escape it`() {
        myFixture.addFileToProject(
            "Users.kt",
            "package users\nimport io.github.markusaugust.streamlord.core.domain.Trusted\nclass User(val badge: Trusted, val name: String)",
        )
        myFixture.configureByText("Card.kt", "import users.User\nfun card(user: User): String = \"<p title=\\\"\${user.badge}\\\">x</p>\"")
        assertEquals(1, myFixture.doHighlighting().count { it.inspectionToolId == "KotlinInterpolationInspection" })
        assertEmpty(myFixture.getAllQuickFixes().mapNotNull { (QuickFixWrapper.unwrap(it) as? ReplaceTextFix)?.familyName })
        // A String beside it is escaped by the rewrite, which is offered there.
        myFixture.configureByText("Name.kt", "import users.User\nfun name(user: User): String = \"<p title=\\\"\${user.name}\\\">x</p>\"")
        myFixture.doHighlighting()
        assertEquals(1, myFixture.getAllQuickFixes().mapNotNull { QuickFixWrapper.unwrap(it) as? ReplaceTextFix }.size)
    }

    fun `test an edit in another file that changes a type judges the file again`() {
        myFixture.addFileToProject("Values.kt", "package values\nfun v(): Int = 1")
        val file =
            myFixture.configureByText(
                "Page.kt",
                "import values.v\nfun page() = \"<p>\${v()}</p>\"",
            ) as KtFile
        val analysis = StreamlordAnalysis.getInstance(project)
        val number = background { analysis.kotlinIssues(file) }.flatMap { it.issues }
        assertEquals(emptyList<IssueCode>(), number.map { it.code })
        val values = myFixture.findFileInTempDir("Values.kt")
        WriteCommandAction.runWriteCommandAction(project) {
            val document = FileDocumentManager.getInstance().getDocument(values)!!
            document.setText("package values\nfun v(): String = \"\"")
            PsiDocumentManager.getInstance(project).commitDocument(document)
        }
        val string = background { analysis.kotlinIssues(file) }.flatMap { it.issues }
        assertEquals(listOf(IssueCode.UNESCAPED_INTERPOLATION), string.map { it.code })
    }

    fun `test an edit in another file types the placeholders again but does not run the analysis again`() {
        val text =
            buildString {
                append("package big\n")
                for (i in 0 until 2000) append("fun card$i(name: String, n: Int) = \"<p data-n=\\\"\$n\\\">\$name</p>\"\n")
            }
        val file = myFixture.configureByText("Big.kt", text) as KtFile
        val analysis = StreamlordAnalysis.getInstance(project)
        val first = measure { analysis.kotlinIssues(file) }
        assertTrue(first.second.isNotEmpty())
        myFixture.addFileToProject("Other.kt", "package other\nval x = 1")
        val again = measure { analysis.kotlinIssues(file) }
        assertSame("the analysis and the filter are kept", first.second, again.second)
        println("2000 HTML functions: first ${first.first} ms, after an edit elsewhere ${again.first} ms")
    }

    private fun <T> measure(block: () -> T): Pair<Long, T> {
        val start = System.nanoTime()
        val value = background(block)
        return (System.nanoTime() - start) / 1_000_000 to value
    }

    /** In a read action off the EDT, where the Analysis API is allowed, as the highlighting runs it. */
    private fun <T> background(block: () -> T): T =
        ApplicationManager
            .getApplication()
            .executeOnPooledThread<T> { ReadAction.nonBlocking<T>(block).executeSynchronously() }
            .get()

    private fun severity(s: HighlightSeverity): String =
        when (s) {
            HighlightSeverity.ERROR -> "error"
            HighlightSeverity.WARNING -> "warning"
            HighlightSeverity.WEAK_WARNING -> "hint"
            else -> s.name
        }

    private companion object {
        /** The block form: a widget whose values are safe by types only the editor sees, `k.css` and `k.pageId`; `¤` is a `$`. */
        val BLOCK_CALLS =
            """
            import io.github.markusaugust.streamlord.core.domain.Html
            import io.github.markusaugust.streamlord.core.domain.Trusted
            import io.github.markusaugust.streamlord.core.domain.interpolate

            class Widget(val pageId: String, val css: Trusted)
            fun root(k: Widget, body: Html): Html = interpolate {
                "<div id=\"¤{+k.pageId}\"><style>¤{+k.css}</style>¤{+body}</div>"
            }
            fun inParentheses(k: Widget, body: Html): Html = interpolate({ "<div><style>¤{+k.css}</style>¤{+body}</div>" })
            fun sum(n: Int): Html = interpolate { "<p data-text=\"¤{+(n + 1)}\"></p>" }
            fun joined(css: Trusted): Html = interpolate { "<style>" + +css + "</style>" }
            fun refused(name: String): Html = interpolate { "<p data-text=\"¤{+name}\"></p>" }
            fun unmarked(name: String): Html = interpolate { "<p>¤name</p>" }
            """.trimIndent().replace('¤', '$')

        /** What interpolate refuses or rereads, and a search pattern; `¤` is a `$`. */
        val UNSIGNED_CALLS =
            """
            import io.github.markusaugust.streamlord.core.domain.Html
            import io.github.markusaugust.streamlord.core.domain.interpolate

            fun signals(u: UInt): Html = interpolate("<p data-signals=\"{n: %s}\"></p>", u)
            fun width(w: ULong): Html = interpolate("<p style=\"width: %spx\"></p>", w)
            fun written(u: UInt, n: Number): String = "<p data-signals=\"{u: ¤{u}, n: ¤{n}}\"></p>"
            fun reread(q: String): Html {
                val inner = interpolate("<b>%s</b>", q)
                return interpolate("<div>¤inner</div>")
            }
            fun joined(rows: List<Html>): Html = interpolate("<ul>%s</ul>", rows.joinToString(""))
            fun pattern(tagg: String, html: String) = Regex("<¤tagg\\b[^>]*").find(html)
            fun search(tagg: String, html: String) = html.indexOf("<¤tagg")
            """.trimIndent().replace('¤', '$')

        /** An error, a hint and a warning of the interpolation inspection; `¤` is a `$`. */
        val SEVERITY_PAGE =
            """
            import io.github.markusaugust.streamlord.core.domain.interpolate
            fun card(count: Int, name: String): String {
                val empty = interpolate("<p>%s</p>")
                return "<p data-text=\"¤count\">¤name</p>"
            }
            """.trimIndent().replace('¤', '$')

        /** Just enough of Ktor, Streamlord's Ktor module and the annotations for the fixture files to resolve. */
        val STUBS =
            mapOf(
                "stubs/Annotations.kt" to
                    """
                    package org.intellij.lang.annotations
                    annotation class Language(val value: String)
                    """.trimIndent(),
                "stubs/StreamlordKtor.kt" to
                    """
                    package io.github.markusaugust.streamlord.ktor
                    import io.github.markusaugust.streamlord.core.domain.ElementPatchMode
                    import io.ktor.server.application.ApplicationCall
                    class DatastarScope {
                        fun patchElements(elements: String, selector: String? = null, mode: ElementPatchMode = ElementPatchMode.OUTER) {}
                    }
                    suspend fun ApplicationCall.respondDatastar(block: suspend DatastarScope.() -> Unit) {}
                    """.trimIndent(),
                "stubs/KtorHttp.kt" to
                    """
                    package io.ktor.http
                    class ContentType { object Text { val Html = ContentType() } }
                    """.trimIndent(),
                "stubs/KtorApplication.kt" to
                    """
                    package io.ktor.server.application
                    class Parameters { operator fun get(name: String): String? = null }
                    class ApplicationRequest { val queryParameters = Parameters() }
                    class ApplicationCall { val request = ApplicationRequest() }
                    class Application
                    """.trimIndent(),
                "stubs/KtorServer.kt" to
                    """
                    package io.ktor.server.routing
                    import io.ktor.server.application.Application
                    import io.ktor.server.application.ApplicationCall
                    class Routing
                    class RoutingContext(val call: ApplicationCall)
                    fun Application.routing(block: Routing.() -> Unit) {}
                    fun Routing.get(path: String, body: suspend RoutingContext.() -> Unit) {}
                    """.trimIndent(),
                "stubs/KtorResponse.kt" to
                    """
                    package io.ktor.server.response
                    import io.ktor.http.ContentType
                    import io.ktor.server.application.ApplicationCall
                    suspend fun ApplicationCall.respondText(text: String, contentType: ContentType) {}
                    """.trimIndent(),
                "stubs/KtorEngine.kt" to
                    """
                    package io.ktor.server.engine
                    import io.ktor.server.application.Application
                    class Server { fun start(wait: Boolean): Server = this }
                    fun embeddedServer(factory: Any, port: Int, module: Application.() -> Unit): Server = Server()
                    """.trimIndent(),
                "stubs/KtorCio.kt" to
                    """
                    package io.ktor.server.cio
                    object CIO
                    """.trimIndent(),
            )
    }
}
