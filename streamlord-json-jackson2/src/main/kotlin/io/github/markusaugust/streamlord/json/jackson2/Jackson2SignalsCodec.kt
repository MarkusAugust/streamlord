package io.github.markusaugust.streamlord.json.jackson2

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import io.github.markusaugust.streamlord.core.SignalsCodecException
import io.github.markusaugust.streamlord.core.port.driven.SignalsCodec
import kotlin.reflect.KType
import kotlin.reflect.javaType

/**
 * A [SignalsCodec] backed by Jackson 2 (`com.fasterxml.jackson`).
 *
 * Hand it the `ObjectMapper` your application already owns (in Spring Boot, the auto-configured
 * bean) so signals obey the same rules as every other JSON in the realm. [default] builds a
 * mapper with every module on the classpath registered, including `jackson-module-kotlin`
 * when present.
 *
 * ```kotlin
 * @Bean fun streamlord(mapper: ObjectMapper) = Streamlord(codec = Jackson2SignalsCodec(mapper))
 * ```
 */
public class Jackson2SignalsCodec(private val mapper: ObjectMapper) : SignalsCodec {

    override fun encode(value: Any?, type: KType): String = try {
        mapper.writeValueAsString(value)
    } catch (e: JsonProcessingException) {
        throw SignalsCodecException("Could not encode $type as signals", e)
    }

    @OptIn(ExperimentalStdlibApi::class)
    override fun <T : Any> decode(json: String, type: KType): T = try {
        val javaType = mapper.typeFactory.constructType(type.javaType)
        mapper.readValue<T>(json, javaType) ?: throw SignalsCodecException("Signals decoded to null for $type")
    } catch (e: JsonProcessingException) {
        throw SignalsCodecException("Could not decode signals into $type", e)
    }

    public companion object {
        /**
         * A codec over a fresh mapper with every module on the classpath registered.
         *
         * Unknown properties are ignored, as Spring Boot's own mapper and the other codecs
         * ignore them. The browser sends its whole store, and a type names only the signals one
         * handler accepts; Jackson 2's own default would refuse every such request.
         */
        public fun default(): Jackson2SignalsCodec = Jackson2SignalsCodec(
            ObjectMapper().findAndRegisterModules().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false),
        )
    }
}
