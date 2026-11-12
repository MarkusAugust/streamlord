package io.github.markusaugust.streamlord.analysis

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SignalDriftTest {
    private val page =
        """
        <div data-signals="{query: '', total: 0}">
            <input data-bind="draft">
            <span data-text="${'$'}total"></span>
        </div>
        """.trimIndent()

    private val handler =
        """
        @Serializable
        data class SearchSignals(val query: String = "", val draft: String = "")

        fun route() {
            val signals = call.readSignalsOr(SearchSignals())
        }
        """.trimIndent()

    private fun drift(vararg files: Pair<String, SignalFacts>) = signalDrift(files.toMap()).issues

    private fun kotlin(src: String) = collectSignalFacts(src, SourceLanguage.KOTLIN)

    private fun html(src: String) = collectSignalFacts(src, SourceLanguage.HTML)

    @Test
    fun `the report says what it had to judge, so an empty result can be believed`() {
        val report = signalDrift(mapOf("page.html" to html(page), "Routes.kt" to kotlin(handler)))

        assertEquals(emptyMap(), report.issues)
        assertEquals(setOf("query", "draft"), report.read, "nothing was recognised as a read")
        assertTrue("total" in report.declared)
    }

    /** A corpus the patterns do not match reports clean, and the report is how you tell. */
    @Test
    fun `a project with no recognisable reads is clean and says so`() {
        val report = signalDrift(mapOf("page.html" to html(page)))

        assertEquals(emptyMap(), report.issues)
        assertTrue(report.read.isEmpty(), "there was nothing to judge")
    }

    @Test
    fun `a signals class whose names the page declares is quiet`() {
        assertEquals(emptyMap(), drift("page.html" to html(page), "Routes.kt" to kotlin(handler)))
    }

    @Test
    fun `a property no page declares is reported where it is written`() {
        val renamed = handler.replace("val draft: String", "val drafft: String")

        val issues = drift("page.html" to html(page), "Routes.kt" to kotlin(renamed))["Routes.kt"]!!

        assertEquals(1, issues.size)
        assertEquals("signal-never-declared", issues[0].code)
        assertEquals("drafft", renamed.substring(issues[0].start, issues[0].end))
        assertTrue(issues[0].message.contains("SearchSignals.drafft"), issues[0].message)
    }

    // The point of the check: neither file is wrong on its own.
    @Test
    fun `the declaration may be in any file`() {
        assertTrue(drift("Routes.kt" to kotlin(handler)).isNotEmpty(), "nothing declares these yet")
        assertTrue(drift("page.html" to html(page), "Routes.kt" to kotlin(handler)).isEmpty())
    }

    // dataSignals(codec.toJsonValue(SearchSignals())) declares what the codec writes, which is what readSignals reads.
    @Test
    fun `a page that declares its signals from the class declares the class's properties`() {
        val fromClass = "fun page() = div { dataSignals(call.streamlord.codec.toJsonValue(SearchSignals())) }"
        assertEquals(emptyMap(), drift("Page.kt" to kotlin(fromClass), "Routes.kt" to kotlin(handler)))
        val typed = "fun page() = div { dataSignals(codec.toJsonValue<SearchSignals>(signals)) }"
        assertEquals(emptyMap(), drift("Page.kt" to kotlin(typed), "Routes.kt" to kotlin(handler)))
        // Another class declares other names, and nested under a pair the fields are no top-level signals.
        val other = "fun page() = div { dataSignals(codec.toJsonValue(Other())) }\n@Serializable data class Other(val x: Int = 0)"
        assertTrue(drift("Page.kt" to kotlin(other), "Routes.kt" to kotlin(handler)).isNotEmpty())
        val nested = "fun page() = div { dataSignals(\"form\" to codec.toJsonValue(SearchSignals())) }"
        assertTrue(drift("Page.kt" to kotlin(nested), "Routes.kt" to kotlin(handler)).isNotEmpty())
    }

    // A variable whose class the file names, or one bound to toJsonValue, is that class.
    @Test
    fun `a variable the file types declares its class's properties`() {
        for (page in listOf(
            "fun page(form: SearchSignals) = div { dataSignals(codec.toJsonValue(form)) }",
            "val s = codec.toJsonValue(SearchSignals())\nfun page() = div { dataSignals(s) }",
        )) {
            val report = signalDrift(mapOf("Page.kt" to kotlin(page), "Routes.kt" to kotlin(handler)))
            assertEquals(emptyMap(), report.issues, page)
            assertEquals(emptySet(), report.declaredByCodec, page)
        }
    }

    // A Jackson class or a value of no named class: only the codec knows the names, so the page may
    // declare any, and the report says so.
    @Test
    fun `a value whose class the check cannot read may declare any signal, and the report says so`() {
        val cases = mapOf("dataSignals(codec.toJsonValue(load()))" to "*", "dataSignals(codec.toJsonValue(JacksonForm()))" to "JacksonForm")
        for ((page, byCodec) in cases) {
            val report = signalDrift(mapOf("Page.kt" to kotlin("fun page() = div { $page }"), "Routes.kt" to kotlin(handler)))
            assertEquals(emptyMap(), report.issues, page)
            assertEquals(setOf(byCodec), report.declaredByCodec, page)
        }
    }

    @Test
    fun `a serializable class nobody reads signals into is left alone`() {
        val dto =
            """
            @Serializable
            data class Order(val sku: String, val quantity: Int)
            """.trimIndent()

        assertEquals(emptyMap(), drift("page.html" to html(page), "Order.kt" to kotlin(dto)))
    }

    @Test
    fun `a type read through the type parameter counts too`() {
        val typed = handler.replace("call.readSignalsOr(SearchSignals())", "call.readSignals<SearchSignals>()")
        val renamed = typed.replace("val query: String", "val querry: String")

        val issues = drift("page.html" to html(page), "Routes.kt" to kotlin(renamed))["Routes.kt"]!!

        assertEquals(1, issues.size)
        assertEquals("querry", renamed.substring(issues[0].start, issues[0].end))
    }

    @Test
    fun `a lookup by name is checked the same way`() {
        val lookups =
            """
            fun route() {
                val signals = call.readSignals()
                val a = signals.string("query")
                val b = signals.int("totl")
                val c = signals.path("total", "inner")
            }
            """.trimIndent()

        val issues = drift("page.html" to html(page), "Lookups.kt" to kotlin(lookups))["Lookups.kt"]!!

        assertEquals(1, issues.size)
        assertEquals("totl", lookups.substring(issues[0].start, issues[0].end))
    }

    @Test
    fun `markup inside Kotlin declares signals like any other markup`() {
        val both =
            """
            @Serializable
            data class ModeSignals(val mode: String = "inner")

            fun page(): String = ""${'"'}<div data-signals="{mode: 'inner'}"></div>""${'"'}

            fun route() {
                val signals = call.readSignalsOr(ModeSignals())
            }
            """.trimIndent()

        assertEquals(emptyMap(), drift("Modes.kt" to kotlin(both)))
    }

    @Test
    fun `a signal declared only by the DSL counts`() {
        val dsl =
            """
            @Serializable
            data class CounterSignals(val running: Boolean = false)

            fun markup() {
                div { dataSignals("running" to false) }
            }

            fun route() {
                val signals = call.readSignalsOr(CounterSignals())
            }
            """.trimIndent()

        assertEquals(emptyMap(), drift("Counter.kt" to kotlin(dsl)))
    }

    @Test
    fun `an unread signal is not a finding, because it is harmless`() {
        val quiet =
            """
            @Serializable
            data class SearchSignals(val query: String = "")

            fun route() {
                val signals = call.readSignalsOr(SearchSignals())
            }
            """.trimIndent()

        // The page declares total and draft; nothing reads them, and nothing is said.
        assertEquals(emptyMap(), drift("page.html" to html(page), "Routes.kt" to kotlin(quiet)))
    }

    @Test
    fun `signals declared as JSON, with quoted keys, count`() {
        val json = """<div data-signals='{"query": "", "draft": ""}'></div>"""
        val single = """<div data-signals="{'query': '', 'draft': ''}"></div>"""

        assertEquals(setOf("query", "draft"), html(json).declared)
        assertEquals(emptyMap(), drift("page.html" to html(json), "Search.kt" to kotlin(handler)))
        assertEquals(emptyMap(), drift("page.html" to html(single), "Search.kt" to kotlin(handler)))
    }

    @Test
    fun `a property is judged by its serial name`() {
        val renamed =
            """
            @Serializable
            data class PageSignals(
                val query: String = "",
                @SerialName("page_no") val page: Int = 0,
                val tags: List<String> = listOf(),
            )

            fun route() {
                val signals = call.readSignalsOr(PageSignals())
            }
            """.trimIndent()

        val right = """<div data-signals="{query: '', page_no: 1, tags: []}"></div>"""
        assertEquals(emptyMap(), drift("page.html" to html(right), "Page.kt" to kotlin(renamed)))

        val wrong = """<div data-signals="{query: '', page: 1, tags: []}"></div>"""
        val issues = drift("page.html" to html(wrong), "Page.kt" to kotlin(renamed))["Page.kt"]!!
        assertEquals(listOf("page_no"), issues.map { renamed.substring(it.start, it.end) })
    }

    @Test
    fun `an accessor of the same name on something else is not a signal read`() {
        val mixed =
            """
            fun route(row: ResultSet, typed: Signals) {
                val x = map.string("x")
                val y = json.int("y")
                val z = row.has("z")
                val w = call.readSignals<PageSignals>()?.string("w")
                val a = call.readSignals().string("a")
                val b = typed.int("b")
                val mine = call.readSignals()
                val c = mine.has("c")
                val d = signals?.boolean("d")
                val e = mine.obj("e")?.string("inner")
            }
            """.trimIndent()

        assertEquals(listOf("a", "b", "c", "d", "e"), kotlin(mixed).lookups.map { it.name })
    }

    // A handler that patches a signals class sets every property of it, so the page need not declare them.
    @Test
    fun `a class instance handed to a signals patch declares its properties`() {
        val form = "@Serializable\ndata class Form(val query: String = \"\", val page: Int = 0)\nfun read() = call.readSignals<Form>()\n"
        assertEquals(listOf("query", "page"), drift("Form.kt" to kotlin(form))["Form.kt"]!!.map { form.substring(it.start, it.end) })
        for (patch in listOf(
            "suspend fun f() = call.respondSignals(Form(\"\", 1))",
            "suspend fun f() = sse.patchSignals(Form(query = \"\"), onlyIfMissing = true)",
            "fun f() = datastarSignals(Form())",
            "fun f(form: Form) = datastarSignals(form, streamlord)",
            "suspend fun f() { val form = Form(); stream.patchSignals(form) }",
            "suspend fun f() = sse.patchSignals(codec.toJsonValue(Form()))",
            "suspend fun f() = sse.patchSignals(mapOf(\"query\" to \"\", \"page\" to 1))",
        )) {
            assertEquals(emptyMap(), drift("Form.kt" to kotlin(form), "Patch.kt" to kotlin(patch)), patch)
        }
        // A string, a value of no class the file names, or a patch of other names declares nothing of Form.
        for (patch in listOf(
            "suspend fun f(json: String) = sse.patchSignals(json)",
            "suspend fun f() = sse.patchSignals(\"count\" to 1)",
            "suspend fun f() = call.respondSignals(Other(1))\n@Serializable data class Other(val count: Int)",
            // A Jackson class: its names are the codec's, and a patch of it says nothing for the rest of the project.
            "fun f() = datastarSignals(JacksonForm())",
        )) {
            assertEquals(2, drift("Form.kt" to kotlin(form), "Patch.kt" to kotlin(patch))["Form.kt"]?.size, patch)
        }
    }

    @Test
    fun `a read written in a comment or a string is no read`() {
        val quiet =
            """
            @Serializable
            data class Form(val email: String = "")

            // A page declares the names the handler's readSignals<Form>() reads.
            /* readSignalsOr(Form()) */
            val text = "call.readSignals<Form>()"
            """.trimIndent()

        assertEquals(emptySet(), kotlin(quiet).signalTypes)
        assertEquals(emptyMap(), drift("Form.kt" to kotlin(quiet)))
    }

    // Kotlin finds a simple name by an explicit import, then the file's own package, then a star import.
    @Test
    fun `a class read is the one the reading file's package and imports name`() {
        val form = "package app.forms\n@Serializable\ndata class Form(val email: String = \"\")\n"
        val reader = { header: String -> "${header}fun read() = call.readSignals<Form>()\n" }
        val judged = { header: String -> drift("Form.kt" to kotlin(form), "Read.kt" to kotlin(reader(header)))["Form.kt"].orEmpty().size }

        assertEquals(1, judged("package app.forms\n"), "the same package")
        assertEquals(1, judged("package app.routes\nimport app.forms.Form\n"), "an explicit import")
        assertEquals(1, judged("package app.routes\nimport app.forms.*\n"), "a star import")
        assertEquals(0, judged("package app.routes\n"), "another package, not imported")
        assertEquals(0, judged("package app.routes\nimport other.Form\n"), "another class of the name, imported")

        // Two classes of the name the read may mean: the check cannot tell which, and says nothing.
        val twin = "package app.forms\n@Serializable\ndata class Form(val mail: String = \"\")\n"
        val both = drift("Form.kt" to kotlin(form), "Twin.kt" to kotlin(twin), "Read.kt" to kotlin(reader("package app.forms\n")))
        assertEquals(emptyMap(), both)
    }
}
