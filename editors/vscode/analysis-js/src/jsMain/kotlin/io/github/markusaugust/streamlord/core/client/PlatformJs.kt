package io.github.markusaugust.streamlord.core.client

import io.github.markusaugust.streamlord.core.InternalStreamlordApi

// What core and the analysis both ask of their platform, in JavaScript: the declarations of client/PlatformJvm.kt in streamlord-core.

/** JavaScript's own reading of the digits, which is exact for a base that is a power of two. */
@InternalStreamlordApi
public actual fun radixDouble(
    digits: String,
    radix: Int,
): Double? {
    if (digits.isEmpty() || digits.any { it.digitToIntOrNull(radix) == null }) return null
    val prefix =
        when (radix) {
            16 -> "0x"
            8 -> "0o"
            2 -> "0b"
            else -> throw IllegalArgumentException("radix $radix")
        }
    return js("Number")(prefix + digits).unsafeCast<Double>()
}

@InternalStreamlordApi
public actual fun formEncoded(s: String): String {
    val params: dynamic = js("new URLSearchParams()")
    params.append("", s)
    return (params.toString() as String).substring(1)
}
