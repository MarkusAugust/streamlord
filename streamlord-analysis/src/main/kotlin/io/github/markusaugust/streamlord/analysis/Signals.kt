package io.github.markusaugust.streamlord.analysis

/*
 * Collects signal names declared anywhere in a source file, for completion. Heuristic and
 * generous: better to offer a name twice than to miss it.
 */

public enum class SourceLanguage { KOTLIN, HTML }

// A kebab-case name is written as a kebab-case key, which Datastar reads as camelCase: signal("foo-bar") is $fooBar.
private val KOTLIN_PATTERNS =
    listOf(
        Regex(
            """\b(?:signal|set|setExpr|increment|decrement|toggle|not|dataBind|dataIndicator|dataRef|dataComputed|dataMatchMedia)\(\s*"([A-Za-z_][A-Za-z0-9_.-]*)"""",
        ),
        Regex("""\bdataSignals\(\s*"([A-Za-z_][A-Za-z0-9_.-]*)"\s*,"""),
    )

/** A dollar escaped in a Kotlin string: a signal read, which completion offers and a definition is not. */
private val KOTLIN_READS =
    listOf(
        Regex("""\$\{'\$'\}([A-Za-z_][A-Za-z0-9_.]*)"""),
        Regex("""\\\$([A-Za-z_][A-Za-z0-9_.]*)"""),
    )

/*
 * Declarations in markup, read as the client stores them. These also run over Kotlin, where they
 * only ever match inside HTML strings. The browser lowercases an attribute name, so the name is
 * matched in any case and its key is lowercased: `data-signals:fooBar` declares `foobar`.
 */

/**
 * The key with its modifiers, which may name the casing: `data-signals:my-value__case.snake`. A key
 * the client could hold and nothing else, so that template syntax (`data-signals:${key}`,
 * `data-signals:{{ name }}`, `data-signals:th:text`) declares nothing.
 */
private val KEYED_ATTRIBUTE =
    Regex("""(?i)data-(?:star-)?(?:signals|bind|indicator|ref|computed|match-media):([\p{L}\p{N}_.-]+)(?=[\s=>"'/\\]|$)""")

/** The value form names the signal as written: `data-bind="user-name"` is the signal `user-name`. */
private val VALUE_ATTRIBUTE = Regex("""(?i)data-(?:star-)?(?:bind|indicator|ref)(?:__[^=\s>]*)?=(?:\\?"([^"\\\s]+)\\?"|'([^'\s]+)')""")

// The object form may sit in either kind of quote, and its keys may be bare or quoted: JSON written
// by hand is `data-signals='{"query": ""}'`. Inside a Kotlin string the key's quotes are escaped.
// data-computed="{total: () => ...}" declares its keys the same way.
private val OBJECT_ATTRIBUTE = Regex("""(?i)data-(?:star-)?(?:signals|computed)(?:__[^=\s>]*)?=(?:"\{([^"]*)\}"|'\{([^']*)\}')""")

/** `$name = ...` in an expression: Datastar creates a signal it is assigned to, so the assignment defines it. */
private val ASSIGNED_SIGNAL = Regex("""(?:\$|\$\{'\$'\})([A-Za-z_][A-Za-z0-9_.]*)\s*=(?![=>])""")

/** A bare `$name` is a signal in markup; in Kotlin it is a template, so it stays out of the Kotlin list. */
private val BARE_SIGNAL = Regex("""\$([A-Za-z_][A-Za-z0-9_.]*)""")

private val PAIR_CALLS = setOf("dataSignals", "patchSignals", "removeSignals", "respondSignals", "datastarSignals")
private val JSON_CALLS = setOf("patchSignals", "respondSignals", "datastarSignals")
private val JSON_OBJECT = Regex("""^\s*\{""")

private val PAIR = Regex(""""([A-Za-z_][A-Za-z0-9_.-]*)"\s+to\b""")
private val NAME = Regex("""^"([A-Za-z_][A-Za-z0-9_.-]*)"$""")

