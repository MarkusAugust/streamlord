package io.github.markusaugust.streamlord.fixtures

import kotlin.math.roundToInt
import kotlin.test.fail
import kotlin.time.TimeSource

/*
 * Compiled into the tests of core and of the analysis on the JVM and on Node, and of the IntelliJ
 * plugin. A bound on one time fails on a busy machine; a ratio of two holds, when both are timed
 * the same way. So the work on ten inputs of a size is timed against the work on one input of ten
 * times that size: both take about as long, and a busy machine slows both alike.
 */

private const val FACTOR = 10

// Read once, ten times the size takes about ten times as long; read again for each part, a hundred.
// Thirty-five leaves the first room for cache misses and noise and is the second's third.
private const val LIMIT = 35.0

/**
 * Fails when [work] on an [input] of ten times [size] takes 35 times as long as on one of [size],
 * or longer. Each input is built untimed and used once. The two are timed in turn five times, after
 * a warm-up, and the fastest of each compared; a size whose work is too quick to time is doubled.
 */
internal fun <T> assertGrowsLinearly(
    what: String,
    size: Int,
    input: (n: Int) -> T,
    work: (T) -> Unit,
) {
    // Microseconds for the work on [count] inputs of [n].
    fun time(
        n: Int,
        count: Int,
    ): Long {
        val inputs = List(count) { input(n) }
        val started = TimeSource.Monotonic.markNow()
        inputs.forEach(work)
        return started.elapsedNow().inWholeMicroseconds
    }
    var n = size
    repeat(2) {
        time(n, FACTOR)
        time(n * FACTOR, 1)
    }
    while (time(n, FACTOR) < 20_000 && n < size * 16) n *= 2
    var small = Long.MAX_VALUE
    var large = Long.MAX_VALUE
    repeat(5) {
        small = minOf(small, time(n, FACTOR))
        large = minOf(large, time(n * FACTOR, 1))
    }
    val ratio = FACTOR * large.toDouble() / small
    if (ratio >= LIMIT) {
        fail(
            "$what grows faster than its size: ${ms(small / FACTOR)} ms at $n, ${ms(large)} ms at ${n * FACTOR}, " +
                "${(ratio * 10).roundToInt() / 10.0} times as long for $FACTOR times the size; the limit is $LIMIT",
        )
    }
}

private fun ms(micros: Long) = (micros / 100) / 10.0
