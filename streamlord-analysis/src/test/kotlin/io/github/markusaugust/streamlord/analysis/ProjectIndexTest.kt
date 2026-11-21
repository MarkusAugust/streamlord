package io.github.markusaugust.streamlord.analysis

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** [ProjectIndex] as an editor uses it: files put in and taken out, and what the project then knows. */
class ProjectIndexTest {
    private val index = ProjectIndex()

    private fun html(
        path: String,
        text: String,
    ) = index.put(path, text, SourceLanguage.HTML)

    private fun kotlin(
        path: String,
        text: String,
    ) = index.put(path, text, SourceLanguage.KOTLIN)

    @Test
    fun `knows the signals every file defines, and the reads beside them only as names`() {
        html("pages/cart.html", """<div data-signals:count="0"><p data-text="${'$'}total"></p></div>""")
        kotlin("src/Page.kt", "fun page() = \"\"\"<div data-signals:open=\"false\"></div>\"\"\"")
        assertEquals(setOf("count", "open"), index.definitions())
        assertEquals(setOf("count", "open", "total"), index.signals())
    }

    @Test
    fun `says when what a file defines has changed, and only then`() {
        assertTrue(html("a.html", """<div data-signals:count="0"></div>"""))
        assertFalse(html("a.html", """<div data-signals:count="0"></div>"""))
        assertFalse(html("a.html", """<div data-signals:count="1"></div>"""))
        assertTrue(html("a.html", """<div data-signals:total="1"></div>"""))
        assertEquals(setOf("total"), index.definitions())
    }

    @Test
    fun `forgets a file, and every file under a folder`() {
        html("templates/form.html", """<div data-signals:email="''"></div>""")
        html("templates/sub/more.html", """<div data-signals:phone="''"></div>""")
        html("templates-old.html", """<div data-signals:kept="1"></div>""")
        html("plain.html", "<p>nothing</p>")
        assertTrue(index.remove("templates"))
        assertEquals(setOf("kept"), index.definitions())
        assertFalse(index.remove("plain.html"))
        assertFalse(index.remove("never-there.html"))
        assertTrue(index.remove("templates-old.html"))
        assertEquals(emptySet(), index.definitions())
    }

    @Test
    fun `knows the constants no file declares as anything else`() {
        kotlin("Keys.kt", "const val title = \"Home\"\nconst val label = \"Name\"\nprivate const val secret = \"s\"")
        assertEquals(setOf("label", "title"), index.constants())
        assertTrue(kotlin("Request.kt", "val title get() = header(\"title\")"))
        assertEquals(setOf("label"), index.constants())
        assertTrue(kotlin("Request.kt", "val heading get() = header(\"title\")"))
        assertEquals(setOf("label", "title"), index.constants())
    }

    @Test
    fun `knows the ids and classes of every file, and of one`() {
        html("a.html", """<div id="cart" class="wide tall"></div>""")
        html("b.html", """<p id="total"></p>""")
        assertEquals(Selectors(setOf("cart", "total"), setOf("wide", "tall")), index.selectors())
        assertEquals(Selectors(setOf("total"), emptySet()), index.selectorsOf("b.html"))
        assertEquals(Selectors.EMPTY, index.selectorsOf("c.html"))
    }

    @Test
    fun `starts over when it is cleared`() {
        html("a.html", """<div data-signals:count="0"></div>""")
        index.clear()
        assertEquals(emptySet(), index.definitions())
        assertTrue(html("a.html", """<div data-signals:count="0"></div>"""))
    }

    private val handler = "fun Route.s() {\n    get(\"/s\") { call.readSignals().string(\"query\") }\n}"

    /** The codes of what the project says of the signals the file at [path] reads, as an editor asks. */
    private fun told(
        path: String,
        text: String,
        language: SourceLanguage,
    ) = projectSignalIssues(language, Analyzer().signalReferences(Source(text, language)), index.signalsFor(path)).map { it.code }

    @Test
    fun `tells a file what the others define and declare`() {
        val page = """<p data-text="${'$'}total + ${'$'}count"></p>"""
        html("page.html", page)
        html("cart.html", """<div data-signals:count="0"></div>""")
        kotlin("Search.kt", handler)
        assertEquals(listOf(IssueCode.UNKNOWN_SIGNAL), told("page.html", page, SourceLanguage.HTML))
        assertEquals(listOf(IssueCode.SIGNAL_NEVER_DECLARED), told("Search.kt", handler, SourceLanguage.KOTLIN))
        html("search.html", """<input data-bind:query>""")
        assertEquals(emptyList(), told("Search.kt", handler, SourceLanguage.KOTLIN))
    }

