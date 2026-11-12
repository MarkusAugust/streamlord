package io.github.markusaugust.streamlord.core.client

import io.github.markusaugust.streamlord.core.InternalStreamlordApi

// What core and the analysis both ask of their platform. On the JVM these are the declarations of client/PlatformJvm.kt in streamlord-core.

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
