package io.github.markusaugust.streamlord.analysis

import io.github.markusaugust.streamlord.core.client.regex
import kotlin.concurrent.Volatile

/*
 * The classes a codec writes signals from, read off their declarations: a `@Serializable` class
 * is kotlinx.serialization's, any other Jackson's. A class is read only where its declaration
 * holds every name the codec writes; any other is left to the codec, which alone knows them.
 */

/**
 * A class of the file, by its simple name: whether it is `@Serializable`, whether its names can be
 * read off it, and the constructor properties that name its signals where they can.
 */
internal class SignalClass(
    val name: String,
    val serializable: Boolean,
    val readable: Boolean,
    val properties: List<SignalProperty>,
)

/** An annotation, by its simple name without a use-site target, and the text between its parentheses. */
private class Annotation(
    val name: String,
    val arguments: String?,
    val argumentsStart: Int,
)

/** The annotations and modifiers that stand before a declaration. */
private class Header(
    val annotations: List<Annotation>,
    val modifiers: Set<String>,
)

private val CLASS = regex("""\bclass\s+([A-Za-z_][A-Za-z0-9_]*)\s*(<)?""")
private val OTHER_TYPE = regex("""\b(?:interface|typealias)\s+([A-Za-z_][A-Za-z0-9_]*)""")
private val PROPERTY = regex("""\b(?:val|var)\s+([A-Za-z_][A-Za-z0-9_]*)\s*:""")
private val BLANK_LINE = regex("""\n[ \t\r]*\n""")

