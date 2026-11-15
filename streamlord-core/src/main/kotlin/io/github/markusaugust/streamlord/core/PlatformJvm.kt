package io.github.markusaugust.streamlord.core

import java.math.BigDecimal
import java.math.BigInteger
import java.security.SecureRandom
import java.util.Base64

/*
 * What core asks of the JVM. The JavaScript build of the analysis leaves this file out and brings
 * its own, editors/vscode/analysis-js, with the same declarations.
 */

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
