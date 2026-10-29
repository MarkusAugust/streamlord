package io.github.markusaugust.streamlord.core.domain

import io.github.markusaugust.streamlord.core.DatastarEventValidationException
import io.github.markusaugust.streamlord.core.InternalStreamlordApi

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
@InternalStreamlordApi
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

    // What Datastar's client finds behind a `$`, less the hyphen a signal name may not hold: ASCII
    // word characters, in parts joined by `.`.
    private val SIGNAL_NAME = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)*")

    /**
     * Why [name] cannot declare a signal in code, or `null` when it can: what
     * [AttributeKeys.nameProblem] refuses, a hyphen ([hyphenProblem]), a part the client cannot hold
     * ([pathProblem]), or anything but ASCII letters, digits and `_` in parts joined by `.`, since
     * Datastar's client reads nothing else after a `$`: `$über` or `$a:b` would reach no signal.
     * The DSL throws with this reason and the analysis reports it.
     */
    public fun nameProblem(name: String): String? =
        AttributeKeys.nameProblem(name)
            ?: hyphenProblem(name)
            ?: pathProblem(name)
            ?: if (SIGNAL_NAME.matches(name)) {
                null
            } else {
                "is not a name a \$ reference reaches: Datastar reads ASCII letters, digits and _, in parts joined by dots"
            }

    /**
     * Why [name] cannot name a signal in code, or `null` when it can: it has a hyphen.
     *
     * A signal goes by the name the code writes, in a patch, in an expression and in an attribute
     * key alike. The hyphen is the one character the client reads differently between them: in an
     * attribute key it camel-cases `foo-bar` to `fooBar`, in `$foo-bar` and in a patch it keeps it.
     * Without one, every name comes back as written, so a name with one is refused rather than
     * meaning two signals. A key with a hyphen is still data inside a value or in JSON text.
     */
    public fun hyphenProblem(name: String): String? {
        if ('-' !in name) return null
        return "has a hyphen, which Datastar's client reads as a capital in an attribute key and as a hyphen in an " +
            "expression or a patch, so it would name two signals; write ${withoutHyphens(name)}"
    }

    /** [name] with each hyphen dropped and the letter after it raised: `first-name` is `firstName`. */
    public fun withoutHyphens(name: String): String = HYPHEN.replace(name) { it.value.trimStart('-').uppercase() }

    private val HYPHEN = Regex("-+.?")

    /**
     * [name], a reference an expression writes after `$`, with its hyphens taken out, or `null`
     * when it has none that matter. The client reads `$` and [name] as it reads any expression, and
     * two kinds of hyphen go wrong there: one in a signal the client reads, as in `items[${'$'}i-1]`,
     * which reads the signal `i-1`, and one outside brackets and strings that no reference holds, as
     * in `a['x'].b-c`, which JavaScript reads as a minus. A quoted key and the text of a template
     * literal are text, so `form['first-name']` and `a['x]y-z']` are fine. Each hyphen that goes
     * wrong is dropped and the character after it raised: `items[${'$'}i1]`, `a['x'].bC`. Where
     * [referenceAsSubtraction] gives an answer, that is the likelier meaning.
     */
    public fun withoutReferenceHyphens(name: String): String? {
        val text = "$" + name
        val wrong = wrongHyphens(text)
        if (wrong.isEmpty()) return null
        val out = StringBuilder()
        var raise = false
        for ((k, c) in text.withIndex()) {
            if (k in wrong) {
                raise = true
                continue
            }
            out.append(if (raise) c.toString().uppercase() else c.toString())
            raise = false
        }
        return out.substring(1)
    }

    /**
     * The expression [name] would be with each hyphen that goes wrong before a digit or a `$`
     * written as a subtraction, `${'$'}items[${'$'}i - 1]` for `items[${'$'}i-1]`, or `null` when no such
     * hyphen goes wrong. A name holds no spaces, so the subtraction belongs in the expression. Any
     * other hyphen that goes wrong is taken out as [withoutReferenceHyphens] does.
     */
    public fun referenceAsSubtraction(name: String): String? {
        val text = "$" + name
        val wrong = wrongHyphens(text)
        val minus = wrong.filter { text.getOrNull(it + 1)?.let { c -> c in '0'..'9' || c == '$' } == true }.toSet()
        if (minus.isEmpty()) return null
        val out = StringBuilder()
        var raise = false
        for ((k, c) in text.withIndex()) {
            when {
                k in minus -> {
                    out.append(" - ")
                }

                k in wrong -> {
                    raise = true
                }

                else -> {
                    out.append(if (raise) c.toString().uppercase() else c.toString())
                    raise = false
                }
            }
        }
        return out.toString()
    }

    // The offsets in text of the hyphens that go wrong. The client skips a quoted string, and a
    // template literal without `${`, so their text is in strings; the text of one with `${` it reads
    // as code, which JavaScript does not, so the scan steps over that text itself.
    private fun wrongHyphens(text: String): Set<Int> {
        val read = clientRewritten(text)
        val wrong = HashSet<Int>()
        for (r in read.references) {
            for (i in r.start until r.end) if (text[i] == '-') wrong += i
        }
        // -1 for the text of a template literal, n for the code of a `${`, n braces deep.
        val nesting = ArrayList<Int>()
        var depth = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (nesting.lastOrNull() == -1) {
                when {
                    c == '\\' -> {
                        i++
                    }

                    c == '`' -> {
                        nesting.removeAt(nesting.lastIndex)
                    }

                    c == '$' && text.getOrNull(i + 1) == '{' -> {
                        nesting += 0
                        i++
                    }
                }
                i++
                continue
            }
            val skip =
                read.strings.firstOrNull { i in it }
                    ?: read.references.firstOrNull { i >= it.start && i < it.end }?.let { it.start until it.end }
            if (skip != null) {
                i = skip.last + 1
                continue
            }
            when (c) {
                '`' -> {
                    nesting += -1
                }

                '{' -> {
                    if (nesting.isNotEmpty()) nesting[nesting.lastIndex]++
                }

                '}' -> {
                    if (nesting.isNotEmpty()) {
                        if (nesting.last() == 0) nesting.removeAt(nesting.lastIndex) else nesting[nesting.lastIndex]--
                    }
                }

                '[' -> {
                    depth++
                }

                ']' -> {
                    depth = maxOf(0, depth - 1)
                }

                '-' -> {
                    if (depth == 0) wrong += i
                }
            }
            i++
        }
        return wrong
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
