package io.github.markusaugust.streamlord.core.client

import io.github.markusaugust.streamlord.core.InternalStreamlordApi

/** The code point that starts at [index]: a surrogate pair as one, a surrogate without its partner as itself. */
@InternalStreamlordApi
public fun CharSequence.codePointFrom(index: Int): Int {
    val high = this[index]
    if (high.isHighSurrogate() && index + 1 < length) {
        val low = this[index + 1]
        if (low.isLowSurrogate()) return 0x10000 + ((high.code - 0xD800) shl 10) + (low.code - 0xDC00)
    }
    return high.code
}

/** How many characters [codePoint] takes in a string. */
@InternalStreamlordApi
public fun charCount(codePoint: Int): Int = if (codePoint >= 0x10000) 2 else 1

/** [codePoint] as a string; one past U+10FFFF or below zero is refused. */
@InternalStreamlordApi
public fun codePointString(codePoint: Int): String {
    require(codePoint in 0..0x10FFFF) { "Not a code point: $codePoint" }
    if (codePoint < 0x10000) return codePoint.toChar().toString()
    val offset = codePoint - 0x10000
    return charArrayOf((0xD800 + (offset shr 10)).toChar(), (0xDC00 + (offset and 0x3FF)).toChar()).concatToString()
}

/** [code] in hexadecimal, lowercase, with zeros before it up to [width] digits: `%04x`. */
@InternalStreamlordApi
public fun hex(
    code: Int,
    width: Int,
): String = code.toString(16).padStart(width, '0')
