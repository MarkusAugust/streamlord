package io.github.markusaugust.streamlord.intellij

import com.intellij.codeHighlighting.HighlightDisplayLevel
import com.intellij.codeInsight.daemon.HighlightDisplayKey
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.module.Module
import com.intellij.openapi.roots.ContentEntry
import com.intellij.openapi.roots.ModifiableRootModel
import com.intellij.profile.codeInspection.InspectionProjectProfileManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.PathUtil
import io.github.markusaugust.streamlord.analysis.HoleFact
import io.github.markusaugust.streamlord.core.domain.Trusted
import io.github.markusaugust.streamlord.core.json.JsonArray
import io.github.markusaugust.streamlord.core.json.JsonParser
import io.github.markusaugust.streamlord.html.Casing
import io.github.markusaugust.streamlord.intellij.analysis.HoleFacts
import io.github.markusaugust.streamlord.intellij.analysis.StreamlordAnalysis
import io.github.markusaugust.streamlord.intellij.inspections.KotlinInterpolationInspection
import kotlinx.html.TagConsumer
import org.jetbrains.kotlin.psi.KtFile
import java.io.File

/**
 * The interpolation test project with the Kotlin plugin's types: what IntelliJ says of each hole
 * beyond the text alone, which expected.json gives for both analyses.
 */
class HoleTypesTest : BasePlatformTestCase() {
    private val dir = File(System.getProperty("streamlord.repo"), "editors/vscode/test/fixtures/interpolationtests")

    /** A module with the Kotlin standard library, Streamlord's core and DSL, and kotlinx.html, so the types resolve. */
    override fun getProjectDescriptor(): LightProjectDescriptor =
        object : LightProjectDescriptor() {
            override fun getModuleTypeId(): String = "JAVA_MODULE"

            override fun configureModule(
                module: Module,
                model: ModifiableRootModel,
                contentEntry: ContentEntry,
            ) {
                super.configureModule(module, model, contentEntry)
                for ((name, type) in LIBRARIES) {
                    val jar = File(PathUtil.getJarPathForClass(type))
                    PsiTestUtil.addLibrary(model, name, jar.parent, jar.name)
                }
            }
        }

