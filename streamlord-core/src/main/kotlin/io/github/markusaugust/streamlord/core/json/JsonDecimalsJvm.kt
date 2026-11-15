@file:JvmName("JsonDecimals")

package io.github.markusaugust.streamlord.core.json

import java.math.BigDecimal

/*
 * A JSON number as a BigDecimal, which the JVM alone has: the JavaScript build of the analysis
 * leaves this file out.
 */

public fun JsonNumber.toBigDecimalOrNull(): BigDecimal? = runCatching { BigDecimal(text) }.getOrNull()

public fun JsonNumber.toBigDecimal(): BigDecimal =
    toBigDecimalOrNull() ?: throw ArithmeticException("Number '$text' is outside BigDecimal range")

public fun JsonObject.decimal(key: String): BigDecimal? = (this[key] as? JsonNumber)?.toBigDecimalOrNull()
