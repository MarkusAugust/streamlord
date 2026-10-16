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
 * Three rules hold whatever the mapper says, because the type is what a request may send. They
 * are the kotlinx codec's rules, with one exception. A constructor parameter must be sent unless
 * it has a default or, unlike kotlinx, takes `null`, even when the mapper turns off
 * `FAIL_ON_NULL_FOR_PRIMITIVES`, which otherwise lets a missing `val id: Long` arrive as `0`. A
 * `null` for a primitive is refused. And nothing may follow the JSON value.
 *
 * A Kotlin class needs `jackson-module-kotlin` on the mapper. Without it Jackson builds the class
 * from its defaults and drops what the browser sent, or refuses a class without defaults, so
 * decoding one throws [SignalsCodecException] instead.
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
            if (mapper.registeredModules().none { it.registrationId == KOTLIN_MODULE }) {
                kotlinClassIn(type)?.let { throw noKotlinModule(type, it) }
            }
            val javaType = mapper.constructType(type.javaType)
            mapper
                .readerFor(javaType)
                .with(
                    DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES,
                    DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES,
                    DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                ).readValue<T>(json) ?: throw SignalsDecodeException("Signals decoded to null for $type")
        } catch (e: InvalidDefinitionException) {
            // The type itself cannot be built, whatever the request sends: the server's to fix.
            throw SignalsCodecException("Could not decode signals into $type", e)
        } catch (e: JacksonException) {
            throw SignalsDecodeException("Could not decode signals into $type", e)
        }

    public companion object {
        private const val KOTLIN_MODULE = "tools.jackson.module.kotlin.KotlinModule"

        /** The first Kotlin class in [type] or its arguments that Jackson builds from its properties. */
        private fun kotlinClassIn(type: KType): KClass<*>? {
            val klass = type.classifier as? KClass<*>
            val java = klass?.java
            if (java != null && java.isAnnotationPresent(Metadata::class.java) && !java.isEnum && !java.isInterface) return klass
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
