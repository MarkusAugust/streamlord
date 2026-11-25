package io.github.markusaugust.streamlord.core

// What core asks of its platform. On the JVM these are the declarations of PlatformJvm.kt in streamlord-core.

internal expect fun bigNumberText(
    n: Number,
    plainScale: Int,
): String?

internal expect fun randomBase64(bytes: Int): String

internal expect fun <T> weakSet(): MutableSet<T>
