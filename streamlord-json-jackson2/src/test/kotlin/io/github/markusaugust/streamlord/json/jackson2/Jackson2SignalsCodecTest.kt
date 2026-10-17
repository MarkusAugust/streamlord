package io.github.markusaugust.streamlord.json.jackson2

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.MapperFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import io.github.markusaugust.streamlord.core.SignalsCodecException
import io.github.markusaugust.streamlord.core.SignalsDecodeException
import io.github.markusaugust.streamlord.core.port.driven.decode
import io.github.markusaugust.streamlord.core.port.driven.encode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class Jackson2SignalsCodecTest {
    data class Signals(
        val search: String = "",
        val count: Int = 0,
        val gone: String? = null,
    )

    private val codec = Jackson2SignalsCodec.default()

    @Test
    fun `encodes with nulls`() {
        assertEquals("""{"search":"ash","count":0,"gone":null}""", codec.encode(Signals(search = "ash")))
    }

    @Test
    fun `decodes data classes through the kotlin module`() {
        assertEquals(Signals("x", 2), codec.decode<Signals>("""{"search":"x","count":2}"""))
        assertEquals(mapOf("a" to 1), codec.decode<Map<String, Int>>("""{"a":1}"""))
    }

    interface Shape

    // No request can make an interface buildable: the server's to fix, so not a decode failure.
    @Test
    fun `a type that cannot be built is the server's`() {
        val failure = assertFailsWith<SignalsCodecException> { codec.decode<Shape>("""{"sides":3}""") }
        assertFalse(failure is SignalsDecodeException, "$failure")
    }

    @Test
    fun `wraps failures`() {
        assertFailsWith<SignalsDecodeException> { codec.decode<Signals>("""{"count":"no"}""") }
        assertFailsWith<SignalsDecodeException> { codec.decode<Signals>("not json") }
    }

    // The browser sends its whole store; the type names only what one handler accepts.
    @Test
    fun `ignores the signals the type does not name`() {
        assertEquals(Signals("x", 2), codec.decode<Signals>("""{"search":"x","count":2,"elsewhere":true}"""))
    }

    data class Delete(
        val id: Long,
    )

    data class Page(
        val page: Int = 1,
    )

    data class Find(
        val q: String?,
    )

    // A required signal the browser did not send must not reach the handler as 0.
    @Test
    fun `a missing or null primitive is refused, whatever the mapper says`() {
        val lenient =
            Jackson2SignalsCodec(
                ObjectMapper()
                    .registerModule(
                        KotlinModule.Builder().build(),
                    ).configure(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES, false),
            )
        for (c in listOf(codec, lenient)) {
            assertFailsWith<SignalsDecodeException> { c.decode<Delete>("{}") }
            assertFailsWith<SignalsDecodeException> { c.decode<Delete>("""{"id":null}""") }
            assertFailsWith<SignalsDecodeException> { c.decode<Page>("""{"page":null}""") }
            assertEquals(Page(), c.decode<Page>("{}"))
            // Unlike kotlinx, the Kotlin module reads a nullable parameter as optional.
            assertEquals(Find(null), c.decode<Find>("{}"))
        }
    }

    @Test
    fun `anything after the value is refused`() {
        assertFailsWith<SignalsDecodeException> { codec.decode<Page>("""{"page":2} {"page":3}""") }
    }

    // Without the module Jackson reads past the constructor: values dropped, null in a String, or a 400 that is the server's.
    @Test
    fun `a Kotlin class needs the Kotlin module on the mapper`() {
        val bare = Jackson2SignalsCodec(ObjectMapper())
        for (body in listOf("""{"page":3}""", "{}")) {
            val failure = assertFailsWith<SignalsCodecException> { bare.decode<Page>(body) }
            assertFalse(failure is SignalsDecodeException, "$failure")
        }
        assertFailsWith<SignalsCodecException> { bare.decode<List<Delete>>("""[{"id":1}]""") }
        assertEquals(mapOf("a" to 1), bare.decode<Map<String, Int>>("""{"a":1}"""))
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "t")
    @JsonSubTypes(JsonSubTypes.Type(value = Box::class, name = "box"))
    sealed interface Shaped

    data class Box(
        val label: String = "",
        val page: Int = 1,
    ) : Shaped

    // The interface names no class Jackson builds, but its subtypes are Kotlin and lose their values the same way.
    @Test
    fun `a Kotlin interface needs the Kotlin module too`() {
        assertFailsWith<SignalsCodecException> {
            Jackson2SignalsCodec(
                ObjectMapper(),
            ).decode<Shaped>("""{"t":"box","label":"x","page":3}""")
        }
        assertEquals(Box("x", 3), codec.decode<Shaped>("""{"t":"box","label":"x","page":3}"""))
    }

    // A Java creator keeps the mapper's rule, under which a missing component that takes null is null.
    @Test
    fun `a Java record may leave out a component that takes null`() {
        for (c in listOf(codec, Jackson2SignalsCodec(ObjectMapper()))) {
            assertEquals(JavaSignals("a", null, 1), c.decode<JavaSignals>("""{"name":"a","n":1}"""))
            assertFailsWith<SignalsDecodeException> { c.decode<JavaSignals>("""{"name":"a","n":null}""") }
        }
    }

    // Spring Boot keeps it on, but the introspector the module adds is there either way.
    @Test
    fun `the Kotlin module is found when module ids are not recorded`() {
        val mapper =
            JsonMapper
                .builder()
                .disable(MapperFeature.IGNORE_DUPLICATE_MODULE_REGISTRATIONS)
                .addModule(KotlinModule.Builder().build())
                .build()
        assertEquals(Page(3), Jackson2SignalsCodec(mapper).decode<Page>("""{"page":3}"""))
    }

    data class Item(
        val n: Int = 0,
    )

    data class Held(
        val xs: List<Int> = emptyList(),
        val m: Map<String, Int> = emptyMap(),
        val items: List<Item> = emptyList(),
        val tags: Set<String> = emptySet(),
        val names: Array<String> = emptyArray(),
        val maybe: List<Int?> = emptyList(),
        val maybeMap: Map<String, Int?> = emptyMap(),
    )

    // A null where the type holds none would reach the handler as a NullPointerException.
    @Test
    fun `a null inside a collection that holds none is refused, whatever the mapper says`() {
        for (c in listOf(
            codec,
            Jackson2SignalsCodec(
                com.fasterxml.jackson.module.kotlin
                    .jacksonObjectMapper(),
            ),
        )) {
            for (body in listOf(
                """{"xs":[1,null]}""",
                """{"m":{"a":null}}""",
                """{"items":[null]}""",
                """{"tags":[null]}""",
                """{"names":["a",null]}""",
            )) {
                assertFailsWith<SignalsDecodeException>(body) { c.decode<Held>(body) }
            }
            val held = c.decode<Held>("""{"maybe":[1,null],"maybeMap":{"a":null}}""")
            assertEquals(listOf(1, null), held.maybe)
            assertEquals(mapOf("a" to null), held.maybeMap)
            assertFailsWith<SignalsDecodeException> { c.decode<Map<String, Int>>("""{"a":null}""") }
            assertEquals(mapOf("a" to null), c.decode<Map<String, Int?>>("""{"a":null}"""))
        }
    }

    // jacksonObjectMapper() refuses unknown properties, as Jackson 2 does by default.
    @Test
    fun `ignores the signals the type does not name with a mapper that refuses them`() {
        val mapper =
            com.fasterxml.jackson.module.kotlin
                .jacksonObjectMapper()
        assertEquals(Page(2), Jackson2SignalsCodec(mapper).decode<Page>("""{"page":2,"other":1}"""))
        // The codec reads through a copy; the mapper handed to it keeps its own rules.
        assertFailsWith<com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException> {
            mapper.readValue("""{"page":2,"other":1}""", Page::class.java)
        }
        assertEquals(listOf(1, null), mapper.readValue("""{"xs":[1,null]}""", Held::class.java).xs)
    }
}
