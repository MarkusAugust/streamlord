package io.github.markusaugust.streamlord.analysis

/*
 * What a Kotlin type or string literal, as the source writes it, tells of a value joined into
 * HTML. The analysis reads declarations with these, and the IntelliJ plugin reads with them what
 * its K1 mode resolves, so each rule is written once. The twin of valueTypes.ts; the cases both
 * hold are in twins/value-types.json.
 */

/** Streamlord's own markup types, bare as an import gives them or written out in full. */
private const val CORE_DOMAIN = """(?:io\.github\.markusaugust\.streamlord\.core\.domain\.)?"""

private val NUMBER_TYPE = Regex("""(?:kotlin\.)?(?:Int|Long|Short|Byte|Double|Float|Number)""")

/** Digits, but not a `kotlin.Number`: interpolate refuses one where only a number is let in. */
private val UNSIGNED_TYPE = Regex("""(?:kotlin\.)?(?:UInt|ULong|UShort|UByte)""")
private val BOOLEAN_TYPE = Regex("""(?:kotlin\.)?Boolean""")
private val STRING_TYPE = Regex("""(?:kotlin\.)?(?:String|CharSequence)""")
private val HTML_TYPE = Regex("${CORE_DOMAIN}Html")
private val TRUSTED_TYPE = Regex("${CORE_DOMAIN}Trusted")

/** A collection of markup: what `joinToString` over Html or Trusted is called on. */
private val MARKUP_COLLECTION =
    Regex(
        """(?:kotlin\.collections\.|kotlin\.sequences\.)?""" +
            """(?:List|MutableList|ArrayList|Collection|Iterable|Set|MutableSet|Sequence|Array)""" +
            """<$CORE_DOMAIN(?:Html|Trusted)>\??""",
    )

/**
 * A tag, a comment or a doctype first: `<$tag ` and `<${t} ` among them when a space, `>` or `/`
 * follows the template, and `__kt__`, a `%s` of interpolate's format read as one.
 */
private val TAG_START =
    Regex(
        """^\s*<(?:[A-Za-z]|(?:__kt__|\$(?:\{[^{}]*\}|[\p{L}_][\p{L}\p{Nd}_]*))(?=[\s>/])|!--|!doctype)""",
        RegexOption.IGNORE_CASE,
    )
private val MARGIN_START = Regex("""^\s*\|""")

/** The fact a declared type gives, the whitespace in it ignored; see [ValueFact.ofTypeName]. */
internal fun valueFactOfTypeName(written: String): ValueFact? {
    val type = written.filterNot { it.isWhitespace() }
    val bare = type.removeSuffix("?")
    val nullable = bare != type
    return when {
        NUMBER_TYPE.matches(bare) -> if (nullable) ValueFact.NULLABLE else ValueFact.NUMBER

        UNSIGNED_TYPE.matches(bare) -> if (nullable) ValueFact.NULLABLE else ValueFact.UNSIGNED

        BOOLEAN_TYPE.matches(bare) -> if (nullable) ValueFact.NULLABLE else ValueFact.BOOLEAN

        STRING_TYPE.matches(bare) -> if (nullable) ValueFact.NULLABLE_STRING else ValueFact.STRING

        // What interpolate returns: markup whose values have been through it.
        HTML_TYPE.matches(bare) -> ValueFact.MARKUP

        // Markup the author vouched for, which a Kotlin template writes as it stands; `null` if it may be.
        !nullable && TRUSTED_TYPE.matches(bare) -> ValueFact.MARKUP

        else -> null
    }
}

/** Whether [type] is a collection of Streamlord's markup, as `List<Html>`; the whitespace in it is ignored. */
public fun isMarkupCollection(type: String): Boolean = MARKUP_COLLECTION.matches(type.filterNot { it.isWhitespace() })

/**
 * Whether the content of a string literal, past its opening quotes, starts with a tag, a comment
 * or a doctype, as markup does. [trimMargin] reads it as `.trimMargin()` leaves it, past the first `|`.
 */
public fun opensWithTag(
    content: String,
    trimMargin: Boolean = false,
): Boolean = TAG_START.containsMatchIn(if (trimMargin) content.replaceFirst(MARGIN_START, "") else content)

/** Whether [type] is Streamlord's `Trusted`, which cannot be null. */
internal fun isTrustedType(type: String): Boolean = TRUSTED_TYPE.matches(type.filterNot { it.isWhitespace() })