/** The text of a string argument: the first positional one, or the one named `value`. */
private val NAME_ARGUMENT = regex("""^\s*"([^"]*)"|\bvalue\s*=\s*"([^"]*)"""")

// The kinds of class whose constructor holds no signals, and the modifiers a header may hold.
private val NO_SIGNALS =
    setOf("enum", "annotation", "value", "inline", "sealed", "abstract", "inner", "interface", "fun", "expect", "external")
private val KOTLINX_KINDS = setOf("value", "inline", "sealed", "abstract")
private val NO_OBJECT = setOf("enum", "annotation", "expect")
private val MODIFIERS =
    NO_SIGNALS +
        setOf("public", "internal", "private", "protected", "open", "final", "data", "actual", "override", "lateinit", "const", "vararg")

// What a Jackson class says that leaves its names to its codec: a strategy, names left out or
// read under others, a type written in, a property unwrapped or read or written alone.
private val JACKSON_CLASS_OWN = setOf("JsonNaming", "JsonIgnoreProperties", "JsonTypeInfo", "JsonIncludeProperties", "JsonRootName")
private val JACKSON_PROPERTY_OWN = setOf("JsonAlias", "JsonUnwrapped", "JsonAnySetter", "JsonAnyGetter", "JsonValue", "JsonCreator")

/** Every class in [src] with a constructor, in the order they are declared. */
internal fun signalClasses(
    src: String,
    lex: KotlinLex? = null,
): List<SignalClass> {
    // Each reading of a file asks for its classes more than once: they are kept for the text last read.
    lastClasses?.let { (text, classes) -> if (text === src) return classes }
    return readClasses(src, lex ?: lexKotlin(src)).also { lastClasses = src to it }
}

@Volatile
private var lastClasses: Pair<String, List<SignalClass>>? = null

private fun readClasses(
    src: String,
    lex: KotlinLex,
): List<SignalClass> {
    val out = ArrayList<SignalClass>()
    val types by lazy { typeBrackets(src, lex.mask, lex.comments) }
    // An interface or a type alias is a class of the project whose names the check cannot read.
    for (m in OTHER_TYPE.findAll(src)) if (lex.mask[m.range.first]) out += opaque(m.groupValues[1])
    for (m in CLASS.findAll(src)) {
        val at = m.range.first
        // `Form::class` names a class and declares none.
        if (!lex.mask[at] || src.startsWith("::", maxOf(0, at - 2))) continue
        val name = m.groupValues[1]
        val header = header(src, lex, at, 0)
        // An enum, an annotation or a value class is no object of signals, and an expect class is its actual's.
        if (header.modifiers.any { it in NO_OBJECT } ||
            (header.modifiers.any { it == "value" || it == "inline" } && header.annotations.none { it.name == "Serializable" })
        ) {
            continue
        }
        val serializable = header.annotations.any { it.name == "Serializable" }
        // A generic class's names depend on its argument, and a sealed, abstract or inner one's on more than its constructor.
        // kotlinx reads a sealed or abstract class as a signals class, as it always has.
        if (m.groupValues[2].isNotEmpty() || header.modifiers.any { it in NO_SIGNALS && !(serializable && it in KOTLINX_KINDS) }) {
            out += opaque(name)
            continue
        }
        val open = constructorOpen(src, lex.mask, types, m.range.last + 1)
        if (open == null) {
            out += opaque(name)
            continue
        }
        val closed = matching(src, lex.mask, open, alone = true)
        // Still being typed, a signals class's list is what stands before the first blank line: its signals stay known meanwhile.
        val close = closed.takeIf { it >= 0 } ?: if (serializable) BLANK_LINE.find(src, open)?.range?.first ?: src.length else -1
        if (close < 0) {
            out += opaque(name)
            continue
        }
        val properties = ArrayList<SignalProperty>()
        var readable = closed >= 0 && (serializable || header.annotations.none { ownsNames(it) })
        if (!serializable && closed >= 0 && !addsNoNames(src, lex, close + 1)) readable = false
        val list = src.substring(open, close)
        val depth = depths(list, lex, open)
        for (p in PROPERTY.findAll(list)) {
            val keyword = open + p.range.first
            // A `val` in a default value is that value's, not the class's.
            if (!lex.mask[keyword] || depth[p.range.first] != 1) continue
            val own = header(src, lex, keyword, open + 1)
            val group = PROPERTY.groupAt(list, p, 1) ?: continue
            val start = open + group.range.first
            val written = SignalProperty(group.value, start, start + group.value.length)
            if (serializable) {
                properties += renamed(src, own, "SerialName") ?: written
                continue
            }
            if (own.annotations.any { it.name in JACKSON_PROPERTY_OWN || it.arguments?.contains("access") == true }) readable = false
            // Jackson writes no private property, and none it is told to ignore.
            if ("private" in own.modifiers || "protected" in own.modifiers) continue
            if (own.annotations.any { it.name == "JsonIgnore" && it.arguments?.trim() != "false" }) continue
            properties += renamed(src, own, "JsonProperty") ?: written
        }
        out += SignalClass(name, serializable, readable, properties)
    }
    return out
}

/** A class of the project whose names the codec alone knows. */
private fun opaque(name: String) = SignalClass(name, serializable = false, readable = false, properties = emptyList())

/** The property named by the string argument of [header]'s annotation [annotation], where it has one. */
private fun renamed(
    src: String,
    header: Header,
    annotation: String,
): SignalProperty? {
    val a = header.annotations.firstOrNull { it.name == annotation } ?: return null
    val arguments = a.arguments ?: return null
    val m = NAME_ARGUMENT.find(arguments) ?: return null
    val group = NAME_ARGUMENT.groupAt(arguments, m, 1) ?: NAME_ARGUMENT.groupAt(arguments, m, 2) ?: return null
    // An empty name is none: the property keeps its own.
    if (group.value.isEmpty()) return null
    val start = a.argumentsStart + group.range.first
    return SignalProperty(group.value, start, start + group.value.length)
}

/** Where the constructor of a class whose name ends before [from] opens: after `constructor`, its modifiers and annotations, if any. */
private fun constructorOpen(
    src: String,
    code: BooleanArray,
    types: IntArray,
    from: Int,
): Int? {
    var i = from
    while (i < src.length) {
        while (i < src.length && src[i].isWhitespace()) i++
        when {
            src.startsWith("(", i) -> {
                return i
            }

            src.startsWith("@", i) -> {
                i = annotationEnd(src, code, types, i).takeIf { it > i } ?: return null
            }

            else -> {
                var end = i
                while (end < src.length && src[end].isLetter()) end++
                val word = src.substring(i, end)
                if (word.isEmpty() || (word != "constructor" && word !in MODIFIERS)) return null
                i = end
            }
        }
    }
    return null
}

/** Whether [a], on a Jackson class, says its names are not all its constructor's: `ignoreUnknown` alone, which reads them as they are, does not. */
private fun ownsNames(a: Annotation): Boolean =
    a.name in JACKSON_CLASS_OWN && !(a.name == "JsonIgnoreProperties" && a.arguments?.let { IGNORE_UNKNOWN_ONLY.matchEntire(it) } != null)

private val IGNORE_UNKNOWN_ONLY = regex("""\s*ignoreUnknown\s*=\s*(?:true|false)\s*""")

// What in a body or a supertype gives Jackson a name: a property, a getter, an annotation of its own, a class it extends.
private val NAMES_IN_BODY = regex("""\b(?:val|var)\b|\bfun\s+(?:get|is)[A-Z]|@(?:[A-Za-z_][\w.]*\.)?Json""")
private val COMPANION = regex("""\bcompanion\s+object\b[^{]*\{""")
private val SERIALIZABLE_SUPERTYPE = regex("""(?:java\.io\.)?Serializable""")

/**
 * Whether what follows a constructor that closes before [from] adds no name Jackson writes: no
 * supertype but `Serializable`, and a body, if any, with no property and no getter outside its
 * companion object.
 */
private fun addsNoNames(
    src: String,
    lex: KotlinLex,
    from: Int,
): Boolean {
    var i = from
    while (i < src.length && (src[i].isWhitespace() || lex.comments[i])) i++
    if (src.startsWith(":", i)) {
        var end = i + 1
        while (end < src.length && src[end] != '{' && src[end] != '\n') end++
        if (SERIALIZABLE_SUPERTYPE.matchEntire(src.substring(i + 1, end).trim()) == null) return false
        i = end
        while (i < src.length && src[i].isWhitespace()) i++
    }
    if (!src.startsWith("{", i)) return true
    val close = matching(src, lex.mask, i, alone = true)
    if (close < 0) return false
    val companions =
        COMPANION
            .findAll(src.substring(i + 1, close))
            .filter { lex.mask[i + 1 + it.range.first] }
            .map { m ->
                val open = i + 1 + m.range.last
                open..matching(src, lex.mask, open, alone = true).let { if (it < 0) close else it }
            }.toList()
    return NAMES_IN_BODY.findAll(src.substring(i + 1, close)).none { m ->
        val at = i + 1 + m.range.first
        lex.mask[at] && companions.none { at in it }
    }
}

/** The depth of each offset of [list], which starts at [start] with the constructor's `(`, in code brackets: 1 is the list's own. */
private fun depths(
    list: String,
    lex: KotlinLex,
    start: Int,
): IntArray {
    val out = IntArray(list.length)
    var depth = 0
    for (k in list.indices) {
        val c = list[k]
        if (lex.mask[start + k] && (c == '(' || c == '[' || c == '{')) depth++
        out[k] = depth
        if (lex.mask[start + k] && (c == ')' || c == ']' || c == '}')) depth--
    }
    return out
}

/**
 * The annotations and modifiers before the declaration at [at], read back to [floor]: each
 * `@Name`, `@target:Name` or `@a.b.Name` with what stands in its parentheses, and each modifier,
 * across white space and comments.
 */
private fun header(
    src: String,
    lex: KotlinLex,
    at: Int,
    floor: Int,
): Header {
    val annotations = ArrayList<Annotation>()
    val modifiers = HashSet<String>()
    var i = at
    while (true) {
        while (i > floor && (src[i - 1].isWhitespace() || lex.comments[i - 1])) i--
        if (i <= floor) break
        var arguments: String? = null
        var argumentsStart = -1
        if (src[i - 1] == ')' && lex.mask[i - 1]) {
            val open = openingParen(src, lex, i - 1, floor)
            if (open < 0) break
            arguments = src.substring(open + 1, i - 1)
            argumentsStart = open + 1
            i = open
        }
        var start = i
        while (start > floor &&
            (src[start - 1].isLetterOrDigit() || src[start - 1] == '_' || src[start - 1] == '.' || src[start - 1] == ':')
        ) {
            start--
        }
        val word = src.substring(start, i)
        if (word.isEmpty()) break
        if (start > floor && src[start - 1] == '@') {
            annotations += Annotation(word.substringAfterLast(':').substringAfterLast('.'), arguments, argumentsStart)
            i = start - 1
        } else if (arguments == null && word in MODIFIERS) {
            modifiers += word
            i = start
        } else {
            break
        }
    }
    return Header(annotations, modifiers)
}

/** The offset of the `(` that the code `)` at [close] closes, read back to [floor], or -1. */
private fun openingParen(
    src: String,
    lex: KotlinLex,
    close: Int,
    floor: Int,
): Int {
    var depth = 0
    for (i in close downTo floor) {
        if (!lex.mask[i]) continue
        when (src[i]) {
            ')' -> depth++
            '(' -> if (--depth == 0) return i
        }
    }
    return -1
}
