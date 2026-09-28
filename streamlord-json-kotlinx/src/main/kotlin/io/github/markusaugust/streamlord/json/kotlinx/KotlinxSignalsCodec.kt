package io.github.markusaugust.streamlord.json.kotlinx

import io.github.markusaugust.streamlord.core.SignalsCodecException
import io.github.markusaugust.streamlord.core.port.driven.SignalsCodec
import kotlinx.serialization.KSerializer
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
 *
 * In a GraalVM native image, name the serializers instead:
 *
 * ```kotlin
 * val streamlord = Streamlord(
 *     codec = KotlinxSignalsCodec(
 *         serializers = mapOf(typeOf<Signals>() to Signals.serializer()),
 *     ),
 * )
 * ```
 *
 * @param json the configuration to encode and decode with.
 * @param serializers serializers by the type they handle, consulted before the reflective lookup.
 */
public class KotlinxSignalsCodec(
    private val json: Json = DefaultJson,
    private val serializers: Map<KType, KSerializer<*>> = emptyMap(),
) : SignalsCodec {
    override fun encode(
        value: Any?,
        type: KType,
    ): String =
        try {
            json.encodeToString(serializerFor(type), value)
        } catch (e: SerializationException) {
            throw SignalsCodecException("Could not encode $type as signals", e)
        }

    override fun <T : Any> decode(
        json: String,
        type: KType,
    ): T =
        try {
            @Suppress("UNCHECKED_CAST")
            this.json.decodeFromString(serializerFor(type), json) as T
        } catch (e: SerializationException) {
            throw SignalsCodecException("Could not decode signals into $type", e)
        } catch (e: IllegalArgumentException) {
            throw SignalsCodecException("Could not decode signals into $type", e)
        }

    /*
     * The one given for this type, or the one kotlinx.serialization finds by reading the class.
     *
     * The reflective lookup is the convenient default and the reason this module exists: a KType
     * arrives from the call site and kotlinx answers it with getDeclaredField("Companion") and
     * serializer() on the result, with kotlin-reflect reading the Kotlin metadata on the way.
     *
     * None of that is in the bytecode, so a GraalVM native image drops it and the call fails at
     * runtime with "Unresolved class: class Signals (kind = CLASS)" — an application that starts
     * and then cannot read a single signal. A serializer named here is the plugin-generated one,
     * resolved at compile time and plainly visible to the image, which is what makes this module
     * usable in a native image without hand-written reflection metadata.
     *
     * Map lookup and nothing more: KType equality compares the classifier and the arguments, and
     * reads no metadata to do it.
     */
    @Suppress("UNCHECKED_CAST")
    private fun serializerFor(type: KType): KSerializer<Any?> =
        (serializers[type] ?: json.serializersModule.serializer(type)) as KSerializer<Any?>

    public companion object {
        /** The configuration used when none is given. */
        public val DefaultJson: Json =
            Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
                explicitNulls = true
                isLenient = false
            }
    }
}