public fun collectSignals(
    src: String,
    language: SourceLanguage,
): Set<String> {
    val out = LinkedHashSet(collectDeclaredSignals(src, language))
    if (language == SourceLanguage.KOTLIN) {
        for (properties in serializableProperties(src).values) for (property in properties) out += property.name
    }
    out.removeAll { PLACEHOLDER in it }
    return out
}

/**
 * The signals this source puts on a page: the markup attributes that declare them, and the DSL
 * calls that write them.
 *
 * A property of a `@Serializable` class is left out, which is what separates this from
 * [collectSignals]: a signals class names what a handler reads, and a handler reading a name is
 * not a page declaring it. The drift check in [signalDrift] turns on exactly that difference.
 */
public fun collectDeclaredSignals(
    src: String,
    language: SourceLanguage,
): Set<String> = collect(src, language, reads = true)

/**
 * The signals this source defines, which a `$name` in an expression is checked against: what
 * [collectDeclaredSignals] finds without the reads (a bare `$name` in markup, a `${'$'}name` in
 * Kotlin), plus the properties of `@Serializable` classes, which a handler patches as well as
 * reads, and every signal an expression assigns to.
 */
public fun collectSignalDefinitions(
    src: String,
    language: SourceLanguage,
): Set<String> {
    val out = LinkedHashSet(collect(src, language, reads = false))
    if (language == SourceLanguage.KOTLIN) {
        for (properties in serializableProperties(src).values) for (property in properties) out += property.name
    }
    for (m in ASSIGNED_SIGNAL.findAll(src)) out += m.groupValues[1]
    out.removeAll { PLACEHOLDER in it }
    return out
}

private fun collect(
    src: String,
    language: SourceLanguage,
    reads: Boolean,
): Set<String> {
    val out = LinkedHashSet<String>()
    if (language == SourceLanguage.KOTLIN) {
        for (site in findCallSites(src, PAIR_CALLS)) {
            val positional = site.positional
            positional.forEachIndexed { idx, a ->
                // dataSignals("foo-bar" to 1) declares $fooBar, as the keyed form does; a patch keeps the key.
                for (pair in PAIR.findAll(a.text)) out += pair.groupValues[1].let { if (site.name == "dataSignals") toCamel(it) else it }
                // patchSignals("""{"count": 1}"""): the signals as JSON text.
                if (site.name in JSON_CALLS) {
                    a.string?.text?.takeIf { JSON_OBJECT.containsMatchIn(it) }?.let { json ->
                        out += objectKeyPaths(json.substring(json.indexOf('{') + 1, maxOf(json.indexOf('{') + 1, json.lastIndexOf('}'))))
                    }
                }
                val name = NAME.find(a.text)?.groupValues?.get(1) ?: return@forEachIndexed
                // removeSignals("a", "b") names keys as they are; dataSignals("name", "expression") writes a key Datastar camel-cases.
                if (site.name == "removeSignals") out += name
                if (site.name == "dataSignals" && idx == 0 && positional.getOrNull(1)?.string != null) out += toCamel(name)
            }
        }
        for (re in KOTLIN_PATTERNS) for (m in re.findAll(src)) out += toCamel(m.groupValues[1])
        if (reads) for (re in KOTLIN_READS) for (m in re.findAll(src)) out += m.groupValues[1]
    }
    // A keyed attribute may carry modifiers after the key: data-signals:foo-bar__ifmissing, and
    // `__case` names the signal: data-signals:my-value__case.snake is `$my_value`.
    for (m in KEYED_ATTRIBUTE.findAll(src)) out += clientKeyName(m.groupValues[1].asciiLowercase())
    for (m in VALUE_ATTRIBUTE.findAll(src)) out += m.groups[1]?.value ?: m.groupValues[2]
    for (m in OBJECT_ATTRIBUTE.findAll(src)) out += objectKeyPaths(m.groups[1]?.value ?: m.groupValues[2])
    if (reads && language == SourceLanguage.HTML) {
        for (m in BARE_SIGNAL.findAll(src)) out += toCamel(m.groupValues[1])
    }
    // The placeholder the analysis writes for a Kotlin template is never a signal, whatever file it turns up in.
    out.removeAll { PLACEHOLDER in it }
    return out
}

