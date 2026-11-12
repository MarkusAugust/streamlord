package io.github.markusaugust.streamlord.core

// What core asks of its platform, in JavaScript: the declarations of PlatformJvm.kt in streamlord-core.

/** JavaScript has no arbitrary-precision decimal, so no number is ever read as one. */
public actual class Decimal private constructor()

internal actual fun decimalOrNull(text: String): Decimal? = null

/**
 * The whole number a JSON number is when it has one that fits a `Long`, whatever way it is written:
 * `1e3` is 1000 and `1.0` is 1; `1.5` and `1e30` have none.
 */
internal actual fun exactLongOrNull(text: String): Long? {
    val negative = text.startsWith('-')
    val unsigned = if (negative) text.substring(1) else text
    val e = unsigned.indexOfFirst { it == 'e' || it == 'E' }
    val mantissa = if (e < 0) unsigned else unsigned.substring(0, e)
    // BigDecimal, which the JVM asks, takes no exponent and no scale past an Int, whatever the digits.
    val exponent = if (e < 0) 0 else unsigned.substring(e + 1).toIntOrNull() ?: return null
    val whole = mantissa.substringBefore('.')
    val fraction = mantissa.substringAfter('.', "")
    val digits = (whole + fraction).trimStart('0')
    // The digits are the number times ten to the power of [scale].
    val scale = fraction.length.toLong() - exponent
    if (scale < Int.MIN_VALUE || scale > Int.MAX_VALUE) return null
    if (digits.isEmpty()) return 0
    val wholeDigits =
        when {
            scale <= 0 -> if (digits.length - scale > 19) return null else digits + "0".repeat((-scale).toInt())
            scale >= digits.length -> return null
            digits.takeLast(scale.toInt()).any { it != '0' } -> return null
            else -> digits.dropLast(scale.toInt())
        }
    return ((if (negative) "-" else "") + wholeDigits).toLongOrNull()
}

@Suppress("UNUSED_PARAMETER")
internal actual fun bigNumberText(
    n: Number,
    plainScale: Int,
): String? = null

internal actual fun typeName(value: Any): String? = value::class.simpleName

@Suppress("UNUSED_PARAMETER")
internal actual fun randomBase64(bytes: Int): String = throw UnsupportedOperationException("The editor writes no nonce")

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