    @Test
    fun `a naming strategy set in a file waiting to be read tells the others when it is read`() {
        kotlin(
            "Search.kt",
            "fun Route.s() {\n    get(\"/s\") { call.readSignals<Form>() }\n}\n@Serializable\ndata class Form(val pageNo: Int = 0)",
        )
        val strategy = "val json = Json { namingStrategy = JsonNamingStrategy.SnakeCase }"
        index.put("Json.kt", strategy, SourceLanguage.KOTLIN, facts = false)
        assertTrue(index.readFacts("Json.kt", strategy))
    }

    @Test
    fun `a class turning opaque in the file that patches it tells the others when it is read`() {
        kotlin("S.kt", "@Serializable\ndata class S(val userName: String = \"\")\nfun r() { val s = call.readSignals<S>() }")
        kotlin("A.kt", "data class F(val a: String = \"\")\nfun s() { sse.patchSignals(F()) }")
        val opaque = "@JsonNaming(SnakeCaseStrategy::class)\ndata class F(val a: String = \"\")\nfun s() { sse.patchSignals(F()) }"
        index.put("A.kt", opaque, SourceLanguage.KOTLIN, facts = false)
        assertTrue(index.readFacts("A.kt", opaque))
    }

    @Test
    fun `a file not put in is told what the others define, and nothing of itself`() {
        html("cart.html", """<div data-signals:count="0"></div>""")
        val signals = index.signalsFor("elsewhere.html")
        assertEquals(setOf("count"), signals.defined())
        assertEquals(null, signals.own())
        assertEquals(1, signals.others().count())
    }

    @Test
    fun `the other files are judged again for what the server reads only when their verdict can change`() {
        kotlin("Search.kt", handler)
        html("a.html", """<p data-text="${'$'}co"></p>""")
        // A read typed in a page names nothing the server reads.
        assertFalse(html("a.html", """<p data-text="${'$'}cou"></p>"""))
        assertFalse(html("a.html", """<p data-text="${'$'}quer"></p>"""))
        // Now it names what the handler reads, whose warning goes.
        assertTrue(html("a.html", """<p data-text="${'$'}query"></p>"""))
        // The page gone, the handler's warning is back.
        assertTrue(index.remove("a.html"))
        assertTrue(html("a.html", """<p data-text="${'$'}query"></p>"""))
        // A handler that reads another name changes only its own verdict, and the page then tells it nothing.
        assertFalse(kotlin("Search.kt", handler.replace("query", "page")))
        assertFalse(index.remove("a.html"))
    }

    @Test
    fun `the other files are judged again when a class or a package changes which class a read names`() {
        kotlin("Form.kt", "package app\n@Serializable\ndata class Form(val query: String = \"\")")
        kotlin("Read.kt", "package app\nfun r() = call.readSignals<Form>()")
        kotlin("Twin.kt", "package app\nfun t() = 1")
        // A second class of the name leaves the read naming two, and the first class's warning goes.
        assertTrue(kotlin("Twin.kt", "package app\n@Serializable\ndata class Form(val mail: String = \"\")"))
        // The reader moved to another package names neither.
        assertTrue(kotlin("Read.kt", "package other\nfun r() = call.readSignals<Form>()"))
    }

    @Test
    fun `what a file says of the server's reads can wait until it is judged`() {
        kotlin("Search.kt", handler)
        val page = """<p data-text="${'$'}query"></p>"""
        // The page defines nothing, and what it says of the server's reads waits.
        assertFalse(index.put("a.html", page, SourceLanguage.HTML, facts = false))
        assertEquals(listOf(emptySet<String>()), index.signalsFor("Search.kt").others().map { it.declared })
        // A second edit keeps waiting, and what it said before stands.
        assertFalse(index.put("a.html", "$page ", SourceLanguage.HTML, facts = false))
        assertEquals(listOf(emptySet<String>()), index.signalsFor("Search.kt").others().map { it.declared })
        assertEquals(listOf("a.html"), index.waiting())
        // A text that is not the one put in is not read: the file keeps waiting for its own.
        assertFalse(index.readFacts("a.html", page))
        assertEquals(listOf("a.html"), index.waiting())
        assertFalse(index.readFacts("never-there.html", page))
        assertTrue(index.readFacts("a.html", "$page "))
        assertEquals(emptyList(), index.waiting())
        assertEquals(listOf(setOf("query")), index.signalsFor("Search.kt").others().map { it.declared })
        assertFalse(index.readFacts("a.html", "$page "))
    }

    private val helper = "fun FlowContent.x() {\n    set(\"ghost\", 1)\n    input { dataBind(\"own\") }\n}"

    private fun ProjectIndex.names() = definitions().filterNotTo(HashSet()) { ':' in it }

