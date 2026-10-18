package io.github.markusaugust.streamlord.core.json

/**
 * RFC 7386 JSON merge patch, which is what a Datastar signal patch is.
 *
 * It lives here rather than in a test helper or an editor because it is how the protocol defines
 * the meaning of `datastar-patch-signals`: the client folds each patch into its store this way,
 * so anything asking what the browser ends up holding has to fold them the same way.
 *
 * A member set to `null` is a removal, which is how a signal is deleted. Everything else is
 * merged recursively, and a patch that is not an object replaces the target outright.
 */
public fun mergePatch(
    target: JsonValue,
    patch: JsonValue,
): JsonValue {
    if (patch !is JsonObject) return patch
    val result = LinkedHashMap<String, JsonValue>()
    if (target is JsonObject) result.putAll(target)
    for ((k, v) in patch) {
        if (v is JsonNull) result.remove(k) else result[k] = mergePatch(result[k] ?: JsonNull, v)
    }
    return JsonObject(result)
}

/**
 * Raised by [mergePatchAsClient] partway through a patch the client fails on, or one Streamlord
 * refuses. The client has by then applied the keys before the one that failed and keeps them, so
 * [store] is the store as it reads afterwards, and the next patch folds into that.
 *
 * [refused] is `false` where the client throws on the patch too, and `true` where Streamlord
 * refuses on purpose a patch it does not model, which the client may well take.
 */
public class SignalPatchException(
    message: String,
    public val store: JsonObject,
    public val refused: Boolean,
) : IllegalArgumentException(message)

/**
 * The store as the Datastar 1.0.4 client reads it after a signal patch: `mergePatch` and
 * `mergeInner` in the client's `signals.ts`, which differ from [mergePatch] in four ways.
 *
 * `onlyIfMissing` is honoured leaf by leaf, so a nested object is still walked and only the
 * values already there are kept. An object patched onto an array keeps the array and sets its
 * indices: an index past the end extends it, a key that is not an index is dropped. The client
 * walks whatever the patch is with `for ... in`, so an array at the top level sets the keys `0`,
 * `1`, ..., a string sets one key per character, and a number, a boolean or `null` patches
 * nothing. And the store never hands back `null`: a `null` inside a stored array, and the hole a
 * deleted index leaves, read as `""` and are not missing to the next `onlyIfMissing`.
 *
 * Keys are walked and kept in JavaScript's order, array indices ascending first, so `toJson()`
 * of the result holds the same keys in the same order, with the same values, as the client's
 * `JSON.stringify`, though not always the same text: a number keeps the text it came with (`2.0`,
 * `-0`, `1e2`, where `JSON.stringify` writes `2`, `0` and `100`), and the built-in writer escapes
 * some characters it writes as they are, U+2028 and U+2029 among them. A key `length` on an array resizes it, as the
 * client's store does: the value is turned into a number the way JavaScript does it (`[]`,
 * `false` and `""` are 0, `"2"` and `[2]` are 2), shorter drops the tail and longer adds `""`;
 * one that does not come out a whole number of zero or more throws, as the browser's
 * `RangeError` does. This is the store as it reads once a binding has touched it: the client
 * fills a hole with `""` on the first read, so a hole is missing to `onlyIfMissing` only until then.
 *
 * A patch that throws, here or in the client, has its earlier keys applied: the exception is a
 * [SignalPatchException] that carries the store as the client keeps it. Two kinds of patch are
 * refused on purpose, with [SignalPatchException.refused] set, though the client may take them.
 * More than [holeLimit] indices past the end of an array, which would fill memory with the holes
 * the client never allocates. And a key named `__proto__` anywhere in the patch, at any depth and
 * inside array values too, whatever it holds. The client gives the object holding it a foreign
 * prototype, after which it throws on some later keys, replaces the object on a later patch, or,
 * with a second `__proto__` under the first, writes into `String.prototype`; only a `null` in a
 * patch object changes nothing there, and it is refused as well, so that one rule holds. The
 * store in the exception is the patch cut before the key that holds the `__proto__`, at the level
 * where it stands, or before the key whose value is the array it is inside: the store the client
 * keeps where it throws on that key. Where the client does not throw, Streamlord refuses on
 * purpose rather than model a store the client no longer holds as plain JSON. A store passed in
 * with a key `__proto__`, which no fold leaves and the client never holds, is refused the same
 * way once a patch reaches the object that has it.
 *
 * Each fold copies, in full width, every object and array on the path the patch goes through,
 * from the top down, and hands the rest of the result back as the very same values. Folding a
 * small patch into a large store therefore costs time linear in the width of the levels it goes
 * through, not in the size of the store.
 */
