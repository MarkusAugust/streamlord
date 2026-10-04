package io.github.markusaugust.streamlord.core

import io.github.markusaugust.streamlord.core.json.JsonArray
import io.github.markusaugust.streamlord.core.json.JsonObject
import io.github.markusaugust.streamlord.core.json.JsonParser
import io.github.markusaugust.streamlord.core.json.JsonWriter
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.measureTime

class JsonLimitsTest {
    // A million digits fit inside the default signal size limit and took over twenty seconds
    // to turn into a number the first time an accessor was called.
    @Test
    fun `a number of a million digits is refused before anything reads it`() {
        val body = """{"n":""" + "9".repeat(1_000_000) + "}"

        val spent = measureTime { assertFailsWith<JsonParseException> { JsonParser.parseObject(body) } }

        assertTrue(spent.inWholeMilliseconds < 2_000, "took $spent")
    }

    @Test
    fun `a number at the limit is read exactly`() {
        val digits = "9".repeat(JsonParser.MAX_NUMBER_LENGTH)

        assertEquals(BigDecimal(digits), JsonParser.parseObject("""{"n":$digits}""").decimal("n"))
    }

    @Test
    fun `a decimal with an enormous exponent is written as it is, not as two billion zeros`() {
        assertEquals("""{"n":1E+2000000000}""", JsonWriter.write(mapOf("n" to BigDecimal("1E+2000000000"))))
        assertEquals("""{"n":1.10}""", JsonWriter.write(mapOf("n" to BigDecimal("1.10"))))
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