    @Test
    fun `where the editor answers for the DSL, a short helper defines a signal only in a file that has it`() {
        var dsl: Boolean? = false
        val index = ProjectIndex(hasDsl = { dsl.also { _ -> assertEquals("Other.kt", it) } })
        index.put("Other.kt", helper, SourceLanguage.KOTLIN)
        assertEquals(setOf("own"), index.names())
        assertEquals(setOf("own"), index.signalsOf("Other.kt"))
        assertEquals(listOf("own"), index.declarations("Other.kt") { helper }?.map { it.name })
        // The file moved to a module with the DSL: no text changed, and the editor says so.
        dsl = true
        index.modulesChanged()
        assertEquals(setOf("ghost", "own"), index.names())
        assertEquals(setOf("ghost", "own"), index.signalsOf("Other.kt"))
        assertEquals(listOf("ghost", "own"), index.declarations("Other.kt") { helper }?.map { it.name })
        // While the editor cannot tell, a name too many is better than a warning too many.
        dsl = null
        index.modulesChanged()
        assertEquals(setOf("ghost", "own"), index.names())
    }

    @Test
    fun `with no answer from an editor, the imports of a file say whether a short helper is the DSL's`() {
        kotlin("Other.kt", helper)
        assertEquals(setOf("own"), index.names())
        kotlin("Other.kt", "import io.github.markusaugust.streamlord.html.*\n$helper")
        assertEquals(setOf("ghost", "own"), index.names())
    }

    @Test
    fun `says where a file defines and reads signals, worked out once for the text it was put in with`() {
        val page = """<div data-signals:count="0"><p data-text="${'$'}count + ${'$'}total"></p></div>"""
        html("a.html", page)
        var asked = 0
        val text = {
            asked++
            page
        }
        assertEquals(listOf(Triple(18, 23, "count")), index.declarations("a.html", text)?.map { Triple(it.start, it.end, it.name) })
        index.declarations("a.html", text)
        assertEquals(1, asked)
        val source = { Source(text(), SourceLanguage.HTML) }
        assertEquals(listOf("count", "total"), index.reads("a.html", source)?.map { it.name })
        index.reads("a.html", source)
        assertEquals(2, asked)
    }

    @Test
    fun `a text that is not the one put in is answered for and not kept`() {
        html("a.html", """<div data-signals:count="0"></div>""")
        val edited = """<div data-signals:total="0"></div>"""
        assertEquals(listOf("total"), index.declarations("a.html") { edited }?.map { it.name })
        assertEquals(null, index.declarations("a.html") { null })
        assertEquals(listOf("count"), index.declarations("a.html") { """<div data-signals:count="0"></div>""" }?.map { it.name })
        assertEquals(null, index.declarations("never-there.html") { edited })
        assertEquals(null, index.reads("never-there.html") { Source(edited, SourceLanguage.HTML) })
    }

    @Test
    fun `lists the files it holds`() {
        html("a.html", "<p></p>")
        kotlin("src/B.kt", "fun b() = 1")
        assertEquals(setOf("a.html", "src/B.kt"), index.paths())
    }

    @Test
    fun `a new file that gives the others nothing leaves them as they were judged`() {
        kotlin("Search.kt", handler)
        assertFalse(html("plain.html", "<p>nothing</p>"))
        assertFalse(html("other.html", """<p data-text="${'$'}page"></p>"""))
        assertTrue(html("search.html", """<p data-text="${'$'}query"></p>"""))
    }

    @Test
    fun `hands out the same definitions and the same constants until one of them changes`() {
        html("a.html", """<div data-signals:count="0"></div>""")
        kotlin("Keys.kt", "const val title = \"Home\"")
        val definitions = index.definitions()
        val constants = index.constants()
        html("b.html", "<p>nothing</p>")
        html("a.html", """<div data-signals:count="1"></div>""")
        assertSame(definitions, index.definitions())
        kotlin("Keys.kt", "const val title = \"Home\"\nconst val label = \"Name\"")
        assertSame(definitions, index.definitions())
        assertNotSame(constants, index.constants())
        val more = index.constants()
        html("a.html", """<div data-signals:total="1"></div>""")
        assertNotSame(definitions, index.definitions())
        assertSame(more, index.constants())
    }

    @Test
    fun `a text that newly calls a short helper is read without it where its file has no DSL`() {
        val index = ProjectIndex(hasDsl = { false })
        index.put("Other.kt", "fun FlowContent.x() {\n    input { dataBind(\"own\") }\n}", SourceLanguage.KOTLIN)
        assertEquals(listOf("own"), index.declarations("Other.kt") { helper }?.map { it.name })
    }
}
