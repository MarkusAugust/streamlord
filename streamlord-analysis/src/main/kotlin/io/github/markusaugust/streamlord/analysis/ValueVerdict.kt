package io.github.markusaugust.streamlord.analysis

import io.github.markusaugust.streamlord.core.domain.Position

/*
 * What a value's fact says of it, written into markup: the one place the analysis asks which fact
 * a value has, so a rule is given once and named. The table is in cases/value-verdict.json. A fact of null is a value of unknown type.
 */

/** What a fact alone says of a value a Kotlin template writes into a position. */
internal enum class Verdict {
    /** Nothing to say. */
    SAFE,

    /** Characters that need escaping; in a URL the text before the value decides the rest. */
    ESCAPE,

    /** Escaping does not help. */
    UNSAFE,

    /** An unsigned number: its digits are safe, but interpolate refuses it here. */
    DIGITS,
}

/** The verdict on [fact] where a Kotlin template writes it at [position]. */
internal fun verdict(
    fact: ValueFact?,
    position: Position,
): Verdict {
    val plain = fact.plain()
    if (plain.ownAt(position)) return Verdict.SAFE
    return when (position) {
        Position.TEXT, Position.ATTRIBUTE, Position.URL -> {
            if (plain.scalar()) Verdict.SAFE else Verdict.ESCAPE
        }

        Position.STYLE -> {
            when (plain) {
                ValueFact.NUMBER -> Verdict.SAFE
                ValueFact.UNSIGNED -> Verdict.DIGITS
                else -> Verdict.UNSAFE
            }
        }

        Position.DATASTAR -> {
            when (plain) {
                ValueFact.NUMBER, ValueFact.BOOLEAN -> Verdict.SAFE
                ValueFact.UNSIGNED -> Verdict.DIGITS
                else -> Verdict.UNSAFE
            }
        }

        Position.CODE, Position.STRUCTURE -> {
            Verdict.UNSAFE
        }
    }
}

/**
 * Whether the value is the author's own or cannot carry code: a constant, a number, a boolean or
 * an unsigned number, whose digits Kotlin writes. Where escaping does not help, [verdict] still
 * asks which.
 */
public fun ValueFact?.writesItself(): Boolean =
    this == ValueFact.CONSTANT || this == ValueFact.NUMBER || this == ValueFact.BOOLEAN || this == ValueFact.UNSIGNED

/** A fact as the judgement reads it: a `String?` is a String, whose null only the fixes ask about. */
internal fun ValueFact?.plain(): ValueFact? = if (this == ValueFact.NULLABLE_STRING) ValueFact.STRING else this

/** Markup is the author's own in element text; in an attribute or code it is characters that can break out. */
private fun ValueFact?.ownAt(position: Position): Boolean =
    this == ValueFact.CONSTANT || (this == ValueFact.MARKUP && position == Position.TEXT)

/** Digits, true, false or null: safe wherever escaping is enough. */
internal fun ValueFact?.scalar(): Boolean =
    this == ValueFact.NUMBER || this == ValueFact.BOOLEAN || this == ValueFact.NULLABLE || this == ValueFact.UNSIGNED

/** Digits, true or false: never null and never markup. */
internal fun ValueFact?.digitsOrBoolean(): Boolean = this == ValueFact.NUMBER || this == ValueFact.BOOLEAN || this == ValueFact.UNSIGNED

/** A String or markup: characters that can end what holds them, so a warning, where a value of unknown type is a hint. */
internal fun ValueFact?.knownCharacters(): Boolean = this == ValueFact.STRING || this == ValueFact.MARKUP

/** What interpolate lets in where escaping does not help: a number in style, a number or a boolean in a Datastar attribute. */
internal fun ValueFact?.interpolateTakesIn(position: Position): Boolean =
    when (position) {
        Position.DATASTAR -> this == ValueFact.NUMBER || this == ValueFact.BOOLEAN
        Position.STYLE -> this == ValueFact.NUMBER
        else -> false
    }

/** How a message names a value of [digitsOrBoolean]: "a number", "a boolean", "an unsigned number". */
internal fun ValueFact.described(): String = if (this == ValueFact.UNSIGNED) "an unsigned number" else "a ${name.lowercase()}"

/** The kind of interpolate's argument a value of this fact is, if the fact says. */
internal fun ValueFact?.argKind(): ArgKind? =
    when (this) {
        ValueFact.STRING, ValueFact.NULLABLE_STRING -> ArgKind.STRING
        ValueFact.NUMBER -> ArgKind.NUMBER
        ValueFact.UNSIGNED -> ArgKind.UNSIGNED
        ValueFact.BOOLEAN -> ArgKind.BOOLEAN
        ValueFact.MARKUP -> ArgKind.MARKUP
        else -> null
    }

/** Written by the author: a literal, a constant, or text made from them alone. */
internal fun ValueFact?.isConstant(): Boolean = this == ValueFact.CONSTANT

/** A String, which is what may come from outside. */
internal fun ValueFact?.isString(): Boolean = this == ValueFact.STRING

/** A `String?`, which a template writes as `null` and interpolate as nothing. */
internal fun ValueFact?.isNullableString(): Boolean = this == ValueFact.NULLABLE_STRING

/** A number or a boolean that may be null. */
internal fun ValueFact?.isNullable(): Boolean = this == ValueFact.NULLABLE

/** An `Html` or a `Trusted` the editor resolved to another package, which no reading of the text makes markup. */
internal fun ValueFact?.isOther(): Boolean = this == ValueFact.OTHER

/** Html or Trusted: markup, whose values have been through interpolate or which the author vouched for. */
public fun ValueFact?.isMarkup(): Boolean = this == ValueFact.MARKUP

/** Each kind from the one safe in the fewest places to the one safe everywhere. */
private val WEAKEST_FIRST =
    listOf(
        ValueFact.STRING,
        null,
        ValueFact.OTHER,
        ValueFact.MARKUP,
        ValueFact.NULLABLE,
        ValueFact.UNSIGNED,
        ValueFact.BOOLEAN,
        ValueFact.NUMBER,
        ValueFact.CONSTANT,
    )

/** Of the kinds a hole's values have, the one safe in the fewest places; one not known stands between a String and markup. */
internal fun weakest(kinds: List<ValueFact?>): ValueFact? {
    val worst = kinds.minOfOrNull { WEAKEST_FIRST.indexOf(it) } ?: WEAKEST_FIRST.lastIndex
    return WEAKEST_FIRST[worst]
}
