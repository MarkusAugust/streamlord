package io.github.markusaugust.streamlord.core.domain

/**
 * The signal names Datastar's client cannot hold, measured against its 1.0.4 store.
 *
 * - `__proto__` makes its merge throw halfway through a batch it never closes, and no later
 *   signal change reaches the page until it is reloaded.
 * - `constructor` reaches `Object.prototype` when an object stands under it, as in
 *   `{"constructor": {"prototype": {"x": 1}}}` or `data-signals:constructor.prototype.x`, and
 *   changes every object on the page.
 * - A key holding `.` is stored as one key, which `$a.b` does not reach, and the client loses its
 *   value when it sends the signals with a request, because it splits the key at the dot.
 */
public object SignalNames {
    private val FORBIDDEN = setOf("__proto__", "constructor")

    /** Why the client cannot hold [key] as one key of a signal object, or `null` when it can. */
    public fun keyProblem(key: String): String? =
        when {
            key in FORBIDDEN -> segmentProblem(key)
            '.' in key -> "the client stores a key holding '.' as one key, which \$a.b does not reach and requests drop"
            else -> null
        }

    /** Why the client cannot hold [path], written with dots as `data-signals:a.b` writes it, or `null` when it can. */
    public fun pathProblem(path: String): String? = path.split('.').firstOrNull { it in FORBIDDEN }?.let(::segmentProblem)

    private fun segmentProblem(segment: String): String =
        if (segment == "__proto__") {
            "__proto__ makes the client's merge throw and leaves the page deaf to every later signal"
        } else {
            "constructor reaches Object.prototype in the client and changes every object on the page"
        }

    /**
     * The pairs as one signal object, with a dotted name nested the way `data-signals:a.b` nests
     * it: `"form.email" to "x"` and `"form.name" to "y"` give `{form: {email: "x", name: "y"}}`. A
     * map under a name merges with the dotted names below it. A name given twice, or given both as
     * a value and as the parent of another, throws [IllegalArgumentException].
     */
    public fun nest(pairs: Iterable<Pair<String, Any?>>): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        for ((name, value) in pairs) {
            val parts = name.split('.')
            var node = out
            for ((i, part) in parts.withIndex()) {
                val at = parts.subList(0, i + 1).joinToString(".")
                if (i == parts.lastIndex) {
                    node[part] = if (part in node) merged(node[part], value, at) else value
                } else {
                    node = branch(node, part, at)
                }
            }
        }
        return out
    }

    private fun branch(
        node: LinkedHashMap<String, Any?>,
        part: String,
        at: String,
    ): LinkedHashMap<String, Any?> {
        val existing = node[part]
        val next =
            when {
                part !in node -> LinkedHashMap()
                existing is Map<*, *> -> copy(existing, at)
                else -> throw IllegalArgumentException("$at is given a value and also names signals under it")
            }
        node[part] = next
        return next
    }

    private fun merged(
        existing: Any?,
        value: Any?,
        at: String,
    ): Any? {
        if (existing !is Map<*, *> || value !is Map<*, *>) throw IllegalArgumentException("$at is given twice")
        val out = copy(existing, at)
        for ((k, v) in value) {
            val key = k.toString()
            out[key] = if (key in out) merged(out[key], v, "$at.$key") else v
        }
        return out
    }

    private fun copy(
        map: Map<*, *>,
        at: String,
    ): LinkedHashMap<String, Any?> =
        LinkedHashMap<String, Any?>().also { out ->
            for ((k, v) in map) {
                if (k !is String) throw IllegalArgumentException("$at holds a key that is not a string")
                out[k] = v
            }
        }
}
