package io.github.markusaugust.streamlord.analysis

// What the analysis asks of its platform, in JavaScript: the declarations of PlatformJvm.kt in streamlord-analysis.

internal actual fun <K : Any, V : Any> sharedMap(): MutableMap<K, V> = HashMap()

/** What V8 throws when the stack is spent. */
internal external class RangeError : Throwable

internal actual typealias StackExhausted = RangeError

internal actual abstract class Traceless actual constructor() : RuntimeException()

internal actual fun currentTimeMillis(): Long =
    kotlin.js.Date
        .now()
        .toLong()

private val ID_START: dynamic = js("/^\\p{ID_Start}$/u")
private val ID_CONTINUE: dynamic = js("/^\\p{ID_Continue}$/u")

internal actual fun startsName(codePoint: Int): Boolean = ID_START.test(js("String").fromCodePoint(codePoint)) as Boolean

internal actual fun continuesName(codePoint: Int): Boolean = ID_CONTINUE.test(js("String").fromCodePoint(codePoint)) as Boolean

private val indexed = HashMap<Regex, dynamic>()

/** Runs the pattern again at the match with the `d` flag, which is where JavaScript says where a group stands. */
internal actual fun Regex.groupRange(
    input: CharSequence,
    match: MatchResult,
    group: Int,
): IntRange? {
    if (indexed.size >= 4096) indexed.clear()
    val again =
        indexed.getOrPut(this) {
            js("RegExp")(pattern, if (RegexOption.IGNORE_CASE in options) "duyi" else "duy")
        }
    again.lastIndex = match.range.first
    val found = again.exec(input.toString()) ?: return null
    val at = found.indices[group] ?: return null
    return (at[0] as Int) until (at[1] as Int)
}
