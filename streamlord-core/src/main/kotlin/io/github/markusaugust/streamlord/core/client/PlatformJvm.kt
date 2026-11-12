package io.github.markusaugust.streamlord.core.client

import io.github.markusaugust.streamlord.core.InternalStreamlordApi
import java.net.URLEncoder

/*
 * What core and the analysis both ask of the JVM. The JavaScript build of the analysis leaves this
 * file out and brings its own, editors/vscode/analysis-js, with the same declarations.
 */

/** The digits of a whole number in base [radix] as the double JavaScript reads them as, or null when they are no such number. */
@InternalStreamlordApi
public fun radixDouble(
    digits: String,
    radix: Int,
): Double? = digits.toBigIntegerOrNull(radix)?.toDouble()

/** `application/x-www-form-urlencoded` in UTF-8, as `URLSearchParams` writes it. */
@InternalStreamlordApi
public fun formEncoded(s: String): String = URLEncoder.encode(s, Charsets.UTF_8)

/**
 * A pattern with the meaning java.util.regex gives it. Every pattern of core and the analysis is
 * built here, so that a build for another regex engine can give each the same meaning.
 */
@InternalStreamlordApi
public fun regex(
    pattern: String,
    vararg options: RegexOption,
): Regex = Regex(pattern, options.toSet())

/** Where group [group] of [match], a match of this pattern in [input], stands; null when the group took no part. */
@InternalStreamlordApi
@Suppress("UnusedReceiverParameter", "UNUSED_PARAMETER")
public fun Regex.groupRange(
    input: CharSequence,
    match: MatchResult,
    group: Int,
): IntRange? = match.groups[group]?.range
