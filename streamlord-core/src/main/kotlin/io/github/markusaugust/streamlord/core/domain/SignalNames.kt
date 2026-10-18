package io.github.markusaugust.streamlord.core.domain

import io.github.markusaugust.streamlord.core.DatastarEventValidationException

/**
 * The signal names Datastar's client cannot hold, measured against its 1.0.4 store.
 *
 * - `__proto__` is never stored. With some objects under it, `{"x": 1}` among them, the merge
 *   throws halfway through a batch it never closes, and the bindings already on the page stop
 *   redrawing until it is reloaded; `{"__proto__": {"y": 1}}` under it sets `y` on
 *   `String.prototype`.
 * - A name every object already has, such as `constructor` or `toString`, is fine with a plain
 *   value under it. With an object under it, the client's `pathToObj`, which runs before every
 *   request that sends signals, writes into the built-in one: `{"constructor": {"prototype":
 *   {"x": 1}}}` sets `x` on `Object.prototype`, `{"toString": {"name": 1}}` makes every later
 *   request on the page fail, and the signal is dropped from requests.
 * - A key holding `.` is stored as one key, which `$a.b` does not reach, and outside a list the
 *   client loses its value when it sends the signals with a request, because it splits the key
 *   at the dot. It is refused inside a list too, so that one rule holds everywhere.
 *
 * A plain value or a list under `constructor` arrives as sent; what the page then writes under it
 * is the page's own doing. A name written in code, a path such as `data-signals:a.b` takes, may
 * carry an object under it at any part, so none of its parts may be one of these.
 */
public object SignalNames {
    /** The properties of `Object.prototype` in a browser: every object already has them. */
    internal val PROTOTYPE_NAMES =
        setOf(
            "__proto__",
            "constructor",
            "hasOwnProperty",
            "isPrototypeOf",
            "propertyIsEnumerable",
            "toLocaleString",
            "toString",
            "valueOf",
            "__defineGetter__",
            "__defineSetter__",
            "__lookupGetter__",
            "__lookupSetter__",
        )

    /**
     * Why the client cannot hold [key] as one key of a signal object, or `null` when it can.
     * [objectUnder] says whether the value under it is an object, not an array or a plain value.
     */
    public fun keyProblem(
        key: String,
        objectUnder: Boolean,
    ): String? =
        when {
            key == "__proto__" -> {
                "the client never stores __proto__: with some objects under it its merge throws and the page's " +
                    "bindings stop redrawing, and a second __proto__ under it writes into String.prototype"
            }

            objectUnder && key in PROTOTYPE_NAMES -> {
                "every object already has $key, so the client writes the object under it into the built-in one and drops the signal"
            }

            '.' in key -> {
                "the client stores a key holding '.' as one key, which \$a.b does not reach"
            }

            else -> {
                null
            }
        }

    /** Why the client cannot hold [path], written with dots as `data-signals:a.b` writes it, or `null` when it can. */
    public fun pathProblem(path: String): String? {
        val parts = path.split('.')
        if (parts.any { it.isEmpty() }) return "has an empty part, which no \$ reference reaches"
        return parts.firstOrNull { it in PROTOTYPE_NAMES }?.let {
            "has the part $it, which every object already has, so the client cannot hold a signal there"
        }
    }

    /**
     * The pairs as one signal object, with a dotted name nested the way `data-signals:a.b` nests
     * it: `"form.email" to "x"` and `"form.name" to "y"` give `{form: {email: "x", name: "y"}}`.
     * Maps under one name merge, key by key. A name given two values, or a value and signals
     * under it, is handed to [clash] with the reason, which by default throws
     * [DatastarEventValidationException]. The names themselves are not checked; hold each to
     * [pathProblem] first.
     */
    public fun nest(
        pairs: Iterable<Pair<String, Any?>>,
        clash: (name: String, reason: String) -> Nothing = { name, reason ->
            throw DatastarEventValidationException("signal $name $reason")
        },
    ): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        for ((name, value) in pairs) {
            val parts = name.split('.')
            var node = out
            for ((i, part) in parts.withIndex()) {
                val at = parts.subList(0, i + 1).joinToString(".")
                if (i == parts.lastIndex) {
                    node[part] = if (part in node) merged(node[part], value, at, clash) else value
                } else {
                    val existing = node[part]
                    val next =
                        when {
                            part !in node -> LinkedHashMap()
                            existing is Map<*, *> -> copy(existing)
                            else -> clash(at, "is given a value and also names signals under it")
                        }
                    node[part] = next
                    node = next
                }
            }
        }
        return out
    }

    private fun merged(
        existing: Any?,
        value: Any?,
        at: String,
        clash: (String, String) -> Nothing,
    ): Any? {
        if (existing !is Map<*, *> && value !is Map<*, *>) clash(at, "is given twice")
        if (existing !is Map<*, *> || value !is Map<*, *>) clash(at, "is given a value and also names signals under it")
        val out = copy(existing)
        for ((k, v) in value) {
            val key = k.toString()
            out[key] = if (key in out) merged(out[key], v, "$at.$key", clash) else v
        }
        return out
    }

    private fun copy(map: Map<*, *>): LinkedHashMap<String, Any?> =
        LinkedHashMap<String, Any?>().also { out -> map.forEach { (k, v) -> out[k.toString()] = v } }
}