public fun mergePatchAsClient(
    store: JsonObject,
    patch: JsonValue,
    ifMissing: Boolean = false,
    holeLimit: Int = 10_000,
): JsonObject {
    val keys: Map<String, JsonValue> =
        when (patch) {
            is JsonObject -> patch
            is JsonArray -> patch.withIndex().associate { (i, v) -> i.toString() to v }
            is JsonString -> patch.value.withIndex().associate { (i, c) -> i.toString() to JsonString(c.toString()) }
            else -> return inJsOrderDeep(store) as JsonObject
        }
    val root = JsObject(LinkedHashMap<String, Any>(store))
    try {
        if (PROTO in store) throw Refusal(STORE_PROTO_REFUSED)
        ClientMerge(ifMissing, holeLimit).fold(keys, root)
    } catch (e: IllegalArgumentException) {
        val message = e.message ?: "The client cannot take this signal patch"
        throw SignalPatchException(message, freeze(root) as JsonObject, refused = e is Refusal)
    }
    return freeze(root) as JsonObject
}

// The store while a patch folds into it: the objects and arrays the patch reaches, opened one
// level at a time, and below them the JsonValues it does not reach, left as they are.
private class JsObject(
    val fields: LinkedHashMap<String, Any> = LinkedHashMap(),
)

private class JsArray(
    val items: ArrayList<Any> = ArrayList(),
)

/** [value] as a node a patch can change, opened one level deep, or `null` for one that holds no keys. */
private fun open(value: Any?): Any? =
    when (value) {
        is JsObject, is JsArray -> value
        is JsonObject -> if (PROTO in value) throw Refusal(STORE_PROTO_REFUSED) else JsObject(LinkedHashMap<String, Any>(value))
        is JsonArray -> JsArray(ArrayList<Any>(value))
        else -> null
    }

// In place, since a node is frozen once, as the fold ends.
private fun freeze(value: Any): JsonValue =
    when (value) {
        is JsArray -> {
            value.items.replaceAll(::freeze)
            @Suppress("UNCHECKED_CAST")
            JsonArray(value.items as List<JsonValue>).also { it.jsOrdered = true }
        }

        is JsObject -> {
            value.fields.replaceAll { _, v -> freeze(v) }
            @Suppress("UNCHECKED_CAST")
            JsonObject(inJsOrder(value.fields as Map<String, JsonValue>)).also { it.jsOrdered = true }
        }

        else -> {
            inJsOrderDeep(value as JsonValue)
        }
    }

/** [value] with every object in it in JavaScript's key order: the same instance where it already is. */
private fun inJsOrderDeep(value: JsonValue): JsonValue =
    when {
        value is JsonObject && !value.jsOrdered -> {
            val fields = value.mapValues { inJsOrderDeep(it.value) }
            val ordered = inJsOrder(fields)
            val same = ordered.keys.toList() == value.keys.toList() && fields.all { (k, v) -> v === value[k] }
            (if (same) value else JsonObject(ordered)).also { it.jsOrdered = true }
        }

        value is JsonArray && !value.jsOrdered -> {
            val items = value.map(::inJsOrderDeep)
            val same = items.indices.all { items[it] === value[it] }
            (if (same) value else JsonArray(items)).also { it.jsOrdered = true }
        }

        else -> {
            value
        }
    }

/** `mergePatch` and `mergeInner` in signals.ts, on the store as [open] lays it out. */
private class ClientMerge(
    private val ifMissing: Boolean,
    private val holeLimit: Int,
) {
    fun fold(
        patch: Map<String, JsonValue>,
        node: Any,
    ) {
        for ((key, value) in inJsOrder(patch)) {
            if (key == PROTO) throw Refusal(PROTO_REFUSED)
            if (value is JsonNull) {
                if (!ifMissing) delete(node, key)
            } else {
                mergeInner(value, key, node)
            }
        }
    }

    private fun mergeInner(
        patch: JsonValue,
        target: String,
        parent: Any,
    ) {
        if (patch is JsonObject) {
            val current = get(parent, target)
            val node = open(current) ?: JsObject()
            if (node !== current) set(parent, target, node)
            fold(patch, node)
        } else {
            if (holdsProto(patch)) throw Refusal(PROTO_REFUSED)
            if (!(ifMissing && has(parent, target))) set(parent, target, asRead(patch))
        }
    }

    private fun has(
        parent: Any,
        key: String,
    ): Boolean =
        when (parent) {
            is JsArray -> key == "length" || (arrayIndex(key)?.let { it < parent.items.size } ?: false)
            else -> key in (parent as JsObject).fields
        }

    private fun get(
        parent: Any,
        key: String,
    ): Any? =
        when (parent) {
            is JsArray -> arrayIndex(key)?.let { parent.items.getOrNull(it.toInt()) }
            else -> (parent as JsObject).fields[key]
        }

    private fun set(
        parent: Any,
        key: String,
        value: Any,
    ) {
        if (parent !is JsArray) {
            (parent as JsObject).fields[key] = value
            return
        }
        val items = parent.items
        if (key == "length") {
            val length = arrayLength(value)
            require(length != null) { "Invalid array length ${freeze(value).toJson()} in a signal patch" }
            if (length > items.size + holeLimit) {
                throw Refusal("Signal patch length $length is more than $holeLimit past the end of an array of ${items.size}$HOLES")
            }
            while (items.size > length) items.removeAt(items.size - 1)
            while (items.size < length) items.add(EMPTY)
            return
        }
        // A key that is not an index is a property JSON.stringify leaves out.
        val index = arrayIndex(key) ?: return
        if (index > items.size + holeLimit) {
            throw Refusal("Signal patch index $index is more than $holeLimit past the end of an array of ${items.size}$HOLES")
        }
        while (items.size <= index) items.add(EMPTY)
        items[index.toInt()] = value
    }

    // `delete` leaves a hole, read as ""; deleting an array's length is a TypeError in the client's strict-mode code.
    private fun delete(
        parent: Any,
        key: String,
    ) {
        require(!(parent is JsArray && key == "length")) { "The client cannot delete the length of an array" }
        when (parent) {
            is JsArray -> arrayIndex(key)?.takeIf { it < parent.items.size }?.let { parent.items[it.toInt()] = EMPTY }
            else -> (parent as JsObject).fields.remove(key)
        }
    }
}

