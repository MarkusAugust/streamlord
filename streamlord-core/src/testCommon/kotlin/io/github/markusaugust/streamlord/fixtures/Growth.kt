package io.github.markusaugust.streamlord.fixtures

import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.test.fail
import kotlin.time.TimeSource

/*
 * Compiled into the tests of core and of the analysis on the JVM and on Node, and of the IntelliJ
 * plugin. A bound on one time fails on a busy machine; a ratio of times holds, when they are taken
 * the same way. So the work is timed on 64 inputs of a size, on 8 of eight times it and on 1 of 64
 * times it: each about as long as the others, and a busy machine slows all three alike.
 */

private const val STEP = 8

// Over 64 times the size, work that grows with it costs as much per unit at the end as at the start,
// and work that grows with its square 64 times as much. Eight times lies midway, as the size to the
// power 1.5; on a busy machine the first stayed under three times.
private const val LIMIT = 8.0

// A measurement this long means the work has run away: the test stops rather than wait for it.
private const val DEADLINE_MICROS = 20_000_000L

/**
 * Fails when [work] costs eight times as much per unit of size, or more, on an [input] of 64 times
 * [size] as on one of [size]. Each input is built untimed and used once. The three sizes are timed
 * in turn five times, after a warm-up, and the fastest of each kept. A size whose work is too quick
 * to time, or whose time is mostly a cost each call has whatever its size, is doubled first. It
 * fails as soon as one measurement takes 20 seconds, or when the growth from the smallest size to
 * the middle one is past the limit or says the largest would take that long.
 */
internal fun <T> assertGrowsLinearly(
    what: String,
    size: Int,
    input: (n: Int) -> T,
    work: (T) -> Unit,
) {
    fun report(
        n: Int,
        micros: List<Long>,
    ) = micros.indices.joinToString(", ") { "${ms(micros[it])} ms for ${count(it)} at ${n * sizeOf(it)}" }

    // Microseconds for the work on the inputs of measurement [k], of n times [sizeOf] k.
    fun time(
        n: Int,
        k: Int,
    ): Long {
        val inputs = List(count(k)) { input(n * sizeOf(k)) }
        val started = TimeSource.Monotonic.markNow()
        inputs.forEach(work)
        val took = started.elapsedNow().inWholeMicroseconds
        if (took > DEADLINE_MICROS) fail("$what ran away: ${ms(took)} ms for ${count(k)} at ${n * sizeOf(k)}")
        return took
    }
    var n = size
    repeat(2) { for (k in 0..1) time(n, k) }
    while (true) {
        val small = time(n, 0)
        // A cost each call has whatever its size, which 64 calls pay where 8 pay it an eighth as often,
        // hides how the work grows: the size is doubled until it is under a quarter of the smallest's time.
        val fixed = (small - time(n, 1)) / (count(0) - count(1)).toDouble()
        val tooQuick = small < 30_000 && n < size * 8
        val fixedBound = fixed * count(0) >= small / 4.0 && n < size * 64
        if (!tooQuick && !fixedBound) break
        n *= 2
    }
    val best = LongArray(3) { Long.MAX_VALUE }
    repeat(5) {
        for (k in 0..2) {
            if (k == 2) {
                val step = best[1].toDouble() / best[0]
                if (step >= LIMIT || best[1] * step > DEADLINE_MICROS) fail("$what grows faster than its size: ${report(n, best.take(2))}")
            }
            best[k] = minOf(best[k], time(n, k))
        }
    }
    val growth = best[2].toDouble() / best[0]
    if (growth >= LIMIT) {
        val exponent = 1 + ln(growth) / ln(sizeOf(2).toDouble())
        fail(
            "$what grows faster than its size: ${report(n, best.toList())}: ${(growth * 10).roundToInt() / 10.0} times the cost per " +
                "unit at 64 times the size, as the size to the power ${(exponent * 100).roundToInt() / 100.0}; the limit is $LIMIT",
        )
    }
}

private fun sizeOf(k: Int): Int =
    if (k == 0) {
        1
    } else if (k == 1) {
        STEP
    } else {
        STEP * STEP
    }

private fun count(k: Int): Int = sizeOf(2 - k)

private fun ms(micros: Long) = (micros / 100) / 10.0
