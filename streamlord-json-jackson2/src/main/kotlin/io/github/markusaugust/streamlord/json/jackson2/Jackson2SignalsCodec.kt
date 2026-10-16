package io.github.markusaugust.streamlord.json.jackson2

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.exc.InvalidDefinitionException
import io.github.markusaugust.streamlord.core.SignalsCodecException
import io.github.markusaugust.streamlord.core.SignalsDecodeException
import io.github.markusaugust.streamlord.core.port.driven.SignalsCodec
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.javaType

/**
 * A [SignalsCodec] backed by Jackson 2 (`com.fasterxml.jackson`).
 *
 * Hand it the `ObjectMapper` your application already owns so signals obey the same rules as
 * every other JSON in the realm. [default] builds a mapper with every module on the classpath
 * registered, including `jackson-module-kotlin` when present.
 *
 * Three rules hold whatever the mapper says, because the type is what a request may send. They
 * are the kotlinx codec's rules, with one exception. A constructor parameter must be sent unless
 * it has a default or, unlike kotlinx, takes `null`. A `null` for a primitive, such as
 * `val id: Long`, is refused rather than read as `0`, which is what Jackson 2 and Spring Boot's
 * mapper do by default. And nothing may follow the JSON value.
 *
 * A Kotlin class needs `jackson-module-kotlin` on the mapper. Without it Jackson reads the class
 * past its constructor, puts `null` into a property that cannot hold one and refuses a class
 * without defaults, so decoding one throws [SignalsCodecException] instead.
 *
 * ```kotlin
 * @Bean fun streamlord(mapper: ObjectMapper) = Streamlord(codec = Jackson2SignalsCodec(mapper))
 * ```
 */
public class Jackson2SignalsCodec(
    private val mapper: ObjectMapper,
) : SignalsCodec {
    override fun encode(
        value: Any?,
        type: KType,
    ): String =
        try {
            mapper.writeValueAsString(value)
        } catch (e: JsonProcessingException) {
            throw SignalsCodecException("Could not encode $type as signals", e)
        }

    @OptIn(ExperimentalStdlibApi::class)
    override fun <T : Any> decode(
        json: String,
        type: KType,
    ): T =
        try {
            if (KOTLIN_MODULE !in mapper.registeredModuleIds) kotlinClassIn(type)?.let { throw noKotlinModule(type, it) }
            val javaType = mapper.typeFactory.constructType(type.javaType)
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
        } catch (e: JsonProcessingException) {
            throw SignalsDecodeException("Could not decode signals into $type", e)
        }

    public companion object {
        private const val KOTLIN_MODULE = "com.fasterxml.jackson.module.kotlin.KotlinModule"

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
                "jackson-module-kotlin, without which Jackson reads it past its constructor",
        )

        /**
         * A codec over a fresh mapper with every module on the classpath registered.
         *
         * Unknown properties are ignored, as Spring Boot's own mapper and the other codecs
         * ignore them. The browser sends its whole store, and a type names only the signals one
         * handler accepts; Jackson 2's own default would refuse every such request.
         */
        public fun default(): Jackson2SignalsCodec =
            Jackson2SignalsCodec(
                ObjectMapper().findAndRegisterModules().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false),
            )
    }
}
