package io.github.markusaugust.streamlord.html

import io.github.markusaugust.streamlord.core.json.JsonWriter

/*
 * Small spells for writing Datastar expressions without fighting Kotlin's `$` templates.
 *
 * `"$count++"` in Kotlin tries to interpolate a variable named `count`. These helpers build the
 * same text without a single escaped dollar sign.
 */

/** `$name`: a reference to a signal. Dotted names reach nested signals. */
public fun signal(name: String): String = "$" + Casing.reference(name)

/** `$name = value`, with [value] serialised as JSON (which is valid JavaScript). */
public fun set(
    name: String,
    value: Any?,
): String = "${signal(name)} = ${JsonWriter.write(value)}"

/** `$name = expression`, with a raw JavaScript expression on the right. */
public fun setExpr(
    name: String,
    expression: String,
): String = "${signal(name)} = $expression"

/** `$name++`. */
public fun increment(name: String): String = "${signal(name)}++"

/** `$name--`. */
public fun decrement(name: String): String = "${signal(name)}--"

/** `$name = !$name`. */
public fun toggle(name: String): String = "${signal(name)} = !${signal(name)}"

/** `!$name`. */
public fun not(name: String): String = "!${signal(name)}"

/** Several statements, separated by `;`. */
public fun statements(vararg expressions: String): String = expressions.joinToString("; ")
