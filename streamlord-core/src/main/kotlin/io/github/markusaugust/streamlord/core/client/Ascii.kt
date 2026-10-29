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
