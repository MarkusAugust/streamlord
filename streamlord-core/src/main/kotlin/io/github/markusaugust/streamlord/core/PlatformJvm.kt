package io.github.markusaugust.streamlord.core

import java.math.BigDecimal
import java.math.BigInteger
import java.security.SecureRandom
import java.util.Base64

/*
 * What core asks of the JVM. The JavaScript build of the analysis leaves this file out and brings
 * its own, editors/vscode/analysis-js, with the same declarations.
 */

internal fun decimalOrNull(text: String): BigDecimal? = runCatching { BigDecimal(text) }.getOrNull()

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

private val random = SecureRandom()

/** [bytes] random bytes from [SecureRandom], base64 encoded. */
internal fun randomBase64(bytes: Int): String {
    val out = ByteArray(bytes)
    random.nextBytes(out)
    return Base64.getEncoder().encodeToString(out)
}
