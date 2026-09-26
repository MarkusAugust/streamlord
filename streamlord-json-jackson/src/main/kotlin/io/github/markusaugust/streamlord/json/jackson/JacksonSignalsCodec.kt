package io.github.markusaugust.streamlord.json.jackson

import io.github.markusaugust.streamlord.core.SignalsCodecException
import io.github.markusaugust.streamlord.core.port.driven.SignalsCodec
import tools.jackson.core.JacksonException
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.json.JsonMapper
import kotlin.reflect.KType
import kotlin.reflect.javaType

/**
 * A [SignalsCodec] backed by Jackson 3 (`tools.jackson`).
 *
 * Hand it the `ObjectMapper` your application already owns (in Spring Boot, the auto-configured
 * bean) so signals obey the same rules as every other JSON in the realm. [default] builds a
 * mapper that discovers registered modules, including `jackson-module-kotlin` when present.
 *
 * ```kotlin
 * val streamlord = Streamlord(codec = JacksonSignalsCodec(objectMapper))
 * ```
 */
public class JacksonSignalsCodec(private val mapper: ObjectMapper) : SignalsCodec {

    override fun encode(value: Any?, type: KType): String = try {
        mapper.writeValueAsString(value)
    } catch (e: JacksonException) {
        throw SignalsCodecException("Could not encode $type as signals", e)
    }

    @OptIn(ExperimentalStdlibApi::class)
    override fun <T : Any> decode(json: String, type: KType): T = try {
        val javaType = mapper.constructType(type.javaType)
        mapper.readValue<T>(json, javaType) ?: throw SignalsCodecException("Signals decoded to null for $type")
    } catch (e: JacksonException) {
        throw SignalsCodecException("Could not decode signals into $type", e)
    }

    public companion object {
        /** A codec over a fresh mapper with every module on the classpath registered. */
        public fun default(): JacksonSignalsCodec =
            JacksonSignalsCodec(JsonMapper.builder().findAndAddModules().build())
    }
}
