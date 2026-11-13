package io.github.markusaugust.streamlord.html.pro

import io.github.markusaugust.streamlord.html.JsLiteral
import io.github.markusaugust.streamlord.html.js

/*
 * Expression builders for the actions of Datastar Pro. They produce text; the Pro bundle you
 * license and load is what gives that text meaning.
 */

/** `@clipboard(text, isBase64)` (Pro): copy [text] to the clipboard. */
public fun clipboard(
    text: String,
    isBase64: Boolean = false,
): String = if (isBase64) "@clipboard(${js(text)}, true)" else "@clipboard(${js(text)})"

/** `@clipboard(expression)` (Pro) with a raw JavaScript expression, e.g. a signal. */
public fun clipboardExpr(
    expression: String,
    isBase64: Boolean = false,
): String = if (isBase64) "@clipboard($expression, true)" else "@clipboard($expression)"

/** `@fit(v, oldMin, oldMax, newMin, newMax, shouldClamp, shouldRound)` (Pro): linear interpolation between ranges. */
public fun fit(
    value: String,
    oldMin: Number,
    oldMax: Number,
    newMin: Number,
    newMax: Number,
    shouldClamp: Boolean = false,
    shouldRound: Boolean = false,
): String = fitCall(value, "$oldMin", "$oldMax", "$newMin", "$newMax", shouldClamp, shouldRound)

/** [fit] with each end of the ranges a raw JavaScript expression, e.g. a signal: `fitExpr(signal("x"), "0", signal("max"), "0", "1")`. */
public fun fitExpr(
    value: String,
    oldMin: String,
    oldMax: String,
    newMin: String,
    newMax: String,
    shouldClamp: Boolean = false,
    shouldRound: Boolean = false,
): String {
    val ends = listOf(oldMin, oldMax, newMin, newMax)
    for (end in ends) require(end.isNotBlank()) { "an end of a range of fit must not be blank" }
    // In parentheses, each end is one argument, whatever it holds.
    val (a, b, c, d) = ends.map { "($it)" }
    return fitCall(value, a, b, c, d, shouldClamp, shouldRound)
}

private fun fitCall(
    value: String,
    oldMin: String,
    oldMax: String,
    newMin: String,
    newMax: String,
    shouldClamp: Boolean,
    shouldRound: Boolean,
): String =
    buildString {
        append("@fit(").append(value)
        for (end in listOf(oldMin, oldMax, newMin, newMax)) append(", ").append(end)
        if (shouldClamp || shouldRound) append(", ").append(shouldClamp)
        if (shouldRound) append(", true")
        append(')')
    }

/** The `Intl` families `@intl` (Pro) understands. */
public enum class IntlType(
    public val wire: String,
) {
    DATETIME("datetime"),
    NUMBER("number"),
    PLURAL_RULES("pluralRules"),
    RELATIVE_TIME("relativeTime"),
    LIST("list"),
    DISPLAY_NAMES("displayNames"),
}

/**
 * `@intl(type, value, options, locale)` (Pro): locale-aware formatting.
 *
 * @param value A raw JavaScript expression, e.g. `signal("price")` or `new Date()`.
 * @param options Passed to the `Intl` constructor as an object literal. For [IntlType.RELATIVE_TIME]
 * and [IntlType.DISPLAY_NAMES] it is always written, empty or not: the client reads a field of it
 * (`unit`, `type`) and throws without one.
 * @param locales One or more BCP 47 locales; omitted means the browser's.
 */
public fun intl(
    type: IntlType,
    value: String,
    options: Map<String, Any?> = emptyMap(),
    vararg locales: String,
): String =
    buildString {
        append("@intl(").append(js(type.wire)).append(", ").append(value)
        val readsOptions = type == IntlType.RELATIVE_TIME || type == IntlType.DISPLAY_NAMES
        if (readsOptions || options.isNotEmpty() || locales.isNotEmpty()) append(", ").append(JsLiteral.write(options))
        when (locales.size) {
            0 -> Unit
            1 -> append(", ").append(js(locales[0]))
            else -> append(", ").append(JsLiteral.write(locales.toList()))
        }
        append(')')
    }
