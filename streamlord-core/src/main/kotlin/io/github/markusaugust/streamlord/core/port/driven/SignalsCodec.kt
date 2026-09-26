package io.github.markusaugust.streamlord.core.port.driven

import io.github.markusaugust.streamlord.core.SignalsCodecException
import io.github.markusaugust.streamlord.core.json.JsonObject
import io.github.markusaugust.streamlord.core.json.JsonParser
import io.github.markusaugust.streamlord.core.json.JsonValue
import io.github.markusaugust.streamlord.core.json.JsonWriter
import kotlin.reflect.KType
import kotlin.reflect.typeOf

/**
 * Driven port: how signals become JSON and JSON becomes signals.
 *
 * Streamlord owns no JSON library. The [BuiltInSignalsCodec] handles maps, lists and
 * primitives without one; typed data classes need an adapter (`streamlord-json-kotlinx`
 * or `streamlord-json-jackson`), or your own implementation of this interface.
 *
 * Implementations must be thread-safe: one codec serves every request.
 */
public interface SignalsCodec {
    /** Encode [value] (whose static type is [type]) as a JSON object text. */
    public fun encode(value: Any?, type: KType): String

    /** Decode a JSON object text into [type]. Must throw [SignalsCodecException] on failure. */
    public fun <T : Any> decode(json: String, type: KType): T
}

/** Encode with the static type inferred at the call site. */
public inline fun <reified T> SignalsCodec.encode(value: T): String = encode(value, typeOf<T>())

/** Decode with the target type inferred at the call site. */
public inline fun <reified T : Any> SignalsCodec.decode(json: String): T = decode(json, typeOf<T>())

/**
 * The codec that ships inside the core: dependency-free, honest about its limits.
 *
 * Encodes anything [JsonWriter] understands. Decodes only into [JsonValue], [JsonObject],
 * `Map<String, Any?>` or `String` (the raw text). Ask it for a data class and it will tell
 * you, in plain words, which adapter to add.
 */
public object BuiltInSignalsCodec : SignalsCodec {
    override fun encode(value: Any?, type: KType): String = JsonWriter.write(value)

    override fun <T : Any> decode(json: String, type: KType): T {
        val result: Any = when (type.classifier) {
            String::class -> json
            JsonValue::class -> JsonParser.parse(json)
            JsonObject::class -> JsonParser.parseObject(json)
            Map::class -> JsonParser.parseObject(json).toKotlin()
            else -> throw SignalsCodecException(
                "BuiltInSignalsCodec cannot decode into $type. " +
                    "Add streamlord-json-kotlinx or streamlord-json-jackson and configure Streamlord(codec = ...).",
            )
        }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }
}
