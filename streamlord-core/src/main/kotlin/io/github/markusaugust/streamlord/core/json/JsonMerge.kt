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