/** The properties of `Object.prototype` in a browser: every object already has them (`SignalNames` in streamlord-core). */
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
 * Why the client cannot hold a signal at [path], written with dots as `data-signals:a.b` writes it,
 * or null when it can. The client follows the path without a guard, so a part every object already
 * has, with more of the path under it, writes into the built-in one: `__proto__.x` and
 * `constructor.prototype.x` set `x` on every object of the page. The last part is held as a key of
 * its own (`data-signals:constructor="1"` is `$constructor`) unless [objectUnder] says an
 * object goes under it; `__proto__` is never held. `SignalNames.pathProblem` in streamlord-core is
 * stricter, since a name written in code may carry an object under it at any part.
 */
internal fun signalPathProblem(
    path: String,
    objectUnder: Boolean = false,
): String? {
    val parts = path.split('.')
    if (parts.any { it.isEmpty() }) return "has an empty part, which no \$ reference reaches"
    return parts
        .withIndex()
        .firstOrNull { (i, p) -> p == "__proto__" || (p in PROTOTYPE_NAMES && (i < parts.lastIndex || objectUnder)) }
        ?.let {
            "has the part ${it.value}, which every object already has, so the client writes into the built-in one instead of holding a signal"
        }
}

/** What the client reads after a `$`: ASCII letters, digits and `_`, in parts joined by `.` or `-` (engine.ts:449 in 1.0.4). */
private val REACHABLE_NAME = Regex("""[A-Za-z0-9_]+(?:[.-][A-Za-z0-9_]+)*""")

/** `$['my key']`, the read that reaches a signal at [parts] whatever its name: the client leaves a `$[` as written. */
internal fun bracketRead(parts: List<String>): String =
    "$" + parts.joinToString("") { p -> "['" + p.replace("\\", "\\\\").replace("'", "\\'") + "']" }

/**
 * Why no `$name` reaches a signal named [name], or null when one does. [parts] is the path the
 * bracket form names, the name split at its dots unless given.
 */
internal fun signalNameProblem(
    name: String,
    parts: List<String> = name.split('.'),
): String? {
    if (REACHABLE_NAME.matches(name)) return null
    if (name.startsWith("$")) return "holds the \$ itself, so \$${name.drop(1)} does not reach it: that reads the signal ${name.drop(1)}"
    val bracket = "only the bracket form ${bracketRead(parts)} reads it"
    return if (name.any { it > '\u007F' }) {
        "no \$name reaches: Datastar reads only ASCII letters, digits and _ after a \$, so the browser looks for a JavaScript variable instead; $bracket"
    } else {
        "no \$name reaches: Datastar reads ASCII letters, digits and _ after a \$, in parts joined by . or -; $bracket"
    }
}

private fun caseCamel(s: String): String = KEBAB_LETTER.replace(s) { it.value.substring(1).uppercase() }

private val KEBAB_LETTER = Regex("-[a-z]")

/**
 * The signal the client makes of an attribute key written with its modifiers, as the browser hands
 * it over: `parseAttributeKey` (engine.ts) cuts the modifiers off at the first `__`, and
 * `modifyCasing` (text.ts) applies each `__case` given, in order, camel when none is. A casing it
 * does not know, kebab among them, leaves the key as it is.
 */
internal fun clientKeyName(written: String): String {
    val parts = written.split("__")
    val modifiers = LinkedHashMap<String, List<String>>()
    for (raw in parts.drop(1)) {
        val pieces = raw.split('.')
        modifiers[pieces[0]] = pieces.drop(1).distinct()
    }
    var name = parts[0]
    for (case in modifiers["case"] ?: listOf("camel")) {
        val cased =
            when (case) {
                "camel" -> caseCamel(name)
                "snake" -> name.replace('-', '_')
                "pascal" -> name.take(1).uppercase() + caseCamel(name.drop(1))
                else -> null
            }
        name = cased?.ifEmpty { null } ?: name
    }
    return name
}

