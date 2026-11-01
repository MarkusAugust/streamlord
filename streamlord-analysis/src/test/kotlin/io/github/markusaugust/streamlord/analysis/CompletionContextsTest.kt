package io.github.markusaugust.streamlord.analysis

import kotlin.test.Test
import kotlin.test.assertEquals

class CompletionContextsTest {
    @Test
    fun `which attribute value a caret stands in`() {
        for (case in twinCases("completion-contexts.json")) {
            val name = case.string("name")
            val before = case.string("before")!!
            val markup = before + case.string("after")!!
            val prefix = case.string("prefix") ?: "data-"
            val at = valueAtCaret(markup, before.length, prefix)
            assertEquals(case.string("attribute"), at?.attribute, name)
            if (at != null) assertEquals(case.string("value"), at.before, name)
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
}
