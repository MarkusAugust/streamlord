package io.github.markusaugust.streamlord.analysis

import io.github.markusaugust.streamlord.core.client.regex

/*
 * What a Kotlin type or string literal, as the source writes it, tells of a value joined into
 * HTML. The analysis reads declarations with these, and the IntelliJ plugin reads with them the
 * types and literals it finds, so each rule is written once. The cases are in cases/value-types.json.
 */

/** Streamlord's own markup types, bare as an import gives them or written out in full. */
private const val CORE_DOMAIN = """(?:io\.github\.markusaugust\.streamlord\.core\.domain\.)?"""

private val NUMBER_TYPE = regex("""(?:kotlin\.)?(?:Int|Long|Short|Byte|Double|Float)""")

/** A Number of no closed type, whose class may write anything; one that may be null is not known. */
private val OPEN_NUMBER_TYPE = regex("""(?:kotlin\.)?Number""")

/** Digits, but not a `kotlin.Number`: interpolate refuses one where only a number is let in. */
private val UNSIGNED_TYPE = regex("""(?:kotlin\.)?(?:UInt|ULong|UShort|UByte)""")
private val BOOLEAN_TYPE = regex("""(?:kotlin\.)?Boolean""")
private val STRING_TYPE = regex("""(?:kotlin\.)?(?:String|CharSequence)""")
private val HTML_TYPE = regex("${CORE_DOMAIN}Html")
private val TRUSTED_TYPE = regex("${CORE_DOMAIN}Trusted")

/** What a template writes in brackets, by qualified name: Kotlin's collections, Java's that its aliases expand to, and the arrays. */
private val COLLECTIONS: Set<String> =
    (
        listOf(
            "List",
            "Set",
            "Map",
            "Collection",
            "Iterable",
        ).flatMap { listOf("kotlin.collections.$it", "kotlin.collections.Mutable$it") } +
            listOf(
                "ArrayList",
                "HashSet",
                "LinkedHashSet",
                "HashMap",
                "LinkedHashMap",
                "TreeMap",
                "TreeSet",
                "LinkedList",
            ).map { "java.util.$it" } +
            listOf("", "Int", "Long", "Short", "Byte", "Double", "Float", "Boolean", "Char").map { "kotlin.${it}Array" } +
            "kotlin.sequences.Sequence"
    ).toSet()

/**
 * A collection of markup: what `joinToString` over Html or Trusted is called on, one of [COLLECTIONS] by its name alone
 * or in full. An Html that may be null is markup as it is on its own, and `out` changes nothing a read sees.
 */
private val MARKUP_COLLECTION =
    regex(
        "(?:" + COLLECTIONS.flatMap { listOf(it.replace(".", """\."""), it.substringAfterLast('.')) }.distinct().joinToString("|") + ")" +
            """<$CORE_DOMAIN(?:Html\??|Trusted)>\??""",
    )

/**
 * A tag, a comment or a doctype first, with what may stand for the tag name when a space, `>` or
 * `/` follows it. Whitespace and letters as HTML has them, ASCII only, so
 * that it means the same on the JVM and in JavaScript.
 */
private fun tagStart(template: String): Regex =
    regex("""^[ \t\n\f\r]*<(?:[A-Za-z]|(?:$template)(?=[ \t\n\f\r>/])|!--|![Dd][Oo][Cc][Tt][Yy][Pp][Ee])""")

/** In the source: `<$tag ` and `<${t} `, and `__kt__`, a `%s` of interpolate's format read as one. */
private val TAG_START = tagStart("""__kt__|\$(?:\{[^{}]*\}|[\p{L}_][\p{L}\p{Nd}_]*)""")

/** In the text at runtime, where each template is `__kt__` and a `$` left is one the author wrote. */
private val TEXT_TAG_START = tagStart("__kt__")
private val MARGIN_START = regex("""^\s*\|""")

/**
 * [ValueFact.OTHER] for a class an editor resolved, by its qualified name, where the text alone would read its name as
 * Streamlord's markup: an `Html` or a `Trusted` that is not Streamlord's, of another package or of none. Null for any
 * other class, Streamlord's own among them.
 */
private fun otherClass(type: String): ValueFact? {
    val qualified = type.substringBefore('<').removeSuffix("?")
    val name = qualified.substringAfterLast('.')
    val markup = name == "Html" || name == "Trusted"
    return ValueFact.OTHER.takeIf { markup && qualified != "io.github.markusaugust.streamlord.core.domain.$name" }
}

/**
 * The fact of what an editor answered of a value. A constant is one whatever its type, and markup by its type is markup
 * whatever the editor read of it; what it read is else taken over a type that says less.
 */
