package io.github.markusaugust.streamlord.core.json

import java.math.BigDecimal

/**
 * A JSON value as the built-in parser sees it. Immutable and dependency-free.
 *
 * This is the shape incoming signals take when no codec adapter is configured: a faithful,
 * lossless tree you can walk with [JsonObject.string], [JsonObject.int] and friends, or turn
 * into plain Kotlin collections with [toKotlin].
 */
public sealed interface JsonValue {
    /** Convert to plain Kotlin: `Map`, `List`, `String`, `Long`/`Double`/`BigDecimal`, `Boolean` or `null`. */
    public fun toKotlin(): Any?

    /** Render as compact JSON text. */
    public fun toJson(): String = JsonWriter.write(this)
}

/**
 * A JSON object. Also a read-only [Map], so everything you know about maps applies.
 *
 * The typed accessors return `null` when the key is absent, is JSON `null`, or holds a value of
 * a different type. Signals from the browser are untrusted input; nothing here throws on shape.
 */
public class JsonObject(fields: Map<String, JsonValue>) : JsonValue, Map<String, JsonValue> {
    private val fields: Map<String, JsonValue> = LinkedHashMap(fields)

    override val entries: Set<Map.Entry<String, JsonValue>> get() = fields.entries
    override val keys: Set<String> get() = fields.keys
    override val size: Int get() = fields.size
    override val values: Collection<JsonValue> get() = fields.values
    override fun isEmpty(): Boolean = fields.isEmpty()
    override fun get(key: String): JsonValue? = fields[key]
    override fun containsValue(value: JsonValue): Boolean = fields.containsValue(value)
    override fun containsKey(key: String): Boolean = fields.containsKey(key)

    public fun string(key: String): String? = (this[key] as? JsonString)?.value
    public fun boolean(key: String): Boolean? = (this[key] as? JsonBoolean)?.value
    public fun int(key: String): Int? = (this[key] as? JsonNumber)?.toIntOrNull()
    public fun long(key: String): Long? = (this[key] as? JsonNumber)?.toLongOrNull()
    public fun double(key: String): Double? = (this[key] as? JsonNumber)?.toDouble()
    public fun decimal(key: String): BigDecimal? = (this[key] as? JsonNumber)?.toBigDecimalOrNull()
    public fun obj(key: String): JsonObject? = this[key] as? JsonObject
    public fun array(key: String): JsonArray? = this[key] as? JsonArray

    /** `true` if the key exists, even when its value is JSON `null`. */
    public fun has(key: String): Boolean = containsKey(key)

    /** Walk a nested path such as `path("user", "address", "city")`. */
    public fun path(vararg keys: String): JsonValue? {
        var current: JsonValue? = this
        for (key in keys) {
            current = (current as? JsonObject)?.get(key) ?: return null
        }
        return current
    }

    override fun toKotlin(): Map<String, Any?> = fields.mapValues { it.value.toKotlin() }
    override fun equals(other: Any?): Boolean = other is JsonObject && other.fields == fields
    override fun hashCode(): Int = fields.hashCode()
    override fun toString(): String = toJson()

    public companion object {
        /** The empty object, returned when a request carries no signals at all. */
        public val EMPTY: JsonObject = JsonObject(emptyMap())
    }
}

/** A JSON array. Also a read-only [List]. */
public class JsonArray(items: List<JsonValue>) : JsonValue, List<JsonValue> by items.toList() {
    private val items: List<JsonValue> = items.toList()

    public fun strings(): List<String> = mapNotNull { (it as? JsonString)?.value }
    public fun objects(): List<JsonObject> = filterIsInstance<JsonObject>()

    override fun toKotlin(): List<Any?> = items.map { it.toKotlin() }
    override fun equals(other: Any?): Boolean = other is JsonArray && other.items == items
    override fun hashCode(): Int = items.hashCode()
    override fun toString(): String = toJson()
}

public data class JsonString(val value: String) : JsonValue {
    override fun toKotlin(): String = value
}

/**
 * A JSON number kept as its original text, so nothing is lost between the browser and you.
 * Ask for the width you need. The grammar is validated by the parser; the accessors never throw
 * on a value that merely does not fit, they return `null` (or `Infinity` for [toDouble]).
 */
public data class JsonNumber(val text: String) : JsonValue {
    public fun toIntOrNull(): Int? = toLongOrNull()?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
    public fun toLongOrNull(): Long? = text.toLongOrNull() ?: runCatching { BigDecimal(text).longValueExact() }.getOrNull()
    public fun toDouble(): Double = text.toDouble()
    public fun toBigDecimalOrNull(): BigDecimal? = runCatching { BigDecimal(text) }.getOrNull()
    public fun toBigDecimal(): BigDecimal = toBigDecimalOrNull() ?: throw ArithmeticException("Number '$text' is outside BigDecimal range")

    /** A `Long` when the number is integral and fits, otherwise a `Double`. */
    override fun toKotlin(): Number = toLongOrNull() ?: toDouble()
}

public data class JsonBoolean(val value: Boolean) : JsonValue {
    override fun toKotlin(): Boolean = value

    public companion object {
        public val TRUE: JsonBoolean = JsonBoolean(true)
        public val FALSE: JsonBoolean = JsonBoolean(false)
    }
}

public object JsonNull : JsonValue {
    override fun toKotlin(): Nothing? = null
    override fun toString(): String = "null"
}
