package io.github.markusaugust.streamlord.analysis

import io.github.markusaugust.streamlord.core.client.ClientCasing
import io.github.markusaugust.streamlord.core.domain.SignalNames

/*
 * Collects signal names declared anywhere in a source file, for completion. Heuristic and
 * generous: better to offer a name twice than to miss it.
 */

public enum class SourceLanguage { KOTLIN, HTML }

// A helper names a signal as it is written: set("fooBar", 1) is $fooBar. A name with a hyphen is
// refused at runtime (signalNameHyphenIssues) and collected as written. set, setExpr and toggle
// assign the signal, which creates it.
private val KOTLIN_PATTERNS =
    listOf(
        Regex(
            """\b(?:set|setExpr|toggle|dataBind|dataIndicator|dataRef|dataComputed|dataMatchMedia)\(\s*"([A-Za-z_][A-Za-z0-9_.-]*)"""",
        ),
        Regex("""\bdataSignals\(\s*"([A-Za-z_][A-Za-z0-9_.-]*)"\s*,"""),
    )

/** A dollar escaped in a Kotlin string: a signal read, which completion offers and a definition is not. */
private val KOTLIN_READS =
    listOf(
        Regex("""\$\{'\$'\}([A-Za-z_][A-Za-z0-9_.]*)"""),
        Regex("""\\\$([A-Za-z_][A-Za-z0-9_.]*)"""),
    )

