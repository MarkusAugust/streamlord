package io.github.markusaugust.streamlord.core

import java.math.BigDecimal

// What core asks of its platform. On the JVM these are the declarations of PlatformJvm.kt in streamlord-core.

internal expect fun decimalOrNull(text: String): BigDecimal?

internal expect fun exactLongOrNull(text: String): Long?

internal expect fun bigNumberText(
    n: Number,
    plainScale: Int,
): String?

internal expect fun typeName(value: Any): String?

internal expect fun randomBase64(bytes: Int): String
