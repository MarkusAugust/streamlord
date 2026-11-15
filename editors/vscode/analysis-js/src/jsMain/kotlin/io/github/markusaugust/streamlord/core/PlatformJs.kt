package io.github.markusaugust.streamlord.core

// What core asks of its platform, in JavaScript: the declarations of PlatformJvm.kt in streamlord-core.

@Suppress("UNUSED_PARAMETER")
internal actual fun bigNumberText(
    n: Number,
    plainScale: Int,
): String? = null

internal actual fun randomBase64(bytes: Int): String {
    val out: dynamic = js("crypto").getRandomValues(js("new Uint8Array(bytes)"))
    return js("btoa")(js("String.fromCharCode").apply(null, out)).unsafeCast<String>()
}
