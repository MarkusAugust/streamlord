package io.github.markusaugust.streamlord.analysis

import kotlin.test.Test
import kotlin.test.assertEquals

/** The cases of `twins/value-types.json`, which `valueTypes.test.ts` reads too. */
class ValueTypesTest {
    private val cases = twinCases("value-types.json")

    @Test
    fun `the fact of a declared type`() {
        for (c in cases.filter { it.string("type") != null }) {
            val fact = c.string("fact")?.let { ValueFact.valueOf(it.uppercase().replace('-', '_')) }
            assertEquals(fact, ValueFact.ofTypeName(c.string("type")!!), c.string("name"))
        }
    }

    @Test
    fun `whether a type is a collection of markup`() {
        for (c in cases.filter { it.string("collection") != null }) {
            assertEquals(c.boolean("markup"), isMarkupCollection(c.string("collection")!!), c.string("name"))
        }
    }

    @Test
    fun `whether a literal opens with a tag`() {
        for (c in cases.filter { it.string("content") != null }) {
            val trimMargin = c.boolean("trimMargin") ?: false
            assertEquals(c.boolean("tag"), opensWithTag(c.string("content")!!, trimMargin), c.string("name"))
        }
    }
}
