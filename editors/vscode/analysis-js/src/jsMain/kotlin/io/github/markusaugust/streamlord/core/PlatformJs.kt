package io.github.markusaugust.streamlord.core

import java.math.BigDecimal

// What core asks of its platform, in JavaScript: the declarations of PlatformJvm.kt in streamlord-core.

internal actual fun decimalOrNull(text: String): BigDecimal? = null

/** As [decimalOrNull]: an exponent or a fraction takes an exact decimal, and nothing the editor runs reads a whole number so written. */
internal actual fun exactLongOrNull(text: String): Long? = null

@Suppress("UNUSED_PARAMETER")
internal actual fun bigNumberText(
    n: Number,
    plainScale: Int,
): String? = null

internal actual fun randomBase64(bytes: Int): String {
    val out: dynamic = js("crypto").getRandomValues(js("new Uint8Array(bytes)"))
    return js("btoa")(js("String.fromCharCode").apply(null, out)).unsafeCast<String>()
}
