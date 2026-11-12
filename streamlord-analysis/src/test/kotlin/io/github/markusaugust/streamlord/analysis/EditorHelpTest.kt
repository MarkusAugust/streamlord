package io.github.markusaugust.streamlord.analysis

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
                foreign = { name, _ -> name in foreign },
            )
        return source to caret
    }

    private fun workspace(case: JsonObject): Workspace {
        val w = case.obj("workspace") ?: return Workspace()
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
                val doc = expected.string("doc")
                val actual =
                    listOf(
                        item.kind.name.lowercase(),
                        item.replaceFrom - caret,
                        item.insert,
                        item.snippet,
                        item.detail,
                        item.tail,
                        if (doc != null && item.doc?.startsWith(doc) == true) doc else item.doc,
                        item.filter,
                        item.sort,
                    )
                val wanted =
                    listOf(
                        expected.string("kind"),
                        expected.int("from"),
                        expected.string("insert"),
                        expected.string("snippet"),
                        expected.string("detail"),
                        expected.string("tail"),
                        doc,
                        expected.string("filter"),
                        expected.string("sort"),
                    )
                assertEquals(wanted, actual, "$label: kind, from, insert, snippet, detail, tail, doc, filter, sort")
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
            assertTrue(found.markdown.startsWith(expected.string("doc")!!), found.markdown)
            expected.string("without")?.let { assertTrue(it !in found.markdown, found.markdown) }
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
            assertTrue(found.doc.startsWith(expected.string("doc")!!), found.doc)
            assertEquals(
                expected.array("parameters")!!.strings(),
                found.parameters.map { found.label.substring(it) },
                "parameters",
            )
            assertEquals(expected.int("active"), found.active, "active")
        }
    }

    private companion object {
        const val CARET = '¦'
    }
}
