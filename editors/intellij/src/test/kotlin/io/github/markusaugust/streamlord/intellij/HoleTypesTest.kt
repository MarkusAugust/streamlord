package io.github.markusaugust.streamlord.intellij

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.module.Module
import com.intellij.openapi.roots.ContentEntry
import com.intellij.openapi.roots.ModifiableRootModel
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.PathUtil
import io.github.markusaugust.streamlord.core.json.JsonArray
import io.github.markusaugust.streamlord.core.json.JsonParser
import io.github.markusaugust.streamlord.intellij.inspections.KotlinInterpolationInspection
import java.io.File

/**
 * The interpolation test project with the Kotlin plugin's types: what IntelliJ says of each hole
 * beyond the text alone, which expected.json gives for both analyses.
 */
class HoleTypesTest : BasePlatformTestCase() {
    private val dir = File(System.getProperty("streamlord.repo"), "editors/vscode/test/fixtures/interpolationtests")

    /** A module with the Kotlin standard library, so the types resolve. */
    override fun getProjectDescriptor(): LightProjectDescriptor =
        object : LightProjectDescriptor() {
            override fun getModuleTypeId(): String = "JAVA_MODULE"

            override fun configureModule(
                module: Module,
                model: ModifiableRootModel,
                contentEntry: ContentEntry,
            ) {
                super.configureModule(module, model, contentEntry)
                val stdlib = File(PathUtil.getJarPathForClass(KotlinVersion::class.java))
                PsiTestUtil.addLibrary(model, "kotlin-stdlib", stdlib.parent, stdlib.name)
            }
        }

    /**
     * Where the types change the verdict of expected.json, by hole and code: null is silent. A
     * number in the text is safe, markup a function returns is the author's own, and a value
     * typed String, inferred or written, is a warning rather than a hint.
     */
    private val withTypes: Map<Pair<String, String>, String?> =
        mapOf(
            ("\$count" to "unescaped-interpolation") to null,
            ("\${everything(query)}" to "unescaped-interpolation") to null,
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
            import io.github.markusaugust.streamlord.core.domain.Html
            import limits.MAX
            fun card(n: Int?, s: String): String {
                val body = Html("<b>x</b>")
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
        assertEquals(listOf("\$n hint"), seen)
    }

    private fun severity(s: HighlightSeverity): String =
        when (s) {
            HighlightSeverity.WARNING -> "warning"
            HighlightSeverity.WEAK_WARNING -> "hint"
            else -> s.name
        }

    private companion object {
        /** Just enough of Ktor, Streamlord and the annotations for the five files to resolve. */
        val STUBS =
            mapOf(
                "stubs/Annotations.kt" to
                    """
                    package org.intellij.lang.annotations
                    annotation class Language(val value: String)
                    """.trimIndent(),
                "stubs/Streamlord.kt" to
                    """
                    package io.github.markusaugust.streamlord.core.domain
                    @JvmInline value class Html(val text: String)
                    enum class ElementPatchMode { OUTER, APPEND }
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
