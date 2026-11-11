package io.github.markusaugust.streamlord.core

// What core asks of its platform. On the JVM these are the declarations of PlatformJvm.kt in streamlord-core.

public expect class Decimal

internal expect fun decimalOrNull(text: String): Decimal?

internal expect fun exactLongOrNull(text: String): Long?

internal expect fun bigNumberText(
    n: Number,
    plainScale: Int,
): String?

internal expect fun typeName(value: Any): String?

internal expect fun randomBase64(bytes: Int): String

@InternalStreamlordApi
public expect fun radixDouble(
    digits: String,
    radix: Int,
): Double?

@InternalStreamlordApi
public expect fun formEncoded(s: String): String

@InternalStreamlordApi
public expect fun regex(
    pattern: String,
    vararg options: RegexOption,
): Regex

@InternalStreamlordApi
public expect fun Regex.groupRange(
    input: CharSequence,
    match: MatchResult,
    group: Int,
): IntRange?
