package io.github.markusaugust.streamlord.core

import io.github.markusaugust.streamlord.fixtures.assertGrowsLinearly
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertFailsWith

/** What assertGrowsLinearly, which the timing tests are held to, lets through and what it stops. */
class GrowthTest {
    private var sink = 0L

    private fun linear(n: Int) {
        var x = 0L
        for (i in 0 until n * 20) x += (i xor (x ushr 3).toInt()).toLong()
        sink += x
    }

    private fun quadratic(n: Int) {
        var x = 0L
        for (i in 0 until n) for (j in 0 until n) x += ((i * j) xor (x ushr 3).toInt()).toLong()
        sink += x
    }

    // A cost each call has whatever its size, as a parser set up for every input: some tens of
    // microseconds, and slowed by a busy machine as the rest of the work is.
    private fun fixedCost() = linear(1_200)

    @Test
    fun `work that grows with its size passes, with a fixed cost too, and so does sorting`() {
        assertGrowsLinearly("linear", 1_000, { it }) { linear(it) }
        assertGrowsLinearly("linear and a fixed cost", 1_000, { it }) {
            fixedCost()
            linear(it)
        }
        assertGrowsLinearly("sorting", 1_000, { n -> IntArray(n) { Random.nextInt() } }) { it.sort() }
    }

    @Test
    fun `work that grows with its square fails, behind a fixed cost too`() {
        assertFailsWith<AssertionError> { assertGrowsLinearly("quadratic", 100, { it }) { quadratic(it) } }
        assertFailsWith<AssertionError> {
            assertGrowsLinearly("quadratic and a fixed cost", 100, { it }) {
                fixedCost()
                quadratic(it)
            }
        }
    }
}