/** A helper that reads a signal by name: `signal("count")` is `$count`, which completion offers and a definition is not. */
private val HELPER_READS = Regex("""\b(?:${HELPER_READ_CALLS.joinToString("|")})\(\s*"([A-Za-z_][A-Za-z0-9_.-]*)"""")

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

/**
 * `toJsonValue(...)` handed to `dataSignals`: the page declares what the codec makes of the value.
 * The class is named by `toJsonValue<Form>(...)`, by a constructor call, `toJsonValue(Form(...))`,
 * or by a variable whose type the file writes down, `toJsonValue(form)` with `form: Form`.
 */
private val TO_JSON =
    Regex(
        """\btoJsonValue\s*(?:<\s*([A-Za-z_][A-Za-z0-9_.]*)\s*>)?\s*\(\s*(?:(?:[a-z_][A-Za-z0-9_]*\.)*([A-Z][A-Za-z0-9_]*)\s*(?:<[^()]*>)?\s*\(|([a-z_][A-Za-z0-9_]*)\s*\))?""",
    )

/** A pair whose key is not a literal, `key to value`: the fields under it are no top-level signals. */
private val ANY_PAIR = Regex("""^[^(]*?\bto\s""")
private val IDENTIFIER_ARG = Regex("""^\s*([a-z_][A-Za-z0-9_]*)\s*$""")
private val BOOLEAN_ARG = Regex("""^\s*(?:true|false)\s*$""")

/** Types that say nothing about the names in them. */
private val UNNAMED_TYPES = setOf("JsonValue", "JsonObject", "Any", "Map", "MutableMap", "HashMap", "LinkedHashMap")

/** Types that encode to no object, so a page declares nothing from them. */
private val VALUE_TYPES =
    setOf("String", "Int", "Long", "Short", "Byte", "Double", "Float", "Boolean", "Char", "List", "MutableList", "Set", "Array", "Pair")

/**
 * The classes whose encoded fields `dataSignals(codec.toJsonValue(...))` declares in [src], by
 * simple name, and [ANY_SIGNAL] for a value whose class the file does not name: `toJsonValue`
 * of something else, or a variable typed `JsonValue` or `JsonObject`. Any other argument, a string
 * or a pair held in a variable, declares nothing here.
 */
internal fun encodedSignalTypes(src: String): Set<String> {
    val out = LinkedHashSet<String>()
    for (site in findCallSites(src, setOf("dataSignals"))) {
        val positional = site.positional
        // dataSignals(value) or dataSignals(value, true), the second being ifMissing.
        val a =
            positional.firstOrNull()?.takeIf { positional.size == 1 || (positional.size == 2 && BOOLEAN_ARG.matches(positional[1].text)) }
                ?: site.named("signals")
                ?: continue
        if (a.string != null || PAIR.containsMatchIn(a.text) || ANY_PAIR.containsMatchIn(a.text)) continue
        encodedType(a.text, src.substring(0, site.nameStart), depth = 0)?.let { out += it }
    }
    return out
}

/** What [text] declares, read with the source [before] it, or null when it is no JSON value. */
private fun encodedType(
    text: String,
    before: String,
    depth: Int,
): String? {
    TO_JSON.find(text)?.let { m ->
        m.groups[1]?.let { return named(it.value.substringAfterLast('.')) }
        m.groups[2]?.let { return it.value }
        m.groups[3]?.let { id ->
            val type = variableType(id.value, before) ?: return ANY_SIGNAL
            return if (type in VALUE_TYPES) null else named(type)
        }
        return ANY_SIGNAL
    }
    // dataSignals(signals), with `val signals = codec.toJsonValue(Form())` or `signals: JsonObject` before it.
    val id = IDENTIFIER_ARG.find(text)?.groupValues?.get(1) ?: return null
    if (depth > 0) return null
    val name = Regex.escape(id)
    val init =
        Regex("""\b(?:val|var)\s+$name\s*(?::[^=\n]*)?=\s*([^\n;]+)""")
            .findAll(before)
            .lastOrNull()
            ?.groupValues
            ?.get(1)
    if (init != null && TO_JSON.containsMatchIn(init)) return encodedType(init, before, depth + 1)
    return variableType(id, before)?.takeIf { it in setOf("JsonValue", "JsonObject") }?.let { ANY_SIGNAL }
}

/** The class a variable is last declared with in [before], `form: Form` or `val form = Form(...)`. */
private fun variableType(
    id: String,
    before: String,
): String? {
    val name = Regex.escape(id)
    val typed = Regex("""\b$name\s*:\s*([A-Z][A-Za-z0-9_]*)""").findAll(before).lastOrNull()
    val built = Regex("""\b(?:val|var)\s+$name\s*=\s*(?:[a-z_][A-Za-z0-9_]*\.)*([A-Z][A-Za-z0-9_]*)\s*\(""").findAll(before).lastOrNull()
    return listOfNotNull(typed, built).maxByOrNull { it.range.first }?.groupValues?.get(1)
}

private fun named(type: String): String = if (type in UNNAMED_TYPES) ANY_SIGNAL else type

/**
 * What the definitions say about a class, written with a `:`, which no signal name holds: a class
 * a page is declared from, and a `@Serializable` class whose properties are defined. A class in the
 * first and not the second is one only its codec can name (see [isKnownSignal]).
 */
internal const val ENCODED_CLASS: String = "toJsonValue:"
internal const val READABLE_CLASS: String = "class:"

/** The argument as one pair, in parentheses or not; a `"k" to` further in is a key inside the value. */
private val PAIR = Regex("""^[\s(]*"([A-Za-z_][A-Za-z0-9_.-]*)"\s+to\b""")
private val NAME = Regex("""^"([A-Za-z_][A-Za-z0-9_.-]*)"$""")

/** A map handed over as the signals, `patchSignals(mapOf("count" to 0))`: its own pairs are the keys. */
private val MAP_CALLS = setOf("mapOf", "mutableMapOf", "hashMapOf", "linkedMapOf")
private val MAP_CALL = Regex("""^\s*(?:mutable|hash|linked)?[mM]apOf\s*\(""")

/** The literal keys of an argument that is a pair, or a map of pairs; nothing inside a value. */
private fun argumentKeys(text: String): List<String> {
    PAIR.find(text)?.let { return listOf(it.groupValues[1]) }
    if (!MAP_CALL.containsMatchIn(text)) return emptyList()
    val map = findCallSites(text, MAP_CALLS).firstOrNull() ?: return emptyList()
    return map.positional.mapNotNull { PAIR.find(it.text)?.groupValues?.get(1) }
}

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
 *
 * Two kinds of entry are no signal names, and hold a `:` so that none is mistaken for one:
 * `class:Form` for a `@Serializable` class whose properties are here, and `toJsonValue:Form` for a
 * class a page is declared from. [isKnownSignal] reads them together.
 */
public fun collectSignalDefinitions(
    src: String,
    language: SourceLanguage,
): Set<String> {
    val out = LinkedHashSet(collect(src, language, reads = false))
    if (language == SourceLanguage.KOTLIN) {
        for ((type, properties) in serializableProperties(src)) {
            out += READABLE_CLASS + type
            for (property in properties) out += property.name
        }
        // A page declared from a class defines what its codec writes: the properties above for a
        // @Serializable one, any name for one the analysis cannot read, or for a value of no named class.
        for (type in encodedSignalTypes(src)) out += if (type == ANY_SIGNAL) ANY_SIGNAL else ENCODED_CLASS + type
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
                for (key in argumentKeys(a.text)) out += key
                // patchSignals("""{"count": 1}"""): the signals as JSON text.
                if (site.name in JSON_CALLS) {
                    a.string?.text?.takeIf { JSON_OBJECT.containsMatchIn(it) }?.let { json ->
                        out += objectKeyPaths(json.substring(json.indexOf('{') + 1, maxOf(json.indexOf('{') + 1, json.lastIndexOf('}'))))
                    }
                }
                val name = NAME.find(a.text)?.groupValues?.get(1) ?: return@forEachIndexed
                // removeSignals("a", "b"), and dataSignals("name", "expression"), whose key the client reads back as the name.
                if (site.name == "removeSignals") out += name
                if (site.name == "dataSignals" && idx == 0 && positional.getOrNull(1)?.string != null) out += name
            }
        }
        for (re in KOTLIN_PATTERNS) for (m in re.findAll(src)) out += m.groupValues[1]
        if (reads) for (re in KOTLIN_READS) for (m in re.findAll(src)) out += m.groupValues[1]
        if (reads) for (m in HELPER_READS.findAll(src)) out += m.groupValues[1]
    }
    // A keyed attribute may carry modifiers after the key: data-signals:foo-bar__ifmissing, and
    // `__case` names the signal: data-signals:my-value__case.snake is `$my_value`.
    for (m in KEYED_ATTRIBUTE.findAll(src)) out += ClientCasing.readKey(m.groupValues[1])
    for (m in VALUE_ATTRIBUTE.findAll(src)) out += m.groups[1]?.value ?: m.groupValues[2]
    for (m in OBJECT_ATTRIBUTE.findAll(src)) out += objectKeyPaths(m.groups[1]?.value ?: m.groupValues[2])
    if (reads && language == SourceLanguage.HTML) {
        for (m in BARE_SIGNAL.findAll(src)) out += m.groupValues[1]
    }
    // The placeholder the analysis writes for a Kotlin template is never a signal, whatever file it turns up in.
    out.removeAll { PLACEHOLDER in it }
    // What a spread may declare is no name to complete.
    if (reads) out.remove(ANY_SIGNAL)
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
 * its own (`data-signals:constructor="1"` is `$constructor`), and so is an object under it when
 * [objectUnder] says one goes there, but the next request's `filtered()` writes that object into
 * the built-in one and sends the page's signals without it; `__proto__` is never held.
 * `SignalNames.pathProblem` in streamlord-core is stricter, since a name written in code may carry
 * an object under it at any part.
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
        ?.let { (i, p) ->
            if (p != "__proto__" && i == parts.lastIndex) {
                "has the part $p, which every object already has: the client holds the signal, but the next request " +
                    "writes the object under it into the built-in one and sends the page's signals without it"
            } else {
                "has the part $p, which every object already has, so the client writes into the built-in one instead of holding a signal"
            }
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

/**
 * A key of the object form: its path, the parts of that path as the keys are written (`'a.b'` is
 * one part), where it is written, where its value starts, and whether that value is an object.
 */
internal data class ObjectKey(
    val path: String,
    val parts: List<String>,
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
 * brings is not known. Spread into the object itself, `{...init}`, it may be any signal, written
 * `*` (see [isKnownSignal]); under a key, `{user: {...init}}`, it is under `user`, which is declared.
 */
internal fun objectKeyPaths(body: String): List<String> {
    val spread = Spread()
    val paths = objectKeys(body, spread).map { it.path }
    return if (spread.any) paths + ANY_SIGNAL else paths
}

/** What a spread of a variable into the object form may declare: any signal. */
internal const val ANY_SIGNAL: String = "*"

/** Whether an object spreads something other than an object literal into itself. */
internal class Spread {
    var any: Boolean = false
}

/**
 * The keys [objectKeyPaths] names, with where each is written in [body]. [spread] is set when the
 * object itself spreads something other than an object literal.
 */
internal fun objectKeys(
    body: String,
    spread: Spread = Spread(),
): List<ObjectKey> {
    val out = ArrayList<ObjectKey>()
    // The open brackets: the parts of the path of an object that is the value of a key, or null for anything else.
    val frames = arrayListOf<List<String>?>(emptyList())
    var expectKey = true
    var objectFor: List<String>? = null
    var i = 0

    fun skipSpace() {
        while (i < body.length && isJsSpace(body[i])) i++
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
                val parts = top + key
                i++
                skipSpace()
                objectFor = if (body.getOrNull(i) == '{') parts else null
                out += ObjectKey(parts.joinToString("."), parts, key, keyStart, keyEnd, i, objectFor != null)
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
            if (body.getOrNull(i) == '{') {
                objectFor = top
            } else if (top.isEmpty()) {
                spread.any = true
            }
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

private val PAIR_KEY = Regex("""^[\s(]*"""")
private val PAIR_AFTER = Regex("""\s+to\b""")
private val TO_NULL = Regex("""\s+to\s+null\b""")

/** The keys of the pairs among the positional arguments of [site], `"name" to value`, as string literals. */
internal fun pairKeys(
    site: CallSite,
    src: String,
    lex: KotlinLex,
): List<KotlinString> =
    site.positional.mapNotNull { a ->
        if (a.string != null) return@mapNotNull null
        val quote = PAIR_KEY.find(a.text)?.range?.last ?: return@mapNotNull null
        lex.stringAt(a.start + quote + 1)?.takeIf { it.start == a.start + quote && PAIR_AFTER.matchesAt(src, it.end) }
    }

/**
 * Why `Casing.signalName` refuses [name], as [SignalNames.nameProblem] says, or null. Not for a
 * hyphen alone, which signalNameHyphenIssues reports with the name to write instead.
 */
internal fun signalNameRefusal(name: String): String? = SignalNames.nameProblem(name)?.takeIf { it != SignalNames.hyphenProblem(name) }

/**
 * What is wrong with the pairs of [site], a `dataSignals` call. A name the DSL refuses
 * ([signalNameRefusal]). And each `"name" to null`,
 * which declares nothing: Datastar's client holds no null, so the null removes the key, and an
 * expression that reads the signal gets `""`, which the client makes of every null it reads. A
 * warning, since a pair from a codec may hold a null on purpose. `patchSignals` is left alone,
 * since a null there removes the signal, as it is meant to.
 */
internal fun dataSignalsPairIssues(
    site: CallSite,
    src: String,
    lex: KotlinLex,
): List<Issue> {
    if (site.name != "dataSignals") return emptyList()
    return pairKeys(site, src, lex).mapNotNull { key ->
        // A templated name is known only at runtime.
        signalNameRefusal(key.text)?.takeIf { key.interpolations.isEmpty() }?.let { reason ->
            return@mapNotNull Issue(
                start = key.start,
                end = key.end,
                message = "Streamlord refuses \"${key.text}\" at runtime: it $reason.",
                severity = Severity.ERROR,
                code = "key-refused",
                link = Docs.attribute("signals"),
            )
        }
        val end =
            TO_NULL
                .matchAt(src, key.end)
                ?.range
                ?.last
                ?.plus(1) ?: return@mapNotNull null
        Issue(
            start = end - 4,
            end = end,
            message =
                "Datastar's client holds no null: in data-signals a null removes the key, so \"${key.text}\" is not declared, " +
                    "and an expression that reads it gets \"\". Declare it with the value it starts as, such as \"\".",
            severity = Severity.WARNING,
            code = "signal-null",
            link = Docs.SIGNALS,
        )
    }
}
