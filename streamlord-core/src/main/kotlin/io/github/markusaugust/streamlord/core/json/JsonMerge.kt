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
 * More than [holeLimit] indices past the end of an array throws [IllegalArgumentException]
 * rather than fill memory with the holes the client never allocates, and so does a key named
 * `__proto__`, on which the client itself fails.
 */
public fun mergePatchAsClient(
    store: JsonObject,
    patch: JsonValue,
    ifMissing: Boolean = false,
    holeLimit: Int = 10_000,
): JsonObject =
    when (patch) {
        is JsonObject -> {
            foldObject(store, patch, ifMissing, holeLimit)
        }

        is JsonArray -> {
            foldObject(store, JsonObject(patch.withIndex().associate { (i, v) -> i.toString() to v }), ifMissing, holeLimit)
        }

        is JsonString -> {
            foldObject(
                store,
                JsonObject(
                    patch.value.withIndex().associate { (i, c) ->
                        i.toString() to JsonString(c.toString())
                    },
                ),
                ifMissing,
                holeLimit,
            )
        }

        else -> {
            store
        }
    }

private fun foldObject(
    target: JsonObject,
    patch: JsonObject,
    ifMissing: Boolean,
    holeLimit: Int,
): JsonObject {
    val out = LinkedHashMap<String, JsonValue>(target)
    for ((key, value) in patch) {
        require(key != "__proto__") { "The client cannot hold a signal named __proto__" }
        val existing = out[key]
        when {
            value is JsonNull -> if (!ifMissing) out.remove(key)
            value is JsonObject && existing is JsonArray -> out[key] = foldIntoArray(existing, value, ifMissing, holeLimit)
            value is JsonObject -> out[key] = foldObject(existing as? JsonObject ?: JsonObject.EMPTY, value, ifMissing, holeLimit)
            !(ifMissing && key in out) -> out[key] = asRead(value)
        }
    }
    return JsonObject(inJsOrder(out))
}

/** The order JavaScript keeps and `JSON.stringify` writes: array indices ascending, then the rest as they were set. */
private fun inJsOrder(map: Map<String, JsonValue>): Map<String, JsonValue> {
    val indices = map.keys.filter { isArrayIndex(it) }
    if (indices.isEmpty()) return map
    val out = LinkedHashMap<String, JsonValue>(map.size)
    for (key in indices.sortedBy { it.toLong() }) out[key] = map.getValue(key)
    for ((key, value) in map) if (!isArrayIndex(key)) out[key] = value
    return out
}

private fun isArrayIndex(key: String): Boolean = key.toLongOrNull()?.let { it >= 0 && it < 4294967295L && it.toString() == key } ?: false

private fun foldIntoArray(
    target: JsonArray,
    patch: JsonObject,
    ifMissing: Boolean,
    holeLimit: Int,
): JsonValue {
    val items = target.toMutableList()
    for ((key, value) in patch) {
        val index = key.toIntOrNull()?.takeIf { it >= 0 && it.toString() == key } ?: continue
        require(
            index <= items.size + holeLimit,
        ) { "Signal patch index $index is more than $holeLimit past the end of an array of ${items.size}" }
        when {
            value is JsonNull -> if (!ifMissing && index < items.size) items[index] = EMPTY
            value is JsonObject -> items.put(index, foldInto(items.getOrNull(index), value, ifMissing, holeLimit))
            !(ifMissing && index < items.size) -> items.put(index, asRead(value))
        }
    }
    return JsonArray(items)
}

private fun foldInto(
    existing: JsonValue?,
    patch: JsonObject,
    ifMissing: Boolean,
    holeLimit: Int,
): JsonValue =
    when (existing) {
        is JsonArray -> foldIntoArray(existing, patch, ifMissing, holeLimit)
        is JsonObject -> foldObject(existing, patch, ifMissing, holeLimit)
        else -> foldObject(JsonObject.EMPTY, patch, ifMissing, holeLimit)
    }

private val EMPTY = JsonString("")

/** A value as the client's store reads it back: every `null` inside it is `""`. */
private fun asRead(value: JsonValue): JsonValue =
    when (value) {
        is JsonNull -> EMPTY
        is JsonArray -> JsonArray(value.map(::asRead))
        is JsonObject -> JsonObject(value.mapValues { asRead(it.value) })
        else -> value
    }

private fun MutableList<JsonValue>.put(
    index: Int,
    value: JsonValue,
) {
    while (size <= index) add(EMPTY)
    this[index] = value
}
