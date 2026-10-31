package io.github.markusaugust.streamlord.core.client

import io.github.markusaugust.streamlord.core.InternalStreamlordApi

/**
 * Lowercases A to Z and nothing else, as the browser lowercases a tag or an attribute name. The
 * result is as long as the input: `lowercase()` turns U+0130 into two characters and moves every
 * offset after it, and turns the Kelvin sign into a `k` the browser never sees.
 */
@InternalStreamlordApi
public fun String.asciiLowercase(): String {
    if (none { it in 'A'..'Z' }) return this
    val out = StringBuilder(length)
    for (c in this) out.append(if (c in 'A'..'Z') c + 32 else c)
    return out.toString()
}

/** `indexOf` that ignores the case of A to Z and nothing else; [needle] is given in lowercase. */
@InternalStreamlordApi
public fun String.indexOfAsciiIgnoreCase(
    needle: String,
    from: Int,
): Int {
    for (i in maxOf(0, from)..length - needle.length) {
        if (regionMatchesAsciiIgnoreCase(i, needle)) return i
    }
    return -1
}

/** Whether [needle], given in lowercase, stands at [at], ignoring the case of A to Z and nothing else. */
@InternalStreamlordApi
public fun String.regionMatchesAsciiIgnoreCase(
    at: Int,
    needle: String,
): Boolean {
    if (at < 0 || at + needle.length > length) return false
    for (k in needle.indices) {
        val c = this[at + k]
        if ((if (c in 'A'..'Z') c + 32 else c) != needle[k]) return false
    }
    return true
}
