package io.github.markusaugust.streamlord.analysis

import io.github.markusaugust.streamlord.core.json.JsonArray
import io.github.markusaugust.streamlord.core.json.JsonNumber
import io.github.markusaugust.streamlord.core.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Completion, hover and signature help as both editors ask for them, by the shared cases. */
class EditorHelpTest {
    private val analyzer = Analyzer()

    /** The case's text without its caret, the caret, and what the editor knows of the file. */
    private fun asked(case: JsonObject): Pair<Source, Int> {
        val written = case.string("text")!!
        val caret = written.indexOf(CARET)
        val foreign = case.array("foreign")?.strings()?.toSet() ?: emptySet()
        val source =
            Source(
                text = written.removeRange(caret, caret + 1),
                language = if (case.string("language") == "kotlin") SourceLanguage.KOTLIN else SourceLanguage.HTML,
                prefix = case.string("prefix") ?: "data-",
                core = case.boolean("core"),
                htmlDsl = case.boolean("htmlDsl"),
                // As an editor that resolves calls answers: by the call whose name starts there.
                foreign = { name, at -> name in foreign && written.removeRange(caret, caret + 1).startsWith(name, at) },
            )
        return source to caret
    }

    private fun workspace(case: JsonObject): Workspace {
        val w = case.obj("workspace") ?: return Workspace(emptyList(), emptyList(), emptyList(), called = "workspace")
        return Workspace(
            signals = w.array("signals")?.strings().orEmpty(),
            ids = w.array("ids")?.strings().orEmpty(),
            classes = w.array("classes")?.strings().orEmpty(),
            called = w.string("called") ?: "workspace",
        )
    }

    @Test
    fun `what completion offers`() {
        eachCase(sharedCases("completions.json"), { it.string("name")!! }) { case ->
            val (source, caret) = asked(case)
            val found =
                analyzer.complete(
                    source,
                    caret,
                    workspace(case),
                    attributeNames = case.boolean("names") ?: true,
                    domEvents = case.boolean("domEvents") ?: true,
                )
            assertEquals(case.array("labels")!!.strings(), found.items.map { it.label }, "labels")
            for (expected in case.array("items")!!.objects()) {
                val label = expected.string("label")!!
                val item = found.items.first { it.label == label }
                val snippet =
                    item.snippet?.let { s -> s.text to s.places.map { listOf(it.start, it.end, it.choices) } }
                val wantedSnippet =
                    expected.obj("snippet")?.let { s ->
                        s.string("text") to
                            s.array("places")!!.map { p ->
                                val place = p as JsonArray
                                listOf(
                                    (place[0] as JsonNumber).toIntOrNull(),
                                    (place[1] as JsonNumber).toIntOrNull(),
                                    (place[2] as JsonArray).strings(),
                                )
                            }
                    }
                val actual =
                    listOf(
                        item.kind.name.lowercase(),
                        item.replaceFrom - caret,
                        item.insert,
                        snippet,
                        item.detail,
                        item.tail,
                        item.doc,
                        item.filter,
                    )
                val wanted =
                    listOf(
                        expected.string("kind"),
                        expected.int("from"),
                        expected.string("insert"),
                        wantedSnippet,
                        expected.string("detail"),
                        expected.string("tail"),
                        expected.string("doc"),
                        expected.string("filter"),
                    )
                assertEquals(wanted, actual, "$label: kind, from, insert, snippet, detail, tail, doc, filter")
            }
            assertEquals(case.boolean("alone") ?: false, found.alone, "alone")
        }
    }

    @Test
    fun `what hover shows`() {
        eachCase(sharedCases("hovers.json"), { it.string("name")!! }) { case ->
            val (source, caret) = asked(case)
            val found = analyzer.hover(source, caret)
            val expected = case.obj("hover")
            if (expected == null) {
                assertNull(found, "no hover")
                return@eachCase
            }
            assertNotNull(found, "a hover")
            assertTrue(found.start <= caret && caret <= found.end, "the hover holds the caret: ${found.start}..${found.end}")
            assertEquals(expected.string("word"), source.text.substring(found.start, found.end), "word")
            assertEquals(expected.string("doc"), found.markdown, "doc")
        }
    }

    @Test
    fun `what signature help shows`() {
        eachCase(sharedCases("signature-help.json"), { it.string("name")!! }) { case ->
            val (source, caret) = asked(case)
            val found = analyzer.signatureHelp(source, caret)
            val expected = case.obj("signature")
            if (expected == null) {
                assertNull(found, "no signature help")
                return@eachCase
            }
            assertNotNull(found, "signature help")
            assertEquals(expected.string("label"), found.label, "label")
            assertEquals(expected.string("doc"), found.doc, "doc")
            assertEquals(
                expected.array("parameters")!!.strings(),
                found.parameters.map { found.label.substring(it) },
                "parameters",
            )
            assertEquals(expected.int("active"), found.active, "active")
        }
    }

    @Test
    fun `asks the editor about no more calls than the caret stands in, however many the file has`() {
        val calls = (0 until 2000).joinToString("\n") { "fun f$it() = div { dataSignals(\"s$it\" to 0); dataText(\"\\\$s$it + 1\") }" }
        val text = "import io.github.markusaugust.streamlord.html.*\n$calls\nfun g() = div { dataText(\"\\\$s1999\") }"
        var asked = 0
        val source = Source(text, SourceLanguage.KOTLIN, foreign = { _, _ -> asked++ < 0 })
        val caret = text.lastIndexOf("\")")
        val workspace = Workspace(emptyList(), emptyList(), emptyList(), called = "workspace")
        assertTrue(analyzer.complete(source, caret, workspace).items.any { it.label == "\$s1999" })
        assertTrue(analyzer.offers(source, caret, names = true))
        assertTrue(asked <= 4, "asked $asked times")
    }

    @Test
    fun `every action's parameters stand in its signature, in order`() {
        for (action in analyzer.catalog.actions) {
            val text = "<div data-on:click=\"@${action.name}("
            val help = analyzer.signatureHelp(Source(text, SourceLanguage.HTML), text.length)
            assertEquals(action.parameters, help?.parameters?.map { action.signature.substring(it) }, action.name)
        }
    }

    private companion object {
        const val CARET = '¦'
    }
}
