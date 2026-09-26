package io.github.markusaugust.streamlord.core

import io.github.markusaugust.streamlord.core.json.JsonNumber
import io.github.markusaugust.streamlord.core.json.JsonObject
import io.github.markusaugust.streamlord.core.json.JsonParser
import io.github.markusaugust.streamlord.core.json.JsonValue
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.measureTime

/**
 * Hostile input for the built-in parser. The only acceptable outcomes are a [JsonValue] or a
 * [JsonParseException]; anything else (stack overflow, other exceptions, runaway time) is a wound.
 */
class JsonRobustnessTest {

    @Test
    fun `random garbage never escapes as anything but JsonParseException`() {
        val random = Random(1337)
        val alphabet = "{}[]\":,\\/ \n\t0123456789.-+eEtrufalsn\u0000\u2028ø😀"
        repeat(20_000) {
            val text = buildString { repeat(random.nextInt(0, 40)) { append(alphabet[random.nextInt(alphabet.length)]) } }
            try {
                JsonParser.parse(text)
            } catch (_: JsonParseException) {
                // expected
            }
        }
    }

    @Test
    fun `mutated valid documents never escape as anything but JsonParseException`() {
        val random = Random(42)
        val seed = """{"user":{"name":"Gorvek","tags":["a","b",{"x":null}],"n":-12.5e3,"ok":true},"list":[1,2,3],"s":"\u00e6\n"}"""
        repeat(20_000) {
            val chars = seed.toCharArray()
            repeat(random.nextInt(1, 4)) {
                chars[random.nextInt(chars.size)] = "{}[]\":,\\0e- \u0000".random(random)
            }
            try {
                JsonParser.parse(String(chars))
            } catch (_: JsonParseException) {
                // expected
            }
        }
    }

    @Test
    fun `deep nesting is refused long before the stack ends`() {
        for (open in listOf("[", "{\"a\":")) {
            val close = if (open == "[") "]" else "}"
            val deep = open.repeat(100_000) + close.repeat(100_000)
            assertFailsWith<JsonParseException> { JsonParser.parse(deep) }
        }
    }

    @Test
    fun `one mebibyte parses in linear time`() {
        val big = buildString {
            append('{')
            var i = 0
            while (length < 1_048_576) {
                if (i > 0) append(',')
                append("\"k").append(i).append("\":\"").append("v".repeat(50)).append('"')
                i++
            }
            append('}')
        }
        val elapsed = measureTime { assertTrue((JsonParser.parse(big) as JsonObject).size > 10_000) }
        assertTrue(elapsed.inWholeMilliseconds < 2_000, "took $elapsed")
    }

    @Test
    fun `pathological numbers do not throw from accessors`() {
        val obj = JsonParser.parseObject("""{"a":1e2147483648,"b":1e999999999,"c":-0,"d":123456789012345678901234567890,"e":1E400}""")
        assertNull(obj.int("a"))
        assertNull(obj.long("a"))
        obj.decimal("a")
        assertEquals(Double.POSITIVE_INFINITY, obj.double("a"))
        assertNull(obj.long("b"))
        assertEquals(0L, obj.long("c"))
        assertNull(obj.long("d"))
        assertEquals(java.math.BigDecimal("123456789012345678901234567890"), obj.decimal("d"))
        assertEquals(Double.POSITIVE_INFINITY, obj.double("e"))
        assertNull(JsonNumber("1e99999999999").toBigDecimalOrNull())
        assertNull(JsonNumber("1e99999999999").toLongOrNull())
        assertFailsWith<ArithmeticException> { JsonNumber("1e99999999999").toBigDecimal() }
        obj.toKotlin()
    }

    @Test
    fun `long strings and unicode escapes are handled`() {
        val s = "\"" + "\\u0041".repeat(200_000) + "\""
        assertEquals("A".repeat(200_000), (JsonParser.parse(s) as io.github.markusaugust.streamlord.core.json.JsonString).value)
        assertFailsWith<JsonParseException> { JsonParser.parse("\"\\uD83D\"".dropLast(1)) }
    }
}
