package io.github.markusaugust.streamlord.analysis

import io.github.markusaugust.streamlord.core.client.regex

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
private val PROPERTY = regex("""\b(?:val|var)\s+([A-Za-z_][A-Za-z0-9_]*)\s*:""")
private val BLANK_LINE = regex("""\n[ \t\r]*\n""")

/** The text of a string argument: the first positional one, or the one named `value`. */
private val NAME_ARGUMENT = regex("""^\s*"([^"]*)"|\bvalue\s*=\s*"([^"]*)"""")

// The kinds of class whose constructor holds no signals, and the modifiers a header may hold.
private val NO_SIGNALS =
    setOf("enum", "annotation", "value", "inline", "sealed", "abstract", "inner", "interface", "fun", "expect", "external")
private val KOTLINX_KINDS = setOf("value", "inline", "sealed", "abstract")
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
    lex: KotlinLex = lexKotlin(src),
): List<SignalClass> {
    val out = ArrayList<SignalClass>()
    for (m in CLASS.findAll(src)) {
        val at = m.range.first
        // `Form::class` names a class and declares none.
        if (!lex.mask[at] || src.startsWith("::", maxOf(0, at - 2))) continue
        // A generic class's names depend on its argument.
        if (m.groupValues[2].isNotEmpty()) continue
        val header = header(src, lex, at, 0)
        val serializable = header.annotations.any { it.name == "Serializable" }
        // kotlinx reads a value, sealed or abstract class as a signals class, as it always has.
        if (header.modifiers.any { it in NO_SIGNALS && !(serializable && it in KOTLINX_KINDS) }) continue
        val name = m.groupValues[1]
        val open = constructorOpen(src, m.range.last + 1) ?: continue
        val closed = matchingParen(src, open)
        // Still being typed, a signals class's list is what stands before the first blank line: its signals stay known meanwhile.
        val close = closed.takeIf { it >= 0 } ?: if (serializable) BLANK_LINE.find(src, open)?.range?.first ?: src.length else continue
        val properties = ArrayList<SignalProperty>()
        var readable = closed >= 0 && (serializable || header.annotations.none { it.name in JACKSON_CLASS_OWN })
        if (!serializable && closed >= 0 && !endsBare(src, close + 1)) readable = false
        for (p in PROPERTY.findAll(src.substring(open, close))) {
            val keyword = open + p.range.first
            if (!lex.mask[keyword]) continue
            val own = header(src, lex, keyword, open + 1)
            val group = PROPERTY.groupAt(src.substring(open, close), p, 1) ?: continue
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
    val start = a.argumentsStart + group.range.first
    return SignalProperty(group.value, start, start + group.value.length)
}

/** Where the constructor of a class whose name ends before [from] opens: after `constructor`, its modifiers and annotations, if any. */
private fun constructorOpen(
    src: String,
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
                i++
                while (i < src.length && (src[i].isLetterOrDigit() || src[i] == '_' || src[i] == '.' || src[i] == ':')) i++
                if (src.startsWith("(", i)) i = matchingParen(src, i).takeIf { it >= 0 }?.plus(1) ?: return null
            }

            else -> {
                val word = src.substring(i).takeWhile { it.isLetter() }
                if (word.isEmpty() || (word != "constructor" && word !in MODIFIERS)) return null
                i += word.length
            }
        }
    }
    return null
}

/** Whether what follows a constructor that closes before [from] adds no name: no supertype and no body with anything in it. */
private fun endsBare(
    src: String,
    from: Int,
): Boolean {
    var i = from
    while (i < src.length && src[i].isWhitespace()) i++
    if (src.startsWith(":", i)) return false
    if (!src.startsWith("{", i)) return true
    val close = matchingBrace(src, i)
    return close >= 0 && src.substring(i + 1, close).isBlank()
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

/** The offset of the `}` that closes the `{` at [open], or -1. Nesting only. */
private fun matchingBrace(
    src: String,
    open: Int,
): Int {
    var depth = 0
    for (i in open until src.length) {
        when (src[i]) {
            '{' -> depth++
            '}' -> if (--depth == 0) return i
        }
    }
    return -1
}
