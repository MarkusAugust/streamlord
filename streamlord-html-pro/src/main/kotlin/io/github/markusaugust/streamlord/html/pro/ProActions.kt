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

/** `@fit(v, oldMin, oldMax, newMin, newMax, clamp, round)` (Pro): linear interpolation between ranges. */
public fun fit(
    value: String,
    oldMin: Number,
    oldMax: Number,
    newMin: Number,
    newMax: Number,
    clamp: Boolean = false,
    round: Boolean = false,
): String =
    buildString {
        append("@fit(")
            .append(value)
            .append(", ")
            .append(oldMin)
            .append(", ")
            .append(oldMax)
        append(", ").append(newMin).append(", ").append(newMax)
        if (clamp || round) append(", ").append(clamp)
        if (round) append(", true")
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
 * @param options Passed to the `Intl` constructor as an object literal.
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
        if (options.isNotEmpty() || locales.isNotEmpty()) append(", ").append(JsLiteral.write(options))
        when (locales.size) {
            0 -> Unit
            1 -> append(", ").append(js(locales[0]))
            else -> append(", ").append(JsLiteral.write(locales.toList()))
        }
        append(')')
    }
