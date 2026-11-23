package io.github.markusaugust.streamlord.analysis

import io.github.markusaugust.streamlord.core.catalog.CatalogNames
import io.github.markusaugust.streamlord.core.client.CLIENT_SIGNAL
import io.github.markusaugust.streamlord.core.client.ClientCasing
import io.github.markusaugust.streamlord.core.client.regex
import io.github.markusaugust.streamlord.core.domain.SignalNames
import kotlin.concurrent.Volatile

/*
 * Collects signal names declared anywhere in a source file, for completion. Heuristic and
 * generous: better to offer a name twice than to miss it.
 */

/** How a file is read: Kotlin, markup, or the settings of a Spring application, read only for how its codec names signals. */
public enum class SourceLanguage { KOTLIN, HTML, SETTINGS }

// A helper names a signal as it is written: set("fooBar", 1) is $fooBar. A name with a hyphen is
// refused at runtime (signalNameHyphenIssues) and collected as written. The helpers that do not
// only read it, set, setExpr and toggle, assign the signal, which creates it; dataSignals names one
// only before its expression, its object form being one argument.
private val NAMING_CALLS =
    Catalog.default.signalNameHelpers
        .filterValues { "reads" !in it }
        .keys + NAMING_HELPERS - KEYED_OR_OBJECT