public fun ValueAnswer.fact(): ValueFact? {
    if (constant == true) return ValueFact.CONSTANT
    val typed = type?.let(::resolvedFact)
    return if (typed.isMarkup()) typed else read ?: typed
}

/** The fact of a type an editor resolved, [ValueAnswer.type]: as [ofTypeName] reads one a declaration writes, and a collection by its class. */
private fun resolvedFact(type: String): ValueFact? {
    otherClass(type)?.let { return it }
    val qualified = type.substringBefore('<').removeSuffix("?")
    return when {
        // A class of no package is none of Kotlin's, whatever it is named.
        '.' !in qualified -> null

        qualified in COLLECTIONS -> if (isMarkupCollection(type)) ValueFact.MARKUP else ValueFact.COLLECTION

        else -> ValueFact.ofTypeName(type)
    }
}

/**
 * The fact a type gives, as a declaration writes it, the whitespace in it ignored: `Int`
 * [ValueFact.NUMBER] and `Int?` [ValueFact.NULLABLE], `String?` [ValueFact.NULLABLE_STRING], and
 * Streamlord's `Html` [ValueFact.MARKUP] and `Trusted` [ValueFact.TRUSTED], bare or in full. Null for any other type,
 * `com.acme.Html` among them.
 */
internal fun ValueFact.Companion.ofTypeName(written: String): ValueFact? {
    val type = written.filterNot { it.isWhitespace() }
    val bare = type.removeSuffix("?")
    val nullable = bare != type
    return when {
        NUMBER_TYPE.matchEntire(bare) != null -> if (nullable) ValueFact.NULLABLE else ValueFact.NUMBER

        OPEN_NUMBER_TYPE.matchEntire(bare) != null -> if (nullable) null else ValueFact.OPEN_NUMBER

        UNSIGNED_TYPE.matchEntire(bare) != null -> if (nullable) ValueFact.NULLABLE else ValueFact.UNSIGNED

        BOOLEAN_TYPE.matchEntire(bare) != null -> if (nullable) ValueFact.NULLABLE else ValueFact.BOOLEAN

        STRING_TYPE.matchEntire(bare) != null -> if (nullable) ValueFact.NULLABLE_STRING else ValueFact.STRING

        // What interpolate returns: markup whose values have been through it.
        HTML_TYPE.matchEntire(bare) != null -> ValueFact.MARKUP

        // Markup the author vouched for, which a Kotlin template writes as it stands; `null` if it may be.
        !nullable && TRUSTED_TYPE.matchEntire(bare) != null -> ValueFact.TRUSTED

        else -> null
    }
}

/**
 * The fact a declaration of the type [written] gives its value: [nonNull] reads it as the left
 * side of `?:` does, and without [core] Html and Trusted are not Streamlord's.
 */
internal fun declaredFact(
    written: String,
    nonNull: Boolean,
    core: Boolean,
): ValueFact? =
    when (val fact = ValueFact.ofTypeName(written)) {
        // Digits, true or false, or null: safe where anything is, and not where only a number or a boolean is.
        ValueFact.NULLABLE -> if (nonNull) ValueFact.ofTypeName(written.filterNot { it.isWhitespace() }.removeSuffix("?")) else fact

        // A template writes `null` for it, which the analysis judges as the string it may also be.
        ValueFact.NULLABLE_STRING -> ValueFact.STRING

        ValueFact.MARKUP, ValueFact.TRUSTED -> if (core) fact else null

        else -> fact
    }

/** Whether [type] is a collection of Streamlord's markup, as `List<Html>`; the whitespace in it is ignored. */
internal fun isMarkupCollection(type: String): Boolean =
    MARKUP_COLLECTION.matchEntire(type.replace(OUT_PROJECTION, "<").filterNot { it.isWhitespace() }) != null

// `out` as a keyword, so a type named `outHtml` is not read as `out Html`.
private val OUT_PROJECTION = regex("""<\s*out\s+""")

/**
 * Whether the content of a string literal, past its opening quotes, starts with a tag, a comment
 * or a doctype, as markup does. [trimMargin] reads it as `.trimMargin()` leaves it, past the first `|`.
 */
public fun opensWithTag(
    content: String,
    trimMargin: Boolean = false,
): Boolean = TAG_START.containsMatchIn(if (trimMargin) content.replaceFirst(MARGIN_START, "") else content)

/** [opensWithTag] for a literal's text at runtime, [KotlinString.text], where each template is `__kt__`. */
internal fun textOpensWithTag(text: String): Boolean = TEXT_TAG_START.containsMatchIn(text)

/** Whether [type] is Streamlord's `Trusted`, which cannot be null. */
internal fun isTrustedType(type: String): Boolean = TRUSTED_TYPE.matchEntire(type.filterNot { it.isWhitespace() }) != null
