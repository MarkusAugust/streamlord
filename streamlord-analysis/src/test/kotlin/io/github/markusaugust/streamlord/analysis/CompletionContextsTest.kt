package io.github.markusaugust.streamlord.analysis

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CompletionContextsTest {
    @Test
    fun `which attribute value a caret stands in`() {
        for (case in twinCases("completion-contexts.json")) {
            val name = case.string("name")
            val before = case.string("before")!!
            val markup = before + case.string("after")!!
            val prefix = case.string("prefix") ?: "data-"
            val at = valueAtCaret(markup, before.length, prefix, case.boolean("templates") ?: true)
            assertEquals(case.string("attribute"), at?.attribute, name)
            if (at != null) assertEquals(case.string("value"), at.before, name)
        }
    }

    @Test
    fun `which tag a caret stands in`() {
        val cases = twinCases("completion-contexts.json").filter { it.containsKey("tag") }
        assertTrue(cases.isNotEmpty())
        for (case in cases) {
            val before = case.string("before")!!
            val tag =
                tagBeforeCaret(
                    before + case.string("after")!!,
                    before.length,
                    case.string("prefix") ?: "data-",
                    case.boolean("templates") ?: true,
                )
            assertEquals(case.string("tag"), tag, case.string("name"))
        }
    }

    @Test
    fun `the tag in a Kotlin string is read decoded, from its opening bracket`() {
        // The caret just after `data-on:click__` in the string of the source.
        fun at(src: String): String? {
            val offset = src.indexOf("data-on:click__") + "data-on:click__".length
            return tagBeforeCaret(lexKotlin(src).stringAt(offset)!!, offset, "data-")
        }
        assertEquals(
            "<div data-show=\"\$a > 1\" data-on:click__",
            at("val x = \"<div data-show=\\\"\\\$a > 1\\\" data-on:click__\""),
            "escaped quotes around a value holding >",
        )
        assertEquals(
            "<div class=\"card\"\n     data-on:click__",
            at("fun side() = \"\"\"\n<div class=\"card\"\n     data-on:click__\n\"\"\""),
            "a raw string over two lines",
        )
    }

    @Test
    fun `which Datastar expression a caret stands in`() {
        val analyzer = Analyzer()
        for (case in twinCases("expressions-at-caret.json")) {
            val name = case.string("name")
            val before = case.string("before")!!
            val at =
                analyzer.expressionAtCaret(
                    before + case.string("after")!!,
                    before.length,
                    case.boolean("kotlin")!!,
                    case.string("prefix") ?: "data-",
                )
            val expected = case.obj("expression")
            assertEquals(expected?.string("before"), at?.before, name)
            if (expected == null) continue
            assertEquals(expected.boolean("allowActions"), at?.allowActions, name)
            assertEquals(expected.boolean("patchInScope"), at?.patchInScope, name)
        }
    }

    @Test
    fun `which action call a caret stands in`() {
        for (case in twinCases("action-calls.json")) {
            val name = case.string("name")
            val before = case.string("before")!!
            val call = actionCallAt(before, before.length)
            assertEquals(case.string("action"), call?.action, name)
            if (call == null) continue
            assertEquals(case.int("argument"), call.argument, name)
            val option = case.obj("option")
            assertEquals(option?.string("typed"), call.option?.typed, name)
            assertEquals(option?.array("written")?.strings(), call.option?.written, name)
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