private val KOTLIN_PATTERNS =
    listOf(
        regex("""\b(?:${NAMING_CALLS.joinToString("|")})\(\s*"([A-Za-z_][A-Za-z0-9_.-]*)""""),
        regex("""\b$KEYED_OR_OBJECT\(\s*"([A-Za-z_][A-Za-z0-9_.-]*)"\s*,"""),
    )

/** A dollar escaped in a Kotlin string: a signal read, which completion offers and a definition is not. */
private val KOTLIN_READS =
    listOf(
        regex("""\$\{'\$'\}([A-Za-z_][A-Za-z0-9_.]*)"""),
        regex("""\\\$([A-Za-z_][A-Za-z0-9_.]*)"""),
    )

/** A helper that reads a signal by name: `signal("count")` is `$count`, which completion offers and a definition is not. */
private val HELPER_READS = regex("""\b(?:${HELPER_READ_CALLS.joinToString("|")})\(\s*"([A-Za-z_][A-Za-z0-9_.-]*)"""")

/*
 * Declarations in markup, read as the client stores them. These also run over Kotlin, where they
 * only ever match inside HTML strings. The browser lowercases an attribute name, so the name is
 * matched in any case and its key is lowercased: `data-signals:fooBar` declares `foobar`.
 */

/** The object value of a keyed attribute, after its key: `="{...}"`, `='{...}'`, its quotes escaped in a Kotlin string. */
private val KEYED_OBJECT = regex("""\s*=\s*(?:\\?"\{([^"]*)\}\\?"|'\{([^']*)\}')""")

/** [prefix] as a pattern: the bundle's own, and the default one read with `data-star-` as well. */
internal fun prefixPattern(prefix: String): String =
    if (prefix == CatalogNames.PREFIX) "data-(?:star-)?" else prefix.map { if (it.isLetterOrDigit()) "$it" else "[\\$it]" }.joinToString("")

/** The attributes that declare signals under [prefix], the bundle's; the default one is read with `data-star-` as well. */
private class DeclaringAttributes(
    val prefix: String,
) {
    private val written = prefixPattern(prefix)

    /**
     * The key with its modifiers, which may name the casing: `data-signals:my-value__case.snake`. A key
     * the client could hold and nothing else, so that template syntax (`data-signals:${key}`,
     * `data-signals:{{ name }}`, `data-signals:th:text`) declares nothing.
     */
    val keyed =
        regex(
            """(?i)$written(?:${Catalog.default.signalKeyAttributes.joinToString("|")}):([\p{L}\p{N}_.-]+)(?=[\s=>"'/\\]|$)""",
        )

    /** The value form names the signal as written: `data-bind="user-name"` is the signal `user-name`. */
    val value =
        regex(
            """(?i)$written(?:${Catalog.default.attributes.filter { it.valueKind == ValueKind.SIGNAL }.joinToString("|") { it.name }})""" +
                """(?:__[^=\s>]*)?=(?:\\?"([^"\\\s]+)\\?"|'([^'\s]+)')""",
        )

    // The object form may sit in either kind of quote, and its keys may be bare or quoted: JSON written
    // by hand is `data-signals='{"query": ""}'`. Inside a Kotlin string the key's quotes are escaped.
    // data-computed="{total: () => ...}" declares its keys the same way.
    val objectForm = regex("""(?i)$written(?:signals|computed)(?:__[^=\s>]*)?=(?:"\{([^"]*)\}"|'\{([^']*)\}')""")

    /** Whether the keyed attribute [matched] is `data-signals`, whose object value declares signals under its key. */
    fun signals(matched: String): Boolean {
        if (prefix != CatalogNames.PREFIX) return matched.substring(prefix.length).startsWith("signals", true)
        val after = matched.substringAfter('-')
        return after.startsWith("signals", true) || after.startsWith("star-signals", true)
    }
}

@Volatile
private var lastDeclaring = DeclaringAttributes(CatalogNames.PREFIX)

private fun declaring(prefix: String): DeclaringAttributes =
    lastDeclaring.takeIf { it.prefix == prefix } ?: DeclaringAttributes(prefix).also { lastDeclaring = it }

/**
 * `$name = ...`, `$name += 1`, `$name++` or `--$name` in an expression: the client's store creates a
 * signal it is written to, so every assignment defines it. A read creates one too, holding `''`, but
 * counts for nothing here, so that a misspelt read is still reported.
 */
private val ASSIGNED_SIGNAL =
    regex(
        """(?:\+\+|--)\s*(?:\$|\$\{'\$'\})([A-Za-z_][A-Za-z0-9_.]*)|(?:\$|\$\{'\$'\})([A-Za-z_][A-Za-z0-9_.]*)""" +
            """\s*(?:(?:\*\*|&&|\|\||\?\?|<<|>>>|>>|[-+*/%&|^])?=(?![=>])|\+\+|--)""",
    )

/** A bare `$name` is a signal in markup; in Kotlin it is a template, so it stays out of the Kotlin list. */
private val BARE_SIGNAL = regex("""\$([A-Za-z_][A-Za-z0-9_.]*)""")

/** The calls whose pairs and names declare signals, by the name they are written with: a companion's `remove` too. */
private val PAIR_CALLS = Catalog.default.signalCalls.keys + removalCallNames

/** Whether the call whose name starts at [at] is made on a receiver: a `.` before it, past whitespace and line comments. */
private fun onReceiver(
    src: String,
    at: Int,
): Boolean {
    var i = at - 1
    while (i >= 0) {
        val c = src[i]
        if (c == '\n') {
            // A line comment ending the line before says nothing about a receiver.
            val lineStart = src.lastIndexOf('\n', i - 1) + 1
            val comment = src.indexOf("//", lineStart)
            i = if (comment in lineStart until i) comment - 1 else i - 1
            continue
        }
        if (!c.isWhitespace()) return c == '.'
        i--
    }
    return false
}

private val SIGNAL_NAME_HELPERS = Catalog.default.signalNameHelpers.keys
private val JSON_CALLS =
    Catalog.default.signalCalls
        .filterValues { "json" in it }
        .keys
private val JSON_OBJECT = regex("""^\s*\{""")

/**
 * `toJsonValue(...)` handed to `dataSignals`: the page declares what the codec makes of the value.
 * The class is named by `toJsonValue<Form>(...)`, by a constructor call, `toJsonValue(Form(...))`,
 * or by a variable whose type the file writes down, `toJsonValue(form)` with `form: Form`.
 */
private val TO_JSON =
    regex(
        """\btoJsonValue\s*(?:<\s*([A-Za-z_][A-Za-z0-9_.]*)\s*>)?\s*\(\s*(?:(?:[a-z_][A-Za-z0-9_]*\.)*([A-Z][A-Za-z0-9_]*)\s*(?:<[^()]*>)?\s*\(|([a-z_][A-Za-z0-9_]*)\s*\))?""",
    )

/** A pair whose key is not a literal, `key to value`: the fields under it are no top-level signals. */
private val ANY_PAIR = regex("""^[^(]*?\bto\s""")
private val IDENTIFIER_ARG = regex("""^\s*([a-z_][A-Za-z0-9_]*)\s*$""")
private val BOOLEAN_ARG = regex("""^\s*(?:true|false)\s*$""")

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
internal fun encodedSignalTypes(
    src: String,
    foreign: (String, Int) -> Boolean = NOT_FOREIGN,
    lex: KotlinLex = lexKotlin(src),
): Set<String> {
    val out = LinkedHashSet<String>()
    val declarations by lazy(LazyThreadSafetyMode.NONE) { Declarations(src) }
    for (site in findCallSites(src, setOf(KEYED_OR_OBJECT), lex)) {
        if (foreign(site.name, site.nameStart)) continue
        val positional = site.positional
        // dataSignals(value) or dataSignals(value, true), the second being ifMissing.
        val a =
            positional.firstOrNull()?.takeIf {
                positional.size == 1 ||
                    (positional.size == 2 && BOOLEAN_ARG.matchEntire(positional[1].text) != null)
            }
                ?: site.named("signals")
                ?: continue
        if (a.string != null || PAIR.containsMatchIn(a.text) || ANY_PAIR.containsMatchIn(a.text)) continue
        encodedType(a.text, declarations, site.nameStart, depth = 0)?.let { out += it }
    }
    return out
}

/** The calls that patch signals from a value the codec encodes, on a stream and in one response. */
private val PATCH_CALLS =
    Catalog.default.signalCalls
        .filterValues { "value" in it }
        .keys

/** A call that writes signals as JSON text, which a patch sends whatever is in it. */
internal val JSON_TEXT = regex("""\.(?:writeValueAsString|encodeToString|toJson|toJsonString)\s*\(""")
private val CONSTRUCTED = regex("""^\s*(?:[a-z_][A-Za-z0-9_]*\.)*([A-Z][A-Za-z0-9_]*)\s*(?:<[^()]*>)?\s*\(""")

/**
 * The classes a server patch in [src] sets the signals of, by simple name, as [encodedSignalTypes]
 * gives them for a page: `patchSignals(Form(...))`, `respondSignals(form)` with `form: Form`,
 * `datastarSignals(codec.toJsonValue(...))`. A string, a pair or a map is read for its keys
 * elsewhere, and a variable whose class the file does not write down declares nothing.
 */
internal fun patchedSignalTypes(
    src: String,
    lex: KotlinLex,
): Set<String> {
    // The definitions and the facts of a file both ask: the answer is kept for the text last read.
    lastPatched?.let { (text, types) -> if (text === src) return types }
    return readPatchedTypes(src, lex).also { lastPatched = src to it }
}

@Volatile
private var lastPatched: Pair<String, Set<String>>? = null

private fun readPatchedTypes(
    src: String,
    lex: KotlinLex,
): Set<String> {
    val out = LinkedHashSet<String>()
    val declarations by lazy(LazyThreadSafetyMode.NONE) { Declarations(src) }
    for (site in findCallSites(src, PATCH_CALLS, lex)) {
        val a = site.named("value") ?: site.positional.firstOrNull() ?: continue
        if (a.string != null || PAIR.containsMatchIn(a.text) || MAP_CALL.containsMatchIn(a.text)) continue
        val type =
            encodedType(a.text, declarations, site.nameStart, depth = 0)
                ?: CONSTRUCTED
                    .find(a.text)
                    ?.groupValues
                    ?.get(1)
                    ?.let { if (it in VALUE_TYPES) null else named(it) }
                ?: IDENTIFIER_ARG.find(a.text)?.let { m ->
                    declarations.typeOf(m.groupValues[1], site.nameStart)?.let {
                        if (it == "String") {
                            ANY_SIGNAL
                        } else if (it in VALUE_TYPES) {
                            null
                        } else {
                            named(it)
                        }
                    }
                }
                ?: ANY_SIGNAL.takeIf { JSON_TEXT.containsMatchIn(a.text) }
        type?.let { out += it }
    }
    return out
}

/** What [text] declares, read with the source before [at], or null when it is no JSON value. */
private fun encodedType(
    text: String,
    declarations: Declarations,
    at: Int,
    depth: Int,
): String? {
    TO_JSON.find(text)?.let { m ->
        m.groups[1]?.let { return named(it.value.substringAfterLast('.')) }
        m.groups[2]?.let { return it.value }
        m.groups[3]?.let { id ->
            val type = declarations.typeOf(id.value, at) ?: return ANY_SIGNAL
            return if (type in VALUE_TYPES) null else named(type)
        }
        return ANY_SIGNAL
    }
    // dataSignals(signals), with `val signals = codec.toJsonValue(Form())` or `signals: JsonObject` before it.
    val id = IDENTIFIER_ARG.find(text)?.groupValues?.get(1) ?: return null
    if (depth > 0) return null
    val init = declarations.initialiserOf(id, at)
    if (init != null && TO_JSON.containsMatchIn(init)) return encodedType(init, declarations, at, depth + 1)
    return declarations.typeOf(id, at)?.takeIf { it in setOf("JsonValue", "JsonObject") }?.let { ANY_SIGNAL }
}

private val TYPED = regex("""\b([a-z_][A-Za-z0-9_]*)\s*:\s*(?:[a-z_][A-Za-z0-9_]*\.)*([A-Z][A-Za-z0-9_]*)""")
private val BUILT = regex("""\b(?:val|var)\s+([a-z_][A-Za-z0-9_]*)\s*=\s*(?:[a-z_][A-Za-z0-9_]*\.)*([A-Z][A-Za-z0-9_]*)\s*\(""")
private val INITIALISED = regex("""\b(?:val|var)\s+([a-z_][A-Za-z0-9_]*)\s*(?::[^=\n]*)?=\s*([^\n;]+)""")

/**
 * The variables [src] declares, by name, each with where the declaration ends: read once for a
 * file, and asked what a name was last declared as before an offset, at every `dataSignals` call.
 */
internal class Declarations(
    private val src: String,
) {
    private val typed = byName(TYPED.findAll(src))
    private val built = byName(BUILT.findAll(src))
    private val initialised = byName(INITIALISED.findAll(src))

    /** The class [id] is last declared with before [at], `form: Form` or `val form = Form(...)`. */
    fun typeOf(
        id: String,
        at: Int,
    ): String? = listOfNotNull(last(typed, id, at), last(built, id, at)).maxByOrNull { it.range.first }?.groupValues?.get(2)

    /** What [id] is last set to before [at], `val signals = codec.toJsonValue(Form())`. */
    fun initialiserOf(
        id: String,
        at: Int,
    ): String? {
        val found = initialised[id] ?: return null
        val k = found.binarySearch { if (it.range.first < at) -1 else 1 }.let { -it - 2 }
        val m = found.getOrNull(k) ?: return null
        if (m.range.last < at) return m.groupValues[2]
        // A declaration that runs on past [at] is read as far as the source before [at] goes.
        return (INITIALISED.find(src.substring(m.range.first, at)) ?: found.getOrNull(k - 1))?.groupValues?.get(2)
    }

    private fun byName(matches: Sequence<MatchResult>): Map<String, List<MatchResult>> = matches.groupBy { it.groupValues[1] }

    // The last match that ends before [at]: the matches of a name come in the order of the text.
    private fun last(
        index: Map<String, List<MatchResult>>,
        id: String,
        at: Int,
    ): MatchResult? {
        val found = index[id] ?: return null
        val after = found.binarySearch { if (it.range.last < at) -1 else 1 }.let { -it - 1 }
        return found.getOrNull(after - 1)
    }
}

private fun named(type: String): String = if (type in UNNAMED_TYPES) ANY_SIGNAL else type

/**
 * What the definitions say about a class, written with a `:`, which no signal name holds: a class
 * a page is declared from, a class a page or a patch sends with the classes its name may mean
 * (`sends:Form:app.Form|lib.Form`), a `@Serializable` class (`kotlinx:app.Form`), whose properties
 * are defined, a Jackson class with each of its properties (`jackson:app.Form`,
 * `jacksonProperty:app.Form:email`), a signal only where the class is sent, a class of the project
 * whose names the codec alone knows (`opaque:app.Form`), which takes part in Kotlin's lookup and
 * sends nothing the check can know, and a Jackson naming strategy, which leaves every Jackson class
 * to its codec. A class a page is declared from that the analysis cannot
 * read is one only its codec can name, and is marked readable (`class:Form`) only where one of the
 * classes its name may mean is (see [withClassProperties]).
 */
internal const val ENCODED_CLASS: String = "toJsonValue:"
internal const val SENT_CLASS: String = "sends:"
internal const val READABLE_CLASS: String = "class:"
internal const val KOTLINX_CLASS: String = "kotlinx:"
internal const val JACKSON_CLASS: String = "jackson:"
internal const val OPAQUE_CLASS: String = "opaque:"
internal const val RENAMED_CLASSES: String = "renamed:classes"
internal const val JACKSON_PROPERTY_OF: String = "jacksonProperty:"

/**
 * A name a DSL call or core declares with a hyphen, which Streamlord refuses at render or send time,
 * so no page has it: it counts as defined, as the call says, and is no name to complete.
 */
internal const val REFUSED: String = "refused:"

/** The argument as one pair, in parentheses or not; a `"k" to` further in is a key inside the value. */
private val PAIR = regex("""^[\s(]*"([A-Za-z_][A-Za-z0-9_.-]*)"\s+to\b""")
private val NAME = regex("""^"([A-Za-z_][A-Za-z0-9_.-]*)"$""")

/** The calls that build a map in place, `mapOf("count" to 0)`: handed over as the signals, its own pairs are the keys. */
internal val MAP_CALLS = setOf("mapOf", "mutableMapOf", "hashMapOf", "linkedMapOf")
private val MAP_CALL = regex("""^\s*(?:mutable|hash|linked)?[mM]apOf\s*\(""")

/** A pair written in place, `"key" to value`: its key, and its value where that is one literal, at their offsets in the text read. */
internal class LiteralPair(
    val key: KotlinString,
    val value: KotlinString?,
)

private val TO_AFTER = regex("""^\s+to\b\s*""")

/** The pairs of [text]: the pair it is, or each pair of the map it builds in place, `mapOf("k" to v)`; nothing inside a value. */
internal fun literalPairs(text: String): List<LiteralPair> {
    val lex = lexKotlin(text)

    fun pair(
        start: Int,
        end: Int,
    ): LiteralPair? {
        val strings = lex.stringsIn(start, end)
        val key =
            strings.firstOrNull()?.takeIf { k -> text.substring(start, k.start).all { it.isWhitespace() || it == '(' } } ?: return null
        val to = TO_AFTER.find(text.substring(key.end, end)) ?: return null
        val value =
            strings.getOrNull(1)?.takeIf { v ->
                v.start == key.end + to.value.length && text.substring(v.end, end).all { it.isWhitespace() || it == ')' }
            }
        return LiteralPair(key, value)
    }
    if (!MAP_CALL.containsMatchIn(text)) return listOfNotNull(pair(0, text.length))
    val map = findCallSites(text, MAP_CALLS, lex).firstOrNull() ?: return emptyList()
    return map.positional.mapNotNull { pair(it.start, it.end) }
}

/** The literal keys of an argument that is a pair, or a map of pairs, with offsets in [text]; nothing inside a value. */
private fun argumentKeys(text: String): List<SignalDeclaration> =
    literalPairs(text).mapNotNull { pair ->
        pair.key
            .takeIf { k -> !k.raw && NAME.matchEntire(text.substring(k.start, k.end)) != null }
            ?.let { k -> SignalDeclaration(k.contentStart, k.contentEnd, k.text) }
    }

/** A group of a match with where it stands, which a `MatchGroup` says on the JVM alone. */
internal class GroupAt(
    val value: String,
    val range: IntRange,
)

internal fun Regex.groupAt(
    input: CharSequence,
    match: MatchResult,
    group: Int,
): GroupAt? = match.groups[group]?.let { GroupAt(it.value, groupRange(input, match, group)!!) }

private fun declarationAt(
    group: GroupAt,
    offset: Int,
    name: String = group.value,
): SignalDeclaration = SignalDeclaration(offset + group.range.first, offset + group.range.last + 1, name)

/**
 * A place where a source defines a signal: [name] as the client holds it, and the offsets of the
 * name as it is written there, without its quotes or modifiers. `data-signals:my-value__case.snake`
 * defines `my_value`, written `my-value`; under `@SerialName("page_no") val page` the name is `page_no`.
 * [kind] says how a new name is written there (see [signalRenameText]).
 */
public data class SignalDeclaration(
    val start: Int,
    val end: Int,
    val name: String,
    val kind: Kind = Kind.NAME,
) {
    public enum class Kind {
        /** The name as it stands: a pair, a helper's argument, the value form, an assignment, a serial name. */
        NAME,

        /** An attribute's key, which the client lowercases and recases by the modifiers after it. */
        KEY,

        /** A key of an object, in the object form or in JSON: the last part of the path. */
        OBJECT_KEY,

        /** A property of a signals class, which Kotlin's own rename renames. */
        PROPERTY,
    }
}

/**
 * The signals [source] names. In a Kotlin file that cannot call streamlord-html, the DSL's short
 * helpers are somebody else's and name no signal: their names, `set` and `toggle`, are other APIs' too.
 */
public fun collectSignals(source: Source): Set<String> {
    if (source.language == SourceLanguage.SETTINGS) return emptySet()
    val foreign = foreignHelpers(source)
    val src = source.text
    val language = source.language
    val out = LinkedHashSet(collect(src, language, reads = true, { name, _ -> name in foreign }, source.prefix))
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
internal fun collectDeclaredSignals(
    src: String,
    language: SourceLanguage,
    prefix: String,
): Set<String> = collect(src, language, reads = true, NOT_FOREIGN, prefix)

/**
 * Whether [src] writes signals whose names are known only at run time: a `data-signals` a template
 * writes, Thymeleaf's `th:data-signals` or `th:attr`, a key with template syntax in it, or a value
 * that is no object (`data-signals="{{ signals | raw }}"`, `"${'$'}{json}"`), in markup or in an HTML
 * string of Kotlin; in Kotlin also `dataSignals(json)` or `attributes["data-signals"] = json` with
 * JSON text, and a patch of JSON written at run time. A mention in a comment or in text writes none.
 */
internal fun writesSignalsAtRunTime(
    src: String,
    language: SourceLanguage,
    prefix: String,
    lex: KotlinLex? = null,
    patched: Set<String>? = null,
): Boolean {
    if (!src.contains("signals", ignoreCase = true)) return false
    if (language == SourceLanguage.HTML) return tagsWriteSignals(src, prefix, templates = true)
    if (language != SourceLanguage.KOTLIN) return false
    val code = lex ?: lexKotlin(src)
    val inStrings =
        code.strings.any {
            it.text.contains("signals", ignoreCase = true) &&
                tagsWriteSignals(it.text, prefix, templates = false)
        }
    if (inStrings || ANY_SIGNAL in (patched ?: patchedSignalTypes(src, code))) return true
    val declarations by lazy(LazyThreadSafetyMode.NONE) { Declarations(src) }
    for (site in findCallSites(src, setOf("dataSignals"), code)) {
        val a = site.positional.singleOrNull() ?: continue
        if (a.string != null) continue
        val text = a.text.trim()
        if (JSON_TEXT.containsMatchIn(text)) return true
        if (IDENTIFIER_ARG.matchEntire(text) != null && declarations.typeOf(text, site.nameStart) == "String") return true
    }
    return ATTRIBUTES_SET.findAll(src).any { m ->
        code.mask[m.range.first] &&
            src.getOrNull(m.range.last + 1).let { it != null && it != '"' }
    }
}

// `attributes["data-signals"] = ` with what follows it, the kotlinx.html way to write any attribute.
private val ATTRIBUTES_SET = regex("""\battributes\s*\[\s*"[\w-]*signals"\s*\]\s*=\s*""")

/**
 * Whether a start tag of [html] writes signals whose names are known only at run time (see
 * [writesSignalsAtRunTime]): read in one pass that keeps to start tags, past comments, scripts and
 * styles, and looks only where `signals` stands.
 */
private fun tagsWriteSignals(
    html: String,
    prefix: String,
    templates: Boolean,
): Boolean {
    if (!html.contains("signals", ignoreCase = true)) return false
    val text = withoutComments(html.lowercase(), templates)
    for (tag in startTags(text, templates)) {
        var at = text.indexOf("signals", tag.first)
        while (at in tag) {
            if (writesAt(text, at, tag.first, prefix)) return true
            at = text.indexOf("signals", at + 7)
        }
    }
    return false
}

/** [lower] with each comment blanked out, a template's too with [templates], every offset kept. */
private fun withoutComments(
    lower: String,
    templates: Boolean,
): String {
    val pairs = if (templates) COMMENTS + TEMPLATE_COMMENTS else COMMENTS
    if (pairs.none { lower.contains(it.first) }) return lower
    val chars = lower.toCharArray()
    for ((open, close) in pairs) {
        var at = lower.indexOf(open)
        while (at >= 0) {
            // `{{#if}}` opens a Mustache section, not a `{#` comment.
            if (open == "{#" && at > 0 && lower[at - 1] == '{') {
                at = lower.indexOf(open, at + 1)
                continue
            }
            val end = lower.indexOf(close, at + open.length).let { if (it < 0) lower.length else it + close.length }
            for (k in at until end) chars[k] = ' '
            at = lower.indexOf(open, end)
        }
    }
    return chars.concatToString()
}

/** Where a template construct that starts at [at] of [text] ends, or null for none there: a directive is read from the space before it. */
private fun constructEnd(
    text: String,
    at: Int,
): Int? {
    val from = if ((text[at] == '@' || text[at] == '#') && at > 0 && text[at - 1].isWhitespace()) at - 1 else at
    val m = TEMPLATE_SYNTAX.matchAt(text, from) ?: return null
    var s = m.range.first
    while (s < m.range.last && (text[s].isWhitespace() || text[s] == '>')) s++
    return placeholderEnd(text, s).takeIf { it > at }
}

private const val CONSTRUCT_STARTS = "{<[$!*#@"

private val COMMENTS = listOf("<!--" to "-->")
private val TEMPLATE_COMMENTS = listOf("{#" to "#}", "<#--" to "-->", "{{!" to "}}", "<%--" to "--%>")

/** The start tags of [text], each from its `<` to its `>`, a quoted `>` inside it; a script's or a style's body is passed over. */
private fun startTags(
    text: String,
    templates: Boolean,
): List<IntRange> {
    val out = ArrayList<IntRange>()
    var i = text.indexOf('<')
    while (i >= 0 && i < text.length) {
        if (i + 1 >= text.length || !text[i + 1].isLetter()) {
            i = text.indexOf('<', i + 1)
            continue
        }
        var j = i + 1
        var quote = ' '
        while (j < text.length) {
            val c = text[j]
            if (quote != ' ') {
                if (c == quote) quote = ' '
            } else if (c == '"' || c == '\'') {
                quote = c
            } else if (templates && c in CONSTRUCT_STARTS) {
                // A template's own construct in a tag, `<#if on>` or `@if(a > 1)`, ends no tag with its `>`.
                val end = constructEnd(text, j)
                if (end != null) {
                    j = end
                    continue
                }
            } else if (c == '>') {
                break
            }
            j++
        }
        out += i..minOf(j, text.length - 1)
        var end = i + 1
        while (end < text.length && (text[end].isLetterOrDigit() || text[end] == '-')) end++
        val name = text.substring(i + 1, end)
        val next = if (name == "script" || name == "style") text.indexOf("</$name", j) else j
        i = if (next < 0) -1 else text.indexOf('<', next + 1)
    }
    return out
}

/** Whether the `signals` at [at], in the start tag from [tagStart], names an attribute that writes signals at run time. */
private fun writesAt(
    text: String,
    at: Int,
    tagStart: Int,
    prefix: String,
): Boolean {
    var start = at
    while (start > tagStart && text[start - 1].let { it.isLetterOrDigit() || it == ':' || it == '-' || it == '_' }) start--
    val after = at + "signals".length
    val name = text.substring(start, after)
    val signals = prefix + "signals"
    if (name == "th:$signals" || name == "data-th-$signals") return true
    var i = after
    // `th:attr="data-signals=${'$'}{...}"`: Thymeleaf writes each attribute the list names.
    if (name.endsWith(signals) && THYMELEAF_ATTR_OPEN.containsMatchIn(text.substring(tagStart, start))) {
        while (i < text.length && text[i].isWhitespace()) i++
        return text.startsWith("=", i)
    }
    if (name != signals) return false
    if (text.startsWith("__", i)) {
        while (i < text.length && text[i] != '=' && text[i] != ':' && !text[i].isWhitespace() &&
            text[i] != '>'
        ) {
            i++
        }
    }
    if (text.startsWith(":", i)) return KEY_OPENERS.any { text.startsWith(it, i + 1) }
    while (i < text.length && text[i].isWhitespace()) i++
    if (!text.startsWith("=", i)) return false
    i++
    while (i < text.length && text[i].isWhitespace()) i++
    if (text.startsWith("\\", i)) i++
    val quoted = text.getOrNull(i) == '"' || text.getOrNull(i) == '\''
    if (quoted) i++
    while (i < text.length && text[i] == ' ') i++
    val c = text.getOrNull(i) ?: return false
    if (c == '"' || c == '\'' || c == '\\' || c == '>' || c.isWhitespace()) return false
    return c != '{' || text.startsWith("{{", i) || text.startsWith("{%", i)
}

// A `th:attr` whose quoted value is still open: the attributes it lists are Thymeleaf's to write.
private val THYMELEAF_ATTR_OPEN = regex("""th:attr\s*=\s*\\?["'][^"']*$""")

private val KEY_OPENERS = listOf("\${", "{{", "{%", "<%", "[[", "th:", PLACEHOLDER)

/**
 * The signals this source defines, which a `$name` in an expression is checked against: what
 * [collectDeclaredSignals] finds without the reads (a bare `$name` in markup, a `${'$'}name` in
 * Kotlin), plus the properties of `@Serializable` classes, which a handler patches as well as
 * reads, and every signal an expression assigns to.
 *
 * Some entries are no signal names, and hold a `:` so that none is mistaken for one: the marks of
 * the classes a page is declared from or a codec writes (see [ENCODED_CLASS]), and `refused:is-open`
 * for a name Streamlord refuses. [withClassProperties] and [isKnownSignal] read them together.
 */
public fun collectSignalDefinitions(source: Source): Set<String> {
    if (source.language == SourceLanguage.SETTINGS) return if (settingsRenameSignals(source.text)) setOf(RENAMED_CLASSES) else emptySet()
    val foreign = foreignHelpers(source)
    return collectSignalDefinitions(source.text, source.language, { name, _ -> name in foreign }, prefix = source.prefix)
}

/**
 * [collectSignalDefinitions] with the calls that are not Streamlord's left out, as [foreign] says
 * by the name of each and where the name starts: they define no signal.
 */
internal fun collectSignalDefinitions(
    src: String,
    language: SourceLanguage,
    foreign: (name: String, nameStart: Int) -> Boolean,
    given: KotlinLex? = null,
    prefix: String,
): Set<String> {
    val out = LinkedHashSet<String>()
    val spread = Spread()
    // One reading of a Kotlin file for every call looked for in it.
    val lex = given ?: if (language == SourceLanguage.KOTLIN) lexKotlin(src) else null
    val refused = HashSet<SignalDeclaration>()
    for (d in definedAt(src, language, spread, foreign, lex, prefix, refused)) out += if (d in refused) REFUSED + d.name else d.name
    if (spread.any) out += ANY_SIGNAL
    if (language == SourceLanguage.KOTLIN) {
        val own = packageOf(src, lex!!)
        // A class is known by its package too, so that another of its name is not taken for it.
        for (c in signalClasses(src, lex)) {
            val qualified = if (own.isEmpty()) c.name else "$own.${c.name}"
            if (c.serializable) {
                out += KOTLINX_CLASS + qualified
            } else if (c.readable) {
                out += JACKSON_CLASS + qualified
                for (p in c.properties) out += "$JACKSON_PROPERTY_OF$qualified:${p.name}"
            } else {
                out += OPAQUE_CLASS + qualified
            }
        }
        // A page declared from a class defines what its codec writes: the properties for a class
        // the analysis reads, any name for one it cannot read, or for a value of no named class.
        val sent = LinkedHashSet<String>()
        for (type in encodedSignalTypes(src, foreign, lex)) {
            out += if (type == ANY_SIGNAL) ANY_SIGNAL else ENCODED_CLASS + type
            if (type != ANY_SIGNAL) sent += type
        }
        for (type in patchedSignalTypes(src, lex)) if (type != ANY_SIGNAL) sent += type
        if (sent.isNotEmpty()) {
            val candidates = candidateClasses(src, lex)
            for (type in sent) out += "$SENT_CLASS$type:${candidates.of(type).joinToString("|")}"
        }
        // A Jackson naming strategy leaves every Jackson class's names to the codec.
        if (codeRenamesSignals(src, lex)) out += RENAMED_CLASSES
    }
    return out
}

/**
 * Every place [src] defines a signal that [collectSignalDefinitions] names, in source order: what
 * go to declaration lands on and a rename rewrites. A spread and a codec define signals at no place
 * that names them, so neither is here.
 */
public fun signalDeclarations(source: Source): List<SignalDeclaration> {
    if (source.language == SourceLanguage.SETTINGS) return emptyList()
    val foreign = foreignHelpers(source)
    return definedAt(source.text, source.language, Spread(), { name, _ -> name in foreign }, prefix = source.prefix).sortedWith(
        compareBy({
            it.start
        }, { it.end }),
    )
}

/**
 * A name a rename may write: dotted parts of ASCII letters, digits and `_`, not opening with a
 * digit. What a `$name` reads and what a Kotlin string holds without a template or a name the DSL
 * refuses: a `$` would be a template in Kotlin and `-` throws in a DSL call.
 */
private val RENAMABLE = regex("""[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)*""")

/** The characters of a key and its modifiers, as [KEYED_ATTRIBUTE] reads them. */
private val KEY_TAIL = regex("""[\p{L}\p{N}_.-]*""")

/**
 * What to write at [d] in [src] so that the place defines [newName], or null when nothing written
 * there can: a property of a signals class, which Kotlin's rename renames; a key of an object
 * asked to move under another object; a bare key that would need quotes; or an attribute key whose
 * modifiers turn every spelling of [newName] into another name. An attribute key keeps its
 * modifiers and is spelled as the old one was, kebab-case for kebab-case.
 */
public fun signalRenameText(
    src: String,
    d: SignalDeclaration,
    newName: String,
): String? {
    if (RENAMABLE.matchEntire(newName) == null) return null
    return when (d.kind) {
        SignalDeclaration.Kind.NAME -> {
            newName
        }

        SignalDeclaration.Kind.PROPERTY -> {
            null
        }

        SignalDeclaration.Kind.OBJECT_KEY -> {
            val parent = d.name.substringBeforeLast('.', "")
            if (newName.substringBeforeLast('.', "") != parent) return null
            // A quoted key with a dot in it is one part, `'a.b'`, which the path's last part is not.
            if ('.' in src.substring(d.start, d.end)) return null
            newName.substringAfterLast('.')
        }

        SignalDeclaration.Kind.KEY -> {
            val modifiers = KEY_TAIL.matchAt(src, d.end)?.value ?: ""
            val kebab = '-' in src.substring(d.start, d.end)
            val spellings =
                if (kebab) {
                    listOf(
                        newName.replace('_', '-'),
                        ClientCasing.kebab(newName),
                        newName,
                    )
                } else {
                    listOf(newName, ClientCasing.kebab(newName))
                }
            spellings.firstOrNull { ClientCasing.readKey(it + modifiers) == newName }
        }
    }
}

/**
 * What to write over the last part of a read of [signal], `name` in `$user.name` for `user.name`,
 * when the signal is renamed [newName], or null when it cannot be: a read names its signal part by
 * part, so a rename keeps the signal under the object it is in.
 */
public fun signalReadRenameText(
    signal: String,
    newName: String,
): String? {
    if (RENAMABLE.matchEntire(newName) == null || newName.substringBeforeLast('.', "") != signal.substringBeforeLast('.', "")) return null
    return newName.substringAfterLast('.')
}

private fun definedAt(
    src: String,
    language: SourceLanguage,
    spread: Spread,
    foreign: (String, Int) -> Boolean = NOT_FOREIGN,
    lex: KotlinLex? = null,
    prefix: String = CatalogNames.PREFIX,
    refused: MutableSet<SignalDeclaration>? = null,
): List<SignalDeclaration> {
    val out = ArrayList(declared(src, language, spread, foreign, lex, prefix, refused))
    if (language == SourceLanguage.KOTLIN) {
        for (properties in serializableProperties(src, lex ?: lexKotlin(src)).values) {
            // Under @SerialName the name is the annotation's string, which a rename may rewrite.
            for (p in properties) {
                val kind = if (src.getOrNull(p.start - 1) == '"') SignalDeclaration.Kind.NAME else SignalDeclaration.Kind.PROPERTY
                out += SignalDeclaration(p.start, p.end, p.name, kind)
            }
        }
    }
    // In Kotlin a dollar that opens a template writes a Kotlin value, which is assigned nothing.
    val templates by lazy(LazyThreadSafetyMode.NONE) {
        if (language != SourceLanguage.KOTLIN) {
            emptyList()
        } else {
            withNested((lex ?: lexKotlin(src)).strings).flatMap { s -> s.interpolations.filter { it.kind == InterpolationKind.SIMPLE } }
        }
    }
    // Nor is an assignment written in a comment, `// $count++`, a page's.
    val comments by lazy(LazyThreadSafetyMode.NONE) {
        if (language == SourceLanguage.KOTLIN) codeMask(src, lex ?: lexKotlin(src)).second else BooleanArray(0)
    }
    for (m in ASSIGNED_SIGNAL.findAll(src)) {
        val name = ASSIGNED_SIGNAL.groupAt(src, m, 1) ?: ASSIGNED_SIGNAL.groupAt(src, m, 2)!!
        val at = name.range.first - 1
        if (comments.getOrElse(at) { false }) continue
        if (templates.none { at >= it.start && at < it.end }) out += declarationAt(name, 0)
    }
    return out.filter { PLACEHOLDER !in it.name }.distinct()
}

/**
 * The places the markup attributes and the DSL calls of [src] define a signal, which a page puts
 * there: no `@Serializable` property and no assignment. [spread] is set when an object form
 * spreads a variable into itself, which may define any signal. [refused] collects the names a
 * call writes with a hyphen, which signal-name-hyphen reports.
 */
private fun declared(
    src: String,
    language: SourceLanguage,
    spread: Spread,
    foreign: (String, Int) -> Boolean = NOT_FOREIGN,
    given: KotlinLex? = null,
    prefix: String = CatalogNames.PREFIX,
    refused: MutableSet<SignalDeclaration>? = null,
): List<SignalDeclaration> {
    val attributes = declaring(prefix)
    val out = ArrayList<SignalDeclaration>()

    fun named(d: SignalDeclaration) {
        out += d
        if ('-' in d.name) refused?.add(d)
    }
    if (language == SourceLanguage.KOTLIN) {
        val lex = given ?: lexKotlin(src)
        for (site in findCallSites(src, PAIR_CALLS, lex)) {
            if (foreign(site.name, site.nameStart)) continue
            val removes = removesSignals(src, lex.mask, site)
            // A companion's removal is called by a name others use, `names.remove(...)`.
            if (site.name !in Catalog.default.signalCalls && !removes) continue
            val positional = site.positional
            positional.forEachIndexed { idx, a ->
                for (key in argumentKeys(a.text)) {
                    val d = key.copy(start = a.start + key.start, end = a.start + key.end)
                    if (site.name in PAIR_NAMING || removes) named(d) else out += d
                }
                // patchSignals("""{"count": 1}"""): the signals as JSON text.
                if (site.name in JSON_CALLS) {
                    val s = a.string
                    s?.text?.takeIf { JSON_OBJECT.containsMatchIn(it) }?.let { json ->
                        val open = json.indexOf('{') + 1
                        for (k in objectKeys(json.substring(open, maxOf(open, json.lastIndexOf('}'))), spread)) {
                            val at = s.toSource(open + k.nameStart, open + k.nameStart + k.key.length)
                            out += SignalDeclaration(at.first, at.last + 1, k.path, SignalDeclaration.Kind.OBJECT_KEY)
                        }
                    }
                }
                // dataSignals("{quests: {urgent: 0}}") writes data-signals="{...}": its keys are the signals.
                if (site.name == KEYED_OR_OBJECT && idx == 0 && !site.keyed) {
                    a.string?.let { out += objectDeclarations(it, "") }
                }
                val name = NAME.find(a.text)?.let { NAME.groupAt(a.text, it, 1) } ?: return@forEachIndexed
                // removeSignals("a", "b"), and dataSignals("name", "expression"), whose key the client reads back as the name.
                if (removes) named(declarationAt(name, a.start))
                if (site.name == KEYED_OR_OBJECT && idx == 0 && site.keyed) {
                    named(declarationAt(name, a.start))
                    // dataSignals("hero", "{strength: 3}") holds an object under the name, whose keys are signals too.
                    out += objectDeclarations(positional[1].string!!, "${name.value}.")
                }
            }
        }
        // A call is code: the name of a helper in a comment or a string is none, in a template's code it is.
        val code by lazy(LazyThreadSafetyMode.NONE) { codeMask(src, lex).first }
        for (re in KOTLIN_PATTERNS) {
            for (m in re.findAll(src)) {
                // Each pattern starts at the name of the call it reads.
                val name = m.value.substringBefore('(')
                if (!code[m.range.first] || foreign(name, m.range.first)) continue
                // A helper called on a receiver, `headers.set("Cache-Control", ...)`, is not the top-level helper.
                if (name in SIGNAL_NAME_HELPERS && onReceiver(src, m.range.first)) continue
                named(declarationAt(re.groupAt(src, m, 1)!!, 0))
            }
        }
    }
    // A keyed attribute may carry modifiers after the key: data-signals:foo-bar__ifmissing, and
    // `__case` names the signal: data-signals:my-value__case.snake is `$my_value`.
    for (m in attributes.keyed.findAll(src)) {
        val written = attributes.keyed.groupAt(src, m, 1)!!
        val key = written.value.substringBefore("__")
        val name = ClientCasing.readKey(written.value)
        out += SignalDeclaration(written.range.first, written.range.first + key.length, name, SignalDeclaration.Kind.KEY)
        // An object as the value is merged in under the key, its own keys as written: data-signals:hero="{strength: 1}".
        val body = KEYED_OBJECT.matchAt(src, m.range.last + 1)?.let { KEYED_OBJECT.groupAt(src, it, 1) ?: KEYED_OBJECT.groupAt(src, it, 2) }
        if (body != null && attributes.signals(m.value)) {
            for (k in objectKeys(body.value)) {
                val start = body.range.first + k.nameStart
                out += SignalDeclaration(start, start + k.key.length, "$name.${k.path}", SignalDeclaration.Kind.OBJECT_KEY)
            }
        }
    }
    for (m in attributes.value.findAll(src)) {
        out += declarationAt(attributes.value.groupAt(src, m, 1) ?: attributes.value.groupAt(src, m, 2)!!, 0)
    }
    for (m in attributes.objectForm.findAll(src)) {
        val body = attributes.objectForm.groupAt(src, m, 1) ?: attributes.objectForm.groupAt(src, m, 2)!!
        for (k in objectKeys(body.value, spread)) {
            val start = body.range.first + k.nameStart
            out += SignalDeclaration(start, start + k.key.length, k.path, SignalDeclaration.Kind.OBJECT_KEY)
        }
    }
    // The client applies no attribute of an element data-ignore covers, so it declares nothing there.
    if (language == SourceLanguage.KOTLIN) {
        val lex = given ?: lexKotlin(src)
        val ignored = dslIgnored(src, lex, foreign, prefix)
        // And in the HTML strings, the elements data-ignore covers, at their offsets in the file.
        val inStrings =
            lex.strings
                .filter { !it.unterminated && it.text.contains(prefix + "ignore", ignoreCase = true) }
                .flatMap { s -> ignoredTags(s.text, prefix, templates = false).map { s.toSource(it.first, it.last) } }
        // Markup written in a comment, `// <div data-signals:count="1">`, is no page's.
        val comments = codeMask(src, lex).second
        out.removeAll { d -> comments.getOrElse(d.start) { false } || ignored(d.start) || inStrings.any { d.start in it } }
    } else if (src.contains(prefix + "ignore", ignoreCase = true)) {
        val skipped = ignoredTags(src, prefix)
        out.removeAll { d -> skipped.any { d.start in it } }
    }
    return out
}

/** The text of each start tag of [html] the client applies nothing of, as [appliedTags] reads them. */
private fun ignoredTags(
    html: String,
    prefix: String,
    templates: Boolean = true,
): List<IntRange> {
    val tags = tokenize(html, templates).tags
    val applied = appliedTags(tags, prefix).associateBy { it.start }
    return tags.filter { !it.closing && applied[it.start]?.attributes?.size != it.attributes.size }.map { it.start until it.end }
}

/** The keys of the object literal [s] holds, each a signal under [under], where it is written; none when [s] holds no object. */
private fun objectDeclarations(
    s: KotlinString,
    under: String,
): List<SignalDeclaration> {
    if (!JSON_OBJECT.containsMatchIn(s.text)) return emptyList()
    val open = s.text.indexOf('{') + 1
    return objectKeys(s.text.substring(open, maxOf(open, s.text.lastIndexOf('}')))).map { k ->
        val at = s.toSource(open + k.nameStart, open + k.nameStart + k.key.length)
        SignalDeclaration(at.first, at.last + 1, under + k.path, SignalDeclaration.Kind.OBJECT_KEY)
    }
}

private fun collect(
    src: String,
    language: SourceLanguage,
    reads: Boolean,
    foreign: (String, Int) -> Boolean,
    prefix: String,
): Set<String> {
    val out = LinkedHashSet<String>()
    val spread = Spread()
    for (d in declared(src, language, spread, foreign, prefix = prefix)) out += d.name
    if (spread.any) out += ANY_SIGNAL
    if (language == SourceLanguage.KOTLIN) {
        val lex by lazy(LazyThreadSafetyMode.NONE) { lexKotlin(src) }
        // Where the code is, a template's included, and the comments: what a comment writes is read by no page.
        val masks by lazy(LazyThreadSafetyMode.NONE) { codeMask(src, lex) }
        if (reads) {
            for (re in KOTLIN_READS) {
                for (m in re.findAll(src)) {
                    if (!masks.second[m.range.first]) out += m.groupValues[1]
                }
            }
        }
        // In a `$$"..."` literal a shorter run of dollars is text, as an escaped one is elsewhere: `$count` reads a signal.
        if (reads) {
            for (s in withNested(lex.strings)) {
                if (s.dollars < 2) continue
                for (m in BARE_SIGNAL.findAll(src.substring(s.contentStart, s.contentEnd))) {
                    val at = s.contentStart + m.range.first
                    if (s.interpolations.none { at >= it.start && at < it.end }) out += m.groupValues[1]
                }
            }
        }
        if (reads) {
            // A call is code: the name of a helper in a comment or a string is none, in a template's code it is.
            for (m in HELPER_READS.findAll(src)) {
                if (!masks.first[m.range.first] || foreign(m.value.substringBefore('('), m.range.first) ||
                    onReceiver(src, m.range.first)
                ) {
                    continue
                }
                out += m.groupValues[1]
            }
        }
    }
    if (reads && language == SourceLanguage.HTML) {
        for (m in BARE_SIGNAL.findAll(src)) out += m.groupValues[1]
    }
    // The placeholder the analysis writes for a Kotlin template is never a signal, whatever file it turns up in.
    out.removeAll { PLACEHOLDER in it }
    // What a spread may declare is no name to complete.
    if (reads) out.remove(ANY_SIGNAL)
    return out
}

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
        .firstOrNull { (i, p) -> p == "__proto__" || (p in SignalNames.PROTOTYPE_NAMES && (i < parts.lastIndex || objectUnder)) }
        ?.let { (i, p) ->
            if (p != "__proto__" && i == parts.lastIndex) {
                "has the part $p, which every object already has: the client holds the signal, but the next request " +
                    "writes the object under it into the built-in one and sends the page's signals without it"
            } else {
                "has the part $p, which every object already has, so the client writes into the built-in one instead of holding a signal"
            }
        }
}

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
    // What the client reads after a `$`: ASCII letters, digits and `_`, in parts joined by `.` or `-`.
    if (CLIENT_SIGNAL.matchEntire("$" + name) != null) return null
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
    /** Where [key] itself starts, inside its quotes if it has any. */
    val nameStart: Int = start,
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
            var nameStart = i
            val escaped = body.getOrNull(i) == '\\' && (body.getOrNull(i + 1) == '"' || body.getOrNull(i + 1) == '\'')
            val quote = if (escaped) body.getOrNull(i + 1) else body.getOrNull(i)
            if (quote == '"' || quote == '\'') {
                val from = i + if (escaped) 2 else 1
                nameStart = from
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
                out += ObjectKey(parts.joinToString("."), parts, key, keyStart, keyEnd, i, objectFor != null, nameStart)
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

private val BARE_KEY = regex("""[A-Za-z0-9_$]+""")

private val PAIR_KEY = regex("""^[\s(]*"""")
private val PAIR_AFTER = regex("""\s+to\b""")
private val TO_NULL = regex("""\s+to\s+null\b""")

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
    if (site.name != KEYED_OR_OBJECT) return emptyList()
    return pairKeys(site, src, lex).mapNotNull { key ->
        // A templated name is known only at runtime.
        signalNameRefusal(key.text)?.takeIf { key.interpolations.isEmpty() }?.let { reason ->
            return@mapNotNull Issue(
                start = key.start,
                end = key.end,
                message = "Streamlord refuses \"${key.text}\" at runtime: it $reason.",
                severity = Severity.ERROR,
                code = IssueCode.KEY_REFUSED,
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
                "Datastar's client holds no null: a null among the signals removes the key, so \"${key.text}\" is not declared, " +
                    "and an expression that reads it gets \"\". Declare it with the value it starts as, such as \"\".",
            severity = Severity.WARNING,
            code = IssueCode.SIGNAL_NULL,
            link = Docs.SIGNALS,
        )
    }
}

/**
 * The strings the DSL's calls name a signal with in [source]: the first argument of `dataBind`,
 * `dataRef` and `dataIndicator`, and of `dataSignals` and `dataComputed` where a string expression
 * follows it. None where the file cannot call the DSL, nor for a call [Source.foreign] says is
 * somebody else's.
 */
internal fun Analyzer.signalNameStrings(source: Source): List<KotlinString> {
    if (!readsDsl(source)) return emptyList()
    val src = source.text
    return findCallSites(src, catalog.signalNameCalls, lex(src)).mapNotNull { site ->
        val named = (site.name !in catalog.signalPairCalls || site.keyed) && !source.foreign(site.name, site.nameStart)
        site.positional
            .firstOrNull()
            ?.string
            ?.takeIf { named }
    }
}

/** Whether a string follows the first argument of a pair call, which makes the first a signal's name: `dataSignals("name", "...")`. */
internal val CallSite.keyed: Boolean get() = positional.getOrNull(1)?.string != null
