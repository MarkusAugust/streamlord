package io.github.markusaugust.streamlord.core

import io.github.markusaugust.streamlord.core.domain.ElementsGuard
import io.github.markusaugust.streamlord.core.domain.interpolate
import io.github.markusaugust.streamlord.fixtures.assertGrowsLinearly
import kotlin.test.Test

/**
 * The walks over markup grow with the markup: each of these took from seconds to the better part of
 * a minute when a step searched to the end of the string, or rescanned what came before, every time.
 */
class MarkupScanTimingTest {
    @Test
    fun `many comments are walked once`() {
        assertGrowsLinearly("the guard over comments", 1_500, { "<!--a-->".repeat(it) }) { ElementsGuard.check(it) }
        assertGrowsLinearly("the guard over comments closed by --!>", 1_500, { "<!--a--!>".repeat(it) }) { ElementsGuard.check(it) }
        assertGrowsLinearly("interpolate after comments", 1_500, { "<!--a-->".repeat(it) + "<p>%s</p>" }) { interpolate(it, "x") }
    }

    @Test
    fun `many Html values are each judged without reading the markup before them again`() {
        val row = interpolate("<b>%s</b>", "x")
        assertGrowsLinearly("Html values", 300, { n -> "<p>%s</p>".repeat(n) to Array(n) { row } }) { (markup, values) ->
            interpolate(markup, *values)
        }
    }

    @Test
    fun `end tags that match nothing open do not search the open elements each time`() {
        assertGrowsLinearly("unmatched end tags under as many open elements", 300, { n -> "<b>".repeat(n) + "</i>".repeat(n) + "%s" }) {
            interpolate(it, "x")
        }
    }

    @Test
    fun `an svg patch over many svg elements is guarded in time`() {
        val svg = { n: Int -> "<svg>" + "<![CDATA[x]]><g></g>".repeat(n) + "</svg>" }
        assertGrowsLinearly("the guard over CDATA sections in an svg", 300, svg) { ElementsGuard.check(it) }
    }
}
