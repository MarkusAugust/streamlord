package io.github.markusaugust.streamlord.core

import io.github.markusaugust.streamlord.core.json.JsonArray
import io.github.markusaugust.streamlord.core.json.JsonBoolean
import io.github.markusaugust.streamlord.core.json.JsonNull
import io.github.markusaugust.streamlord.core.json.JsonNumber
import io.github.markusaugust.streamlord.core.json.JsonObject
import io.github.markusaugust.streamlord.core.json.JsonParser
import io.github.markusaugust.streamlord.core.json.JsonString
import io.github.markusaugust.streamlord.core.json.JsonWriter
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class JsonTest {

    @Test
    fun `parses the shapes signals take`() {
        val obj = JsonParser.parseObject(
            """{"search":"ash","count":3,"ratio":0.5,"big":12345678901234,"on":true,"gone":null,
               "user":{"name":"Gorvek","tags":["a","b"]},"esc":"q\"\\\/\n\u00e6\ud83d\ude00"}""",
        )
        assertEquals("ash", obj.string("search"))
        assertEquals(3, obj.int("count"))
        assertEquals(0.5, obj.double("ratio"))
        assertEquals(12345678901234L, obj.long("big"))
        assertNull(obj.int("big"))
        assertEquals(true, obj.boolean("on"))
        assertEquals(JsonNull, obj["gone"])
        assertNull(obj.string("gone"))
        assertEquals("Gorvek", obj.obj("user")?.string("name"))
        assertEquals(listOf("a", "b"), obj.obj("user")?.array("tags")?.strings())
        assertEquals(JsonString("Gorvek"), obj.path("user", "name"))
        assertNull(obj.path("user", "missing", "deeper"))
        assertEquals("q\"\\/\n\u00e6\uD83D\uDE00", obj.string("esc"))
        assertNull(obj.string("count"))
    }

    @Test
    fun `parses scalars and whitespace`() {
        assertEquals(JsonNumber("-1.5e3"), JsonParser.parse(" -1.5e3 "))
        assertEquals(JsonBoolean.FALSE, JsonParser.parse("false"))
        assertEquals(JsonArray(emptyList()), JsonParser.parse("[ ]"))
        assertEquals(JsonObject.EMPTY, JsonParser.parse("{ }"))
    }

    @Test
    fun `rejects what the standard rejects`() {
        listOf(
            "{'a':1}", "{a:1}", "[1,]", "{\"a\":1,}", "01", "1.", ".5", "NaN", "[1] x", "\"unterminated",
            "\"tab\tinside\"", "{\"a\":1}}", "", "tru", "[1 2]", "\"\\x41\"", "\"\\u12\"",
        ).forEach { bad ->
            assertFailsWith<JsonParseException>("should reject: $bad") { JsonParser.parse(bad) }
        }
        assertFailsWith<JsonParseException> { JsonParser.parseObject("[1]") }
    }

    @Test
    fun `nesting depth is capped`() {
        val deep = "[".repeat(100) + "]".repeat(100)
        assertFailsWith<JsonParseException> { JsonParser.parse(deep) }
        JsonParser.parse(deep, maxDepth = 100)
    }

    @Test
    fun `last duplicate key wins as in JSON parse`() {
        assertEquals(2, JsonParser.parseObject("""{"a":1,"a":2}""").int("a"))
    }

    @Test
    fun `writes plain kotlin values`() {
        val json = JsonWriter.write(
            linkedMapOf(
                "s" to "x", "i" to 1, "l" to 2L, "d" to 1.5, "b" to true, "n" to null,
                "list" to listOf(1, "two"), "arr" to intArrayOf(3), "map" to mapOf("k" to "v"),
                "enum" to Thread.State.NEW, "dec" to BigDecimal("1.10"), "c" to 'c',
            ),
        )
        assertEquals(
            """{"s":"x","i":1,"l":2,"d":1.5,"b":true,"n":null,"list":[1,"two"],"arr":[3],"map":{"k":"v"},"enum":"NEW","dec":1.10,"c":"c"}""",
            json,
        )
    }

    @Test
    fun `escapes for JSON and for JavaScript contexts`() {
        assertEquals(""""a\"b\\c\nd\u0001e\u2028f<\/script>"""", JsonWriter.writeString("a\"b\\c\nd\u0001e\u2028f</script>"))
        assertEquals("\"http://x/y\"", JsonWriter.writeString("http://x/y"))
    }

    @Test
    fun `round trips through the value tree`() {
        val text = """{"a":[1,2.5,"s",null,true,{"b":{}}],"c":-0.0,"d":1e10}"""
        assertEquals(text, JsonParser.parse(text).toJson())
    }

    @Test
    fun `refuses arbitrary objects and non-finite numbers`() {
        class Sorcerer(val name: String)
        assertFailsWith<SignalsCodecException> { JsonWriter.write(Sorcerer("x")) }
        assertFailsWith<SignalsCodecException> { JsonWriter.write(Double.NaN) }
    }

    @Test
    fun `cycles are cut off by the depth limit`() {
        val cyclic = mutableListOf<Any?>()
        cyclic.add(cyclic)
        assertFailsWith<SignalsCodecException> { JsonWriter.write(cyclic) }
    }

    @Test
    fun `toKotlin yields plain collections`() {
        val k = JsonParser.parseObject("""{"a":1,"b":[true,null],"c":{"d":"e"},"f":1.5}""").toKotlin()
        assertEquals(mapOf("a" to 1L, "b" to listOf(true, null), "c" to mapOf("d" to "e"), "f" to 1.5), k)
    }
}
