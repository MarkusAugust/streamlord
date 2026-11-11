package io.github.markusaugust.streamlord.editor

// What crosses to TypeScript is plain: objects with own properties, arrays, strings and numbers.

/** An object with each of [fields] that has a value; one without is left out, as an optional property is. */
internal fun <T> plain(vararg fields: Pair<String, Any?>): T {
    val o: dynamic = js("({})")
    for ((name, value) in fields) if (value != null) o[name] = value
    return o.unsafeCast<T>()
}

/** An object with a property for each entry, in the map's order. */
internal fun record(entries: Map<String, String>): dynamic {
    val o: dynamic = js("({})")
    for ((name, value) in entries) o[name] = value
    return o
}

/** The own properties of an object whose values are strings, in its order. */
internal fun entries(record: dynamic): Map<String, String> {
    val out = LinkedHashMap<String, String>()
    if (record == null) return out
    val names = js("Object").keys(record).unsafeCast<Array<String>>()
    for (name in names) out[name] = record[name].toString()
    return out
}

internal fun Collection<String>.plain(): Array<String> = toTypedArray()

/** An object with every one of [fields], a missing value as null, where the reader tells null from absent. */
internal fun <T> plainWithNulls(vararg fields: Pair<String, Any?>): T {
    val o: dynamic = js("({})")
    for ((name, value) in fields) o[name] = value
    return o.unsafeCast<T>()
}
