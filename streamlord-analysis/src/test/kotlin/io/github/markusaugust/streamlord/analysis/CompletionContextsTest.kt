package io.github.markusaugust.streamlord.analysis

import io.github.markusaugust.streamlord.core.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CompletionContextsTest {
    private val analyzer = Analyzer()
    private val workspace = Workspace(listOf("count"), emptyList(), emptyList(), called = "workspace")

    /** The source a case describes and its caret: an HTML string in Kotlin where it reads markup without templates. */
    private fun asked(
        case: JsonObject,
        before: String = case.string("before")!!,
        after: String = case.string("after") ?: "",
    ): Pair<Source, Int> {
        val prefix = case.string("prefix") ?: "data-"
        if (case.boolean("templates") == false) {
            val head = "val p = \"\"\""
            return Source(head + before + after + "\"\"\"", SourceLanguage.KOTLIN, prefix) to head.length + before.length
        }
        val language = if (case.boolean("kotlin") == true) SourceLanguage.KOTLIN else SourceLanguage.HTML
        return Source(before + after, language, prefix) to before.length
    }

    /** What completion offers at the caret of [case], and the signature help there where the case says. */
    private fun offered(
        file: String,
        ask: (JsonObject) -> Pair<Source, Int> = { asked(it) },
    ) {
        for (case in sharedCases(file)) {
            val name = case.string("name")
            val (source, at) = ask(case)
            val found = analyzer.complete(source, at, workspace)
            assertEquals(case.array("labels")!!.strings(), found.items.map { it.label }, name)
            assertEquals(case.boolean("alone"), found.alone, name)
            if (!case.containsKey("signature")) continue
            val help = analyzer.signatureHelp(source, at)
            val expected = case.obj("signature")
            assertEquals(expected?.string("label") to expected?.int("active"), help?.label to help?.active, name)
        }
    }

    @Test
    fun `what a caret in markup stands in, a value, a name or neither`() = offered("completion-contexts.json")

    @Test
    fun `which Datastar expression a caret stands in`() = offered("expressions-at-caret.json")

    @Test
    fun `which action call a caret stands in`() =
        offered("action-calls.json") { asked(it, "<div data-on:click=\"" + it.string("before")!!, "\"></div>") }

    // An attribute only the html element takes is offered in its tag, which escaped quotes and a > in a value do not hide.
    @Test
    fun `the tag of a name in a Kotlin string is read from its opening bracket`() {
        fun names(tag: String): List<String> {
            val src = "val x = \"<$tag data-show=\\\"\\\$a > 1\\\" data-\""
            val offset = src.lastIndexOf("data-") + "data-".length
            return analyzer.complete(Source(src, SourceLanguage.KOTLIN), offset, workspace).items.map { it.label }
        }
        assertTrue("data-nonce" in names("html"), names("html").toString())
        assertTrue("data-nonce" !in names("div"), names("div").toString())
    }

    @Test
    fun `the name in a Kotlin string is read decoded, in its tag from its opening bracket`() {
        // The caret just after `data-on:click__` in the string of the source: its modifiers are offered.
        fun at(src: String): List<String> {
            val offset = src.indexOf("data-on:click__") + "data-on:click__".length
            return analyzer.complete(Source(src, SourceLanguage.KOTLIN), offset, workspace).items.map { it.label }
        }
        for (src in listOf(
            "val x = \"<div data-show=\\\"\\\$a > 1\\\" data-on:click__\"",
            "fun side() = \"\"\"\n<div class=\"card\"\n     data-on:click__\n\"\"\"",
            // A sum is markup whole, a later literal marked by the comment as well: it stands in the first one's tag.
            "// language=HTML\nval s = \"<div\" + \" data-on:click__\"",
        )) {
            assertTrue("debounce" in at(src), src)
        }
    }

    @Test
    fun `the catalog says what each option does and names each action's parameters`() {
        val catalog = Catalog.default
        assertEquals(emptyList(), catalog.fetchOptions.filter { it.doc.isBlank() }.map { it.name })
        assertEquals(listOf("uri", "options?"), catalog.actionsByName.getValue("get").parameters)
        assertEquals(listOf("() => expr"), catalog.actionsByName.getValue("peek").parameters)
    }

    @Test
    fun `the prose of an expression is its strings and the text of its templates`() {
        val text = "\$usrnm + ' Lagree' + `x \${\$a} y` // done"
        // jsRegions counts the `${` and `}` of a template with its text.
        assertEquals(listOf("' Lagree'", "`x \${", "} y`"), proseRanges(text).map { text.substring(it.first, it.last + 1) })
    }

    @Test
    fun `the catalog offers the DOM events and Datastar's own`() {
        val catalog = Catalog.default
        val click = catalog.domEvents.firstOrNull { it.name == "click" }
        assertEquals("https://developer.mozilla.org/docs/Web/API/Element/click_event", click?.mdn)
        assertEquals(true, catalog.domEvents.any { it.name == "animationend" })
        assertEquals(listOf("datastar-fetch", "datastar-signal-patch"), catalog.datastarEvents.map { it.name })
    }
}
