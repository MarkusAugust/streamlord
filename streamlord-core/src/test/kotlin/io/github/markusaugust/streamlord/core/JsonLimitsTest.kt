package io.github.markusaugust.streamlord.core

import io.github.markusaugust.streamlord.core.json.JsonArray
import io.github.markusaugust.streamlord.core.json.JsonObject
import io.github.markusaugust.streamlord.core.json.JsonParser
import io.github.markusaugust.streamlord.core.json.JsonWriter
import io.github.markusaugust.streamlord.fixtures.assertGrowsLinearly
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class JsonLimitsTest {
    // A million digits fit inside the default signal size limit and took over twenty seconds
    // to turn into a number the first time an accessor was called.
    @Test
    fun `a number of a million digits is refused before anything reads it`() {
        assertGrowsLinearly("refusing a long number", 16_000, { n -> """{"n":""" + "9".repeat(n) + "}" }) { body ->
            assertFailsWith<JsonParseException> { JsonParser.parseObject(body) }
        }
    }

    @Test
    fun `a float is written in its own shortest form`() {
        assertEquals("[0.1]", JsonWriter.write(listOf(0.1f)))
    }

    @Test
    fun `only ascii hex digits are digits in a unicode escape`() {
        assertFailsWith<JsonParseException> { JsonParser.parse("\"\\u\uFF11234\"") }
        assertFailsWith<JsonParseException> { JsonParser.parse("\"\\u\u0666234\"") }
    }

    @Test
    fun `two keys that are written alike are refused`() {
        assertFailsWith<SignalsCodecException> { JsonWriter.write(mapOf(1 to "a", "1" to "b")) }
    }

    @Test
    fun `the writer stops at the depth the parser stops at`() {
        fun nested(levels: Int): Any = (1 until levels).fold(emptyList<Any>() as Any) { inner, _ -> listOf(inner) }

        JsonParser.parse(JsonWriter.write(nested(64)))
        assertFailsWith<SignalsCodecException> { JsonWriter.write(nested(65)) }
    }

    @Test
    fun `equality with a plain map or list holds in both directions`() {
        assertTrue(JsonObject.EMPTY == emptyMap<String, Any>() && emptyMap<String, Any>() == JsonObject.EMPTY)
        assertTrue(JsonArray(emptyList()) == emptyList<Any>() && emptyList<Any>() == JsonArray(emptyList()))
    }
}
