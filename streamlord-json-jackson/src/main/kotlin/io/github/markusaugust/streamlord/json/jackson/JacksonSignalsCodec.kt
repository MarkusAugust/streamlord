package io.github.markusaugust.streamlord.json.jackson

import io.github.markusaugust.streamlord.core.SignalsCodecException
import io.github.markusaugust.streamlord.core.SignalsDecodeException
import io.github.markusaugust.streamlord.core.port.driven.SignalsCodec
import tools.jackson.core.JacksonException
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.exc.InvalidDefinitionException
import tools.jackson.databind.json.JsonMapper
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.javaType

/**
 * A [SignalsCodec] backed by Jackson 3 (`tools.jackson`).
 *
 * Hand it the `ObjectMapper` your application already owns (in Spring Boot, the auto-configured
 * bean) so signals obey the same rules as every other JSON in the realm. [default] builds a
 * mapper that discovers registered modules, including `jackson-module-kotlin` when present.
 *
 * Four rules hold whatever the mapper says, because the type is what a request may send. A
 * `null` for a primitive is refused, also when the parameter has a default and when the mapper
 * has the Kotlin module's `NullIsSameAsDefault` on, so declare it `Int?` if the page may send
 * `null`. A Kotlin parameter must be sent unless it has a default or, unlike with kotlinx, takes
 * `null`, even when the mapper turns off `FAIL_ON_NULL_FOR_PRIMITIVES`, which otherwise lets a
 * missing `val id: Long` arrive as `0`; a Java creator keeps the mapper's rule. A key the type
 * does not name is ignored, as the other codecs ignore it, even when the mapper turns on
 * `FAIL_ON_UNKNOWN_PROPERTIES`. And nothing may follow the JSON value.
 *
 * A `null` inside a Kotlin parameter's `List<Int>` or `Map<String, Int>` is refused by the
 * Kotlin module's `StrictNullChecks`, on by default in Jackson 3; a mapper that turns it off lets
 * one through. One inside a `List<Int>` or `Map<String, List<Int>>` read whole is refused by the
 * codec, at any depth, whatever the mapper says.
 *
 * A Kotlin class or interface in the type needs `jackson-module-kotlin` on the mapper, even one
 * Jackson could build without it, such as a class with `var` properties. Without the module
 * Jackson reads a Kotlin class past its constructor, builds it from its defaults and drops what the browser sent, or
 * refuses a class without defaults, so decoding throws
 * [SignalsCodecException] instead.
 *
 * ```kotlin
 * val streamlord = Streamlord(codec = JacksonSignalsCodec(objectMapper))
 * ```
 */
public class JacksonSignalsCodec(
    private val mapper: ObjectMapper,
) : SignalsCodec {
    override fun encode(
        value: Any?,
        type: KType,
    ): String =
        try {
            mapper.writeValueAsString(value)
        } catch (e: JacksonException) {
            throw SignalsCodecException("Could not encode $type as signals", e)
        }

    @OptIn(ExperimentalStdlibApi::class)
    override fun <T : Any> decode(
        json: String,
        type: KType,
    ): T =
        try {
            val kotlin = kotlinClassIn(type)
            if (kotlin != null && !hasKotlinModule()) throw noKotlinModule(type, kotlin)
            val javaType = mapper.constructType(type.javaType)
            mapper
                .readerFor(javaType)
                .with(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES, DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .without(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                // A Kotlin parameter with no default must be sent, as kotlinx has it; the Kotlin module
                // still lets one that takes null go missing. A Java creator keeps the mapper's rule.
                .let { if (kotlin != null) it.with(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES) else it }
                .readValue<T>(json)
                ?.also { refuseNullValues(it, type) }
                ?: throw SignalsDecodeException("Signals decoded to null for $type")
        } catch (e: InvalidDefinitionException) {
            // The type itself cannot be built, whatever the request sends: the server's to fix.
            throw SignalsCodecException("Could not decode signals into $type", e)
        } catch (e: JacksonException) {
            throw SignalsDecodeException("Could not decode signals into $type", e)
        }

    private fun hasKotlinModule(): Boolean = mapper.registeredModules().any { it.registrationId == KOTLIN_MODULE }

    public companion object {
        private const val KOTLIN_MODULE = "tools.jackson.module.kotlin.KotlinModule"

        /*
         * The Kotlin module checks what a constructor holds, not the type asked for, so a
         * List<Int> or Map<String, List<Int>> read whole is checked here, through every list,
         * set, array and map it nests.
         */
        private fun refuseNullValues(
            value: Any?,
            type: KType,
            whole: KType = type,
        ) {
            val (items, item) =
                when (value) {
                    is Map<*, *> -> value.values to type.arguments.getOrNull(1)?.type
                    is Collection<*> -> value to type.arguments.getOrNull(0)?.type
                    is Array<*> -> value.asList() to type.arguments.getOrNull(0)?.type
                    else -> return
                }
            if (item == null) return
            for (each in items) {
                if (each == null && !item.isMarkedNullable) {
                    throw SignalsDecodeException("Signals held null where $whole takes none")
                }
                refuseNullValues(each, item, whole)
            }
        }

        /** The first Kotlin class or interface in [type] or its arguments; an enum Jackson reads by name either way. */
        private fun kotlinClassIn(type: KType): KClass<*>? {
            val klass = type.classifier as? KClass<*>
            val java = klass?.java
            if (java != null && java.isAnnotationPresent(Metadata::class.java) && !java.isEnum) return klass
            return type.arguments.firstNotNullOfOrNull { argument -> argument.type?.let { kotlinClassIn(it) } }
        }

        private fun noKotlinModule(
            type: KType,
            klass: KClass<*>,
        ) = SignalsCodecException(
            "Could not decode signals into $type: ${klass.qualifiedName} is a Kotlin class and the mapper has no " +
                "jackson-module-kotlin, without which Jackson drops what the browser sent",
        )

        /** A codec over a fresh mapper with every module on the classpath registered. */
        public fun default(): JacksonSignalsCodec = JacksonSignalsCodec(JsonMapper.builder().findAndAddModules().build())
    }
}