/** A key of the object form: its path, where it is written, where its value starts, and whether that value is an object. */
internal data class ObjectKey(
    val path: String,
    val key: String,
    val start: Int,
    val end: Int,
    val valueStart: Int,
    val objectUnder: Boolean,
)

/**
 * The signal paths the object form of `data-signals` or `data-computed` declares, [body] being the
 * text between its outer braces: each key as written, and the keys of an object under a key with
 * that key before them, as the client's `mergePatch` stores them. `{user: {name: 'x'}}` declares
 * `user` and `user.name`. A key may be bare or quoted, and inside a Kotlin string its quotes may be
 * escaped; an object that is not the value of a key, inside a function or a list, declares nothing.
 * An object literal spread into another declares its keys there; what a spread of anything else
 * brings is not known.
 */
internal fun objectKeyPaths(body: String): List<String> = objectKeys(body).map { it.path }

/** The keys [objectKeyPaths] names, with where each is written in [body]. */
internal fun objectKeys(body: String): List<ObjectKey> {
    val out = ArrayList<ObjectKey>()
    // The open brackets: the path of an object that is the value of a key, or null for anything else.
    val frames = arrayListOf<String?>("")
    var expectKey = true
    var objectFor: String? = null
    var i = 0

    fun skipSpace() {
        while (i < body.length && body[i].isJsSpace()) i++
    }
    while (i < body.length) {
        val top = frames.last()
        if (expectKey && top != null) {
            expectKey = false
            skipSpace()
            var key: String? = null
            val keyStart = i
            val escaped = body.getOrNull(i) == '\\' && (body.getOrNull(i + 1) == '"' || body.getOrNull(i + 1) == '\'')
            val quote = if (escaped) body.getOrNull(i + 1) else body.getOrNull(i)
            if (quote == '"' || quote == '\'') {
                val from = i + if (escaped) 2 else 1
                var j = from
                while (j < body.length && body[j] != quote && !(body[j] == '\\' && body.getOrNull(j + 1) == quote)) j++
                key = body.substring(minOf(from, body.length), minOf(j, body.length))
                i = j + if (body.getOrNull(j) == '\\') 2 else 1
            } else {
                val m = BARE_KEY.matchAt(body, i)
                if (m != null) {
                    key = m.value
                    i += key.length
                }
            }
            val keyEnd = minOf(i, body.length)
            skipSpace()
            if (key != null && body.getOrNull(i) == ':') {
                val path = if (top.isEmpty()) key else "$top.$key"
                i++
                skipSpace()
                objectFor = if (body.getOrNull(i) == '{') path else null
                out += ObjectKey(path, key, keyStart, keyEnd, i, objectFor != null)
            }
            continue
        }
        val c = body[i]
        if (c == '"' || c == '\'' || c == '`') {
            var j = i + 1
            while (j < body.length && body[j] != c) j += if (body[j] == '\\') 2 else 1
            i = j + 1
            continue
        }
        // `...{b: 2}` spreads its keys into the object it stands in: `{a: 1, ...{b: 2}}` declares `b`.
        if (c == '.' && body.startsWith("...", i) && top != null) {
            i += 3
            skipSpace()
            if (body.getOrNull(i) == '{') objectFor = top
            continue
        }
        when (c) {
            '{' -> {
                frames += objectFor
                expectKey = objectFor != null
            }

            '[', '(' -> {
                frames += null
            }

            '}', ']', ')' -> {
                if (frames.size > 1) frames.removeAt(frames.size - 1)
            }

            ',' -> {
                expectKey = true
            }
        }
        objectFor = null
        i++
    }
    return out
}

private val BARE_KEY = Regex("""[A-Za-z0-9_$]+""")

/** What `\s` matches in JavaScript. */
private fun Char.isJsSpace(): Boolean = isWhitespace() || this == '\u00A0' || this == '\uFEFF'
