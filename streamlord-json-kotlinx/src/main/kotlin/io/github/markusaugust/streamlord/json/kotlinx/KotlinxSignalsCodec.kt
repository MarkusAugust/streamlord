package io.github.markusaugust.streamlord.json.kotlinx

import io.github.markusaugust.streamlord.core.SignalsCodecException
import io.github.markusaugust.streamlord.core.port.driven.SignalsCodec
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import kotlin.reflect.KType

/**
 * A [SignalsCodec] backed by kotlinx.serialization.
 *
 * The default [Json] is tuned for signals: unknown keys are ignored (the browser sends its whole
 * store), defaults are encoded (a default is still a value the browser must learn) and nulls are
 * explicit (a `null` is how a signal is removed).
 *
 * ```kotlin
 * val streamlord = Streamlord(codec = KotlinxSignalsCodec())
 * ```
 */
public class KotlinxSignalsCodec(private val json: Json = DefaultJson) : SignalsCodec {

    override fun encode(value: Any?, type: KType): String = try {
        json.encodeToString(json.serializersModule.serializer(type), value)
    } catch (e: SerializationException) {
        throw SignalsCodecException("Could not encode $type as signals", e)
    }

    override fun <T : Any> decode(json: String, type: KType): T = try {
        @Suppress("UNCHECKED_CAST")
        this.json.decodeFromString(this.json.serializersModule.serializer(type), json) as T
    } catch (e: SerializationException) {
        throw SignalsCodecException("Could not decode signals into $type", e)
    } catch (e: IllegalArgumentException) {
        throw SignalsCodecException("Could not decode signals into $type", e)
    }

    public companion object {
        /** The configuration used when none is given. */
        public val DefaultJson: Json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = true
            isLenient = false
        }
    }
}