/** The order JavaScript keeps and `JSON.stringify` writes: array indices ascending, then the rest as they were set. */
private fun <V> inJsOrder(map: Map<String, V>): Map<String, V> {
    val indices = map.keys.filter { arrayIndex(it) != null }
    if (indices.isEmpty()) return map
    val out = LinkedHashMap<String, V>(map.size)
    for (key in indices.sortedBy { it.toLong() }) out[key] = map.getValue(key)
    for ((key, value) in map) if (arrayIndex(key) == null) out[key] = value
    return out
}

private fun arrayIndex(key: String): Long? = key.toLongOrNull()?.takeIf { it >= 0 && it < 4294967295L && it.toString() == key }

/** What `array.length = value` takes [value] for, or `null` where the browser throws a `RangeError`. */
private fun arrayLength(value: Any): Long? {
    val number = jsNumber(value)
    return if (number >= 0 && number <= 4294967295.0 && number == Math.floor(number)) number.toLong() else null
}

/** JavaScript's `Number(value)`, for a value of the store. */
private fun jsNumber(value: Any): Double =
    when (value) {
        is JsonNumber -> {
            value.toDouble()
        }

        is JsonBoolean -> {
            if (value.value) 1.0 else 0.0
        }

        is JsonString -> {
            JsText.toNumber(value.value)
        }

        is JsonNull -> {
            0.0
        }

        // An array is read through its text, the items joined by commas: one item is that item's text.
        is JsonArray -> {
            when (value.size) {
                0 -> 0.0
                1 -> value[0].let { if (it is JsonBoolean || it is JsonObject) Double.NaN else jsNumber(it) }
                else -> Double.NaN
            }
        }

        else -> {
            Double.NaN
        }
    }

private val EMPTY = JsonString("")

/** A patch Streamlord refuses on purpose, which the client may take. */
private class Refusal(
    message: String,
) : IllegalArgumentException(message)

private const val PROTO = "__proto__"

/** Why a patch with a key `__proto__` is refused; the VS Code fold in sse.ts gives the same text. */
internal const val PROTO_REFUSED =
    "A key named __proto__ gives the object holding it a foreign prototype in Datastar's client, " +
        "which then throws on some later keys, replaces the object on a later patch, or with a second " +
        "__proto__ writes into String.prototype; Streamlord does not model it and refuses the patch"

internal const val STORE_PROTO_REFUSED =
    "The store holds a key named __proto__, which Datastar's client never holds; " +
        "Streamlord does not fold a patch into the object that has it"

private const val HOLES = ": the client takes it, and Streamlord does not fill memory with the holes"

/** Whether a key `__proto__` stands anywhere in [value]. */
private fun holdsProto(value: JsonValue): Boolean =
    when (value) {
        is JsonObject -> PROTO in value || value.values.any(::holdsProto)
        is JsonArray -> value.any(::holdsProto)
        else -> false
    }

/** A value as the client's store reads it back: every `null` inside it is `""`. */
private fun asRead(value: JsonValue): JsonValue =
    when (value) {
        is JsonNull -> {
            EMPTY
        }

        is JsonArray -> {
            JsonArray(value.map(::asRead))
        }

        is JsonObject -> {
            JsonObject(value.mapValues { asRead(it.value) })
        }

        else -> {
            value
        }
    }
