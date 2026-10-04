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
}
