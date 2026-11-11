package io.github.markusaugust.streamlord.core

import java.math.BigDecimal
import java.math.BigInteger
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.Base64

/*
 * What core asks of the JVM. The JavaScript build of the analysis leaves this file out and brings
 * its own, editors/vscode/analysis-js, with the same declarations.
 */

/** An arbitrary-precision decimal, where the platform has one. */
public typealias Decimal = BigDecimal

internal fun decimalOrNull(text: String): Decimal? = runCatching { BigDecimal(text) }.getOrNull()

/** The whole number a JSON number is when it has one that fits a `Long`, however it is written: `1e3` is 1000, `1.5` has none. */
internal fun exactLongOrNull(text: String): Long? = runCatching { BigDecimal(text).longValueExact() }.getOrNull()

/** A `BigInteger` or `BigDecimal` as JSON writes it, or null for any other number. */
internal fun bigNumberText(
    n: Number,
    plainScale: Int,
): String? =
    when (n) {
        is BigInteger -> n.toString()

        // Plain notation reads better, and for a scale like 2000000000 it is two billion zeros.
        is BigDecimal -> if (n.scale() in -plainScale..plainScale) n.toPlainString() else n.toString()

        else -> null
    }

internal fun typeName(value: Any): String? = value::class.qualifiedName

private val random = SecureRandom()

/** [bytes] random bytes from [SecureRandom], base64 encoded. */
internal fun randomBase64(bytes: Int): String {
    val out = ByteArray(bytes)
    random.nextBytes(out)
    return Base64.getEncoder().encodeToString(out)
}

/** The digits of a whole number in base [radix] as the double JavaScript reads them as, or null when they are no such number. */
@InternalStreamlordApi
public fun radixDouble(
    digits: String,
    radix: Int,
): Double? = digits.toBigIntegerOrNull(radix)?.toDouble()

/** `application/x-www-form-urlencoded` in UTF-8, as `URLSearchParams` writes it. */
@InternalStreamlordApi
public fun formEncoded(s: String): String = URLEncoder.encode(s, Charsets.UTF_8)