    /**
     * Where the types change the verdict of expected.json, by hole and code: null is silent. A
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
                    val hole = it.string("hole")!!
                    val key = hole to it.string("code")!!
                    val severity = if (key in withTypes) withTypes[key] else it.string("severity")
                    severity?.let { s -> "${it.int("start")}-${it.int("end")} $hole $s" }
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

    fun `test a hole is markup only when its whole value is`() {
        val facts = facts(MARKUP_PAGE)
        val wanted =
            mapOf(
                "\${\"<b>\" + q}" to HoleFact.STRING,
                "\${q + \"<b>\"}" to HoleFact.STRING,
                "\${\"<p>\" + h + \"</p>\"}" to HoleFact.MARKUP,
                "\${bold(q)}" to HoleFact.STRING,
                "\${badge()}" to HoleFact.MARKUP,
                "\${deep2()}" to HoleFact.MARKUP,
                "\${deep1()}" to HoleFact.STRING,
                "\${either(c)}" to HoleFact.MARKUP,
                "\${mixed(c, q)}" to HoleFact.STRING,
                "\${annotated()}" to HoleFact.MARKUP,
                "\$frag" to HoleFact.MARKUP,
                "\$h" to HoleFact.MARKUP,
                "\${h.text}" to HoleFact.MARKUP,
                "\$built" to HoleFact.MARKUP,
                "\$fragment" to HoleFact.MARKUP,
                "\${Trusted(q)}" to HoleFact.MARKUP,
                "\${\"<li>\$q</li>\".trimIndent()}" to HoleFact.MARKUP,
                "\${items.joinToString(\"\") { \"<li>\$it</li>\" }}" to HoleFact.MARKUP,
                "\${items.map { \"<li>\$it</li>\" }.joinToString(\"\")}" to HoleFact.MARKUP,
                "\${items.joinToString(\"\")}" to HoleFact.STRING,
                "\${listOf(\"<b>a</b>\", q).joinToString()}" to HoleFact.STRING,
                "\${if (c) \"<b>a</b>\" else q}" to HoleFact.STRING,
                "\${when { c -> \"<b>a</b>\"; else -> \"<i>b</i>\" }}" to HoleFact.MARKUP,
                "\${createHTML().div { +q }}" to HoleFact.MARKUP,
                "\${elements { span { +q } }}" to HoleFact.MARKUP,
                "\${buildString { appendHTML().div { +q } }}" to HoleFact.MARKUP,
                "\${post(\"/save\")}" to HoleFact.CONSTANT,
                "\${signal(\"count\")}" to HoleFact.CONSTANT,
                "\${increment(\"n\")}" to HoleFact.CONSTANT,
                "\${n}" to HoleFact.NUMBER,
            )
        val seen = wanted.keys.associateWith { facts[it] }
        assertEquals(wanted.entries.joinToString("\n"), seen.entries.joinToString("\n"))
        // A string with no tag in it is not HTML, so nothing in it is typed.
        assertNull(facts["\$who"])
    }

    fun `test a helper is the author's own only with the author's own arguments`() {
        val facts = facts(HELPER_PAGE)
        val wanted =
            mapOf(
                "\${signal(\"count\")}" to HoleFact.CONSTANT,
                "\${signal(KEY)}" to HoleFact.CONSTANT,
                "\${signal(q)}" to HoleFact.STRING,
                "\${signal(\"a\" + q)}" to HoleFact.STRING,
                "\${statements(increment(\"a\"), toggle(\"b\"))}" to HoleFact.CONSTANT,
                "\${statements(increment(\"a\"), signal(q))}" to HoleFact.STRING,
                "\${post(\"/save\") { contentType = ContentType.FORM }}" to HoleFact.CONSTANT,
                "\${post(\"/save\") { selector = q }}" to HoleFact.STRING,
                "\${post(url)}" to HoleFact.STRING,
                "\${signal(Tab.HOME.name)}" to HoleFact.CONSTANT,
                "\${Tab.HOME}" to HoleFact.CONSTANT,
                "\${Tab.HOME.name}" to HoleFact.CONSTANT,
                "\${maybe}" to HoleFact.NULLABLE_STRING,
                "\$q" to HoleFact.STRING,
            )
        val seen = wanted.keys.associateWith { facts[it] }
        assertEquals(wanted.entries.joinToString("\n"), seen.entries.joinToString("\n"))
        // An enum that writes something other than its name is not the author's own.
        assertNull(facts["\${Shown.ONE}"])
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

    fun `test an edit in another file that changes a type judges the file again`() {
        myFixture.addFileToProject("Values.kt", "package values\nfun v(): Int = 1")
        val file =
            myFixture.configureByText(
                "Page.kt",
                "import values.v\nfun page() = \"<p>\${v()}</p>\"",
            ) as KtFile
        val analysis = StreamlordAnalysis.getInstance(project)
        val number = background { analysis.kotlinIssues(file) }.flatMap { it.issues }
        assertEquals(emptyList<String>(), number.map { it.code })
        val values = myFixture.findFileInTempDir("Values.kt")
        WriteCommandAction.runWriteCommandAction(project) {
            val document = FileDocumentManager.getInstance().getDocument(values)!!
            document.setText("package values\nfun v(): String = \"\"")
            PsiDocumentManager.getInstance(project).commitDocument(document)
        }
        val string = background { analysis.kotlinIssues(file) }.flatMap { it.issues }
        assertEquals(listOf("unescaped-interpolation"), string.map { it.code })
    }

    fun `test an edit in another file types the holes again but does not run the analysis again`() {
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

    /** The facts of a page, by the text of each hole. */
    private fun facts(text: String): Map<String, HoleFact> {
        val file = myFixture.configureByText("Page.kt", text) as KtFile
        return background { HoleFacts.of(file) }.mapKeys { (range, _) -> range.substring(file.text) }
    }

    private fun severity(s: HighlightSeverity): String =
        when (s) {
            HighlightSeverity.ERROR -> "error"
            HighlightSeverity.WARNING -> "warning"
            HighlightSeverity.WEAK_WARNING -> "hint"
            else -> s.name
        }

