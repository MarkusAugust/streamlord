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
 * Pass `strict = true` with them and a type that was left out fails here, on the JVM, with the
 * name of the class — rather than in the image, on the first request, as a decode that cannot
 * find a serializer it was never given.
 *
 * @param json the configuration to encode and decode with.
 * @param serializers serializers by the type they handle, consulted before the reflective lookup.
 * @param strict refuse the reflective lookup, so a type missing from [serializers] fails at once.
 */
public class KotlinxSignalsCodec(
    private val json: Json = DefaultJson,
    private val serializers: Map<KType, KSerializer<*>> = emptyMap(),
    private val strict: Boolean = false,
) : SignalsCodec {
    override fun encode(
        value: Any?,
        type: KType,
    ): String =
        try {
            json.encodeToString(serializerFor(type), value)
        } catch (e: SerializationException) {
            throw SignalsCodecException("Could not encode $type as signals.${hint(type)}", e)
        }

    override fun <T : Any> decode(
        json: String,
        type: KType,
    ): T =
        try {
            @Suppress("UNCHECKED_CAST")
            this.json.decodeFromString(serializerFor(type), json) as T
        } catch (e: SerializationException) {
            throw SignalsCodecException("Could not decode signals into $type.${hint(type)}", e)
        } catch (e: IllegalArgumentException) {
            throw SignalsCodecException("Could not decode signals into $type.${hint(type)}", e)
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

    /**
     * What to add to a failure for a type nobody named.
     *
     * The reflective lookup is the likeliest thing to have failed, and it is the one that fails
     * differently in different places: it works on a JVM and cannot work inside a GraalVM native
     * image, where the class metadata it reads is not there. A message that says only "could not
     * decode" sends the reader looking at their JSON. This one sends them to the fix.
     */
    private fun hint(type: KType): String =
        if (serializers.containsKey(type)) {
            ""
        } else {
            " No serializer was named for it: pass typeOf<$type>() to " +
                "KotlinxSignalsCodec(serializers = ...) if this is a GraalVM native image."
        }

    @Suppress("UNCHECKED_CAST")
    private fun serializerFor(type: KType): KSerializer<Any?> {
        serializers[type]?.let { return it as KSerializer<Any?> }

        /*
         * The point of strict mode.
         *
         * An application that names its serializers for a native image has one list to keep in
         * step with its routes, and nothing notices when a type is added to the second and not
         * the first — the JVM resolves it reflectively and says nothing, and the image fails on
         * the first request that carries it. This is that silence, broken, where a test can hear
         * it: this project's own service shipped exactly that bug, twice, before this existed.
         */
        if (strict) {
            throw SignalsCodecException(
                "No serializer was named for $type, and this codec is strict. Add " +
                    "typeOf<$type>() to KotlinxSignalsCodec(serializers = ...) — a reflective " +
                    "lookup would work here and fail in a GraalVM native image.",
            )
        }

        return json.serializersModule.serializer(type)
    }

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
