package io.github.markusaugust.streamlord.json.jackson2

import com.fasterxml.jackson.annotation.JsonSetter
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.annotation.Nulls
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.MapperFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import io.github.markusaugust.streamlord.core.SignalsCodecException
import io.github.markusaugust.streamlord.core.SignalsDecodeException
import io.github.markusaugust.streamlord.core.port.driven.decode
import io.github.markusaugust.streamlord.core.port.driven.encode
import java.io.File
import java.net.URLClassLoader
import kotlin.reflect.KType
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
        val deep: Map<String, List<List<Int>>> = emptyMap(),
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
                """{"deep":{"a":[[1],[2,null]]}}""",
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

    @JvmInline
    value class Id(
        val v: Int,
    )

    data class WithId(
        val id: Id,
        val xs: List<Int> = emptyList(),
        val m: Map<String, Int> = emptyMap(),
        val ids: List<Id> = emptyList(),
    )

    data class WithIdRequired(
        val id: Id,
        val xs: List<Int>,
        val m: Map<String, Int>,
    )

    // A value class parameter hides the constructor kotlin-reflect knows behind a synthetic one.
    @Test
    fun `a null inside a collection is refused beside a value class`() {
        for (c in listOf(
            codec,
            Jackson2SignalsCodec(
                com.fasterxml.jackson.module.kotlin
                    .jacksonObjectMapper(),
            ),
        )) {
            for (body in listOf("""{"id":1,"xs":[1,null]}""", """{"id":1,"m":{"a":null}}""", """{"id":1,"ids":[null]}""")) {
                assertFailsWith<SignalsDecodeException>(body) { c.decode<WithId>(body) }
            }
            for (body in listOf("""{"id":1,"xs":[1,null],"m":{}}""", """{"id":1,"xs":[],"m":{"a":null}}""")) {
                assertFailsWith<SignalsDecodeException>(body) { c.decode<WithIdRequired>(body) }
            }
            assertEquals(WithId(Id(1), listOf(2), ids = listOf(Id(3))), c.decode<WithId>("""{"id":1,"xs":[2],"ids":[3]}"""))
        }
    }

    // A Java collection takes null; its platform type only looks as if it did not.
    @Test
    fun `a Java record keeps null inside its collections`() {
        for (c in listOf(codec, Jackson2SignalsCodec(ObjectMapper()))) {
            assertEquals(
                JavaHeld(listOf(1, null), mapOf("a" to null)),
                c.decode<JavaHeld>("""{"xs":[1,null],"m":{"a":null}}"""),
            )
        }
    }

    data class Crate<T>(
        val items: List<T>,
    )

    data class Keyed<V>(
        val m: Map<String, V>,
    )

    // What T stands for is known only to the type asked for, so the parameter's List<T> is left alone.
    @Test
    fun `a null inside a generic container is left to the type argument`() {
        for (c in listOf(codec, Jackson2SignalsCodec(ObjectMapper().registerModule(KotlinModule.Builder().build())))) {
            assertEquals(Crate(listOf(1, null)), c.decode<Crate<Int?>>("""{"items":[1,null]}"""))
            assertEquals(Crate(listOf("a", null)), c.decode<Crate<String?>>("""{"items":["a",null]}"""))
            assertEquals(Keyed<Int?>(mapOf("a" to null)), c.decode<Keyed<Int?>>("""{"m":{"a":null}}"""))
        }
    }

    class Settable {
        var xs: List<Int?> = emptyList()
    }

    // The rule is for creator parameters; a setter or field keeps the mapper's own.
    @Test
    fun `a setter keeps null inside its collection`() {
        assertEquals(listOf(1, null), codec.decode<Settable>("""{"xs":[1,null]}""").xs)
        assertEquals(listOf(1, null), codec.decode<JavaBean>("""{"xs":[1,null]}""").xs)
    }

    data class Made(
        val xs: List<Int>,
    ) {
        companion object {
            @JvmStatic
            @com.fasterxml.jackson.annotation.JsonCreator
            fun of(
                @com.fasterxml.jackson.annotation.JsonProperty("xs") xs: List<Int>,
            ) = Made(xs)
        }
    }

    @Test
    fun `a null inside a factory method's collection is refused`() {
        assertFailsWith<SignalsDecodeException> { codec.decode<Made>("""{"xs":[1,null]}""") }
        assertEquals(Made(listOf(1)), codec.decode<Made>("""{"xs":[1]}"""))
    }

    /*
     * Jackson reads the primary constructor. kotlin-reflect lists the secondary ones first, and
     * each could pass for it: the first by its parameter types less the last, the second by its
     * DefaultConstructorMarker.
     */
    class Strict(
        id: Id,
        xs: List<Int>,
    ) {
        val xs: List<Int?> = xs

        constructor(id: Int, xs: List<Int?>, extra: Int) : this(Id(id + extra), xs.filterNotNull())
        constructor(id: Id, xs: List<Int?>, extra: String) : this(id, xs.filterNotNull())
    }

    class Lenient(
        id: Id,
        xs: List<Int?>,
    ) {
        val xs: List<Int?> = xs

        constructor(id: Int, xs: List<Int>, extra: Int) : this(Id(id + extra), xs)
        constructor(id: Id, xs: List<Int>, extra: String) : this(id, xs)
    }

    @Test
    fun `the constructor behind a value class is the one Jackson reads`() {
        assertFailsWith<SignalsDecodeException> { codec.decode<Strict>("""{"id":1,"xs":[1,null]}""") }
        assertEquals(listOf(1, null), codec.decode<Lenient>("""{"id":1,"xs":[1,null]}""").xs)
    }

    // StrictNullChecks does the same; an annotation on the parameter still decides.
    @Test
    fun `a mapper's default to skip null in collections does not reach a parameter`() {
        val skipping =
            Jackson2SignalsCodec(
                com.fasterxml.jackson.module.kotlin.jacksonObjectMapper().apply {
                    setDefaultSetterInfo(JsonSetter.Value.forContentNulls(Nulls.SKIP))
                },
            )
        assertFailsWith<SignalsDecodeException> { skipping.decode<Held>("""{"xs":[1,null]}""") }
        assertEquals(Skipped(listOf(1)), skipping.decode<Skipped>("""{"xs":[1,null]}"""))
        assertEquals(Skipped(listOf(1)), codec.decode<Skipped>("""{"xs":[1,null]}"""))
    }

    data class Skipped(
        @param:JsonSetter(contentNulls = Nulls.SKIP) val xs: List<Int>,
    )

    // Unlike Jackson 3, which reads it as 0; the table on the Signals page has both.
    @Test
    fun `a Java record missing a primitive is refused`() {
        assertFailsWith<SignalsDecodeException> { codec.decode<JavaSignals>("""{"name":"a"}""") }
    }

    // The Kotlin module checks a constructor's parameters; a type read whole is checked by the codec, all the way down.
    @Test
    fun `a null inside a collection read whole is refused at any depth`() {
        for (c in listOf(codec, Jackson2SignalsCodec(ObjectMapper()))) {
            assertFailsWith<SignalsDecodeException> { c.decode<Array<String>>("""["a",null]""") }
            assertEquals(listOf("a", null), c.decode<Array<String?>>("""["a",null]""").toList())
            assertFailsWith<SignalsDecodeException> { c.decode<List<Int>>("""[1,null]""") }
            assertFailsWith<SignalsDecodeException> { c.decode<Set<String>>("""["a",null]""") }
            assertFailsWith<SignalsDecodeException> { c.decode<Map<String, List<Int>>>("""{"a":[1,null]}""") }
            assertFailsWith<SignalsDecodeException> { c.decode<List<List<Int>>>("""[[1],[2,null]]""") }
            assertFailsWith<SignalsDecodeException> { c.decode<Map<String, Map<String, Int>>>("""{"a":{"b":null}}""") }
            assertEquals(listOf(listOf(1, null)), c.decode<List<List<Int?>>>("""[[1,null]]"""))
            assertEquals(mapOf("a" to null), c.decode<Map<String, List<Int>?>>("""{"a":null}"""))
        }
    }

    data class Created
        @com.fasterxml.jackson.annotation.JsonCreator
        constructor(
            @param:com.fasterxml.jackson.annotation.JsonProperty("xs") val xs: List<Int>,
        )

    // jackson-databind brings no kotlin-reflect; the Kotlin module does, and the rule above is installed only beside it.
    @Test
    fun `a mapper without the Kotlin module never needs kotlin-reflect`() {
        val urls =
            System
                .getProperty("java.class.path")
                .split(File.pathSeparator)
                .filterNot { File(it).name.startsWith("kotlin-reflect") }
                .map { File(it).toURI().toURL() }
                .toTypedArray()
        URLClassLoader(urls, ClassLoader.getPlatformClassLoader()).use { loader ->
            assertFailsWith<ClassNotFoundException> { loader.loadClass("kotlin.reflect.jvm.ReflectJvmMapping") }
            val mapperClass = loader.loadClass(ObjectMapper::class.java.name)
            val codec =
                loader
                    .loadClass(Jackson2SignalsCodec::class.java.name)
                    .getConstructor(mapperClass)
                    .newInstance(mapperClass.getConstructor().newInstance())
            val type =
                loader
                    .loadClass("kotlin.jvm.internal.Reflection")
                    .getMethod("typeOf", Class::class.java)
                    .invoke(null, loader.loadClass(JavaWrap::class.java.name))
            val decode = codec.javaClass.getMethod("decode", String::class.java, loader.loadClass(KType::class.java.name))
            assertEquals("JavaWrap[inner=Created(xs=[1])]", decode.invoke(codec, """{"inner":{"xs":[1]}}""", type).toString())
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

    enum class Colour { RED, GREEN }

    data class Painted(
        val c: Colour,
    )

    // Unlike kotlinx, which refuses both; the table on the Signals page has it.
    @Test
    fun `an enum is read by position as well as by name`() {
        for (body in listOf("""{"c":0}""", """{"c":"0"}""")) {
            assertEquals(Painted(Colour.RED), codec.decode<Painted>(body), body)
        }
    }
}