    private companion object {
        /** Every way of building markup the facts know, and a near miss of each; `¤` is a `$`. */
        val MARKUP_PAGE =
            """
            package page
            import io.github.markusaugust.streamlord.core.domain.Html
            import io.github.markusaugust.streamlord.core.domain.Trusted
            import io.github.markusaugust.streamlord.core.domain.interpolate
            import io.github.markusaugust.streamlord.html.elements
            import io.github.markusaugust.streamlord.html.increment
            import io.github.markusaugust.streamlord.html.post
            import io.github.markusaugust.streamlord.html.signal
            import kotlinx.html.div
            import kotlinx.html.span
            import kotlinx.html.stream.appendHTML
            import kotlinx.html.stream.createHTML
            import org.intellij.lang.annotations.Language

            fun bold(q: String) = "<b>" + q
            fun badge(): String = "<span>new</span>"
            fun deep1() = deep2()
            fun deep2() = deep3()
            fun deep3() = deep4()
            fun deep4() = "<i>x</i>"
            fun either(c: Boolean): String {
                if (c) return "<b>a</b>"
                return "<i>b</i>"
            }
            fun mixed(c: Boolean, q: String): String {
                if (c) return "<b>a</b>"
                return q
            }
            @Language("HTML") fun annotated(): String = load()
            fun load(): String = ""
            fun plain(who: String) = "hello ¤who"

            fun page(q: String, items: List<String>, n: Int, c: Boolean, @Language("HTML") frag: String, h: Html): String {
                val built = interpolate("<b>%s</b>", q)
                val fragment = "<b>¤q</b>"
                return ""${'"'}
                    <div>
                    ¤{"<b>" + q}
                    ¤{q + "<b>"}
                    ¤{"<p>" + h + "</p>"}
                    ¤{bold(q)}
                    ¤{badge()}
                    ¤{deep2()}
                    ¤{deep1()}
                    ¤{either(c)}
                    ¤{mixed(c, q)}
                    ¤{annotated()}
                    ¤frag
                    ¤h
                    ¤{h.text}
                    ¤built
                    ¤fragment
                    ¤{Trusted(q)}
                    ¤{"<li>¤q</li>".trimIndent()}
                    ¤{items.joinToString("") { "<li>¤it</li>" }}
                    ¤{items.map { "<li>¤it</li>" }.joinToString("")}
                    ¤{items.joinToString("")}
                    ¤{listOf("<b>a</b>", q).joinToString()}
                    ¤{if (c) "<b>a</b>" else q}
                    ¤{when { c -> "<b>a</b>"; else -> "<i>b</i>" }}
                    ¤{createHTML().div { +q }}
                    ¤{elements { span { +q } }}
                    ¤{buildString { appendHTML().div { +q } }}
                    <button data-on:click="¤{post("/save")}" data-text="¤{signal("count")}" data-on:keydown="¤{increment("n")}">x</button>
                    ¤{n}
                    </div>
                ""${'"'}
            }
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

        /** Helpers with arguments of every origin, enum entries and strings that may be null; `¤` is a `$`. */
        val HELPER_PAGE =
            """
            package page
            import io.github.markusaugust.streamlord.html.FetchOptions.ContentType
            import io.github.markusaugust.streamlord.html.increment
            import io.github.markusaugust.streamlord.html.post
            import io.github.markusaugust.streamlord.html.signal
            import io.github.markusaugust.streamlord.html.statements
            import io.github.markusaugust.streamlord.html.toggle

            const val KEY = "count"
            enum class Tab { HOME }
            enum class Shown { ONE; override fun toString() = "<b>one</b>" }

            fun page(q: String, url: String, maybe: String?): String =
                ""${'"'}
                <div>
                <button data-text="¤{signal("count")}" data-show="¤{signal(KEY)}" data-class="¤{signal(q)}">x</button>
                <button data-text="¤{signal("a" + q)}" data-on:click="¤{statements(increment("a"), toggle("b"))}">x</button>
                <button data-on:click="¤{statements(increment("a"), signal(q))}">x</button>
                <button data-on:click="¤{post("/save") { contentType = ContentType.FORM }}">x</button>
                <button data-on:click="¤{post("/save") { selector = q }}" data-on:keydown="¤{post(url)}">x</button>
                <button data-text="¤{signal(Tab.HOME.name)}" data-show="¤{Tab.HOME}" data-class="¤{Tab.HOME.name}">x</button>
                <button data-text="¤{Shown.ONE}">x</button>
                <p>¤{maybe} ¤q</p>
                </div>
                ""${'"'}
            """.trimIndent().replace('¤', '$')

        val LIBRARIES =
            listOf(
                "kotlin-stdlib" to KotlinVersion::class.java,
                "streamlord-core" to Trusted::class.java,
                "streamlord-html" to Casing::class.java,
                "kotlinx-html" to TagConsumer::class.java,
            )

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
