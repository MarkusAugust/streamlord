package io.github.markusaugust.streamlord.core

import io.github.markusaugust.streamlord.core.domain.ElementsGuard
import io.github.markusaugust.streamlord.core.domain.interpolate
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

/**
 * The walks over markup grow with the markup: each of these took from seconds to the better part of
 * a minute when a step searched to the end of the string, or rescanned what came before, every time.
 * The bounds are generous, for a slow machine; the quadratic walks were tens of times over them.
 */
class MarkupScanTimingTest {
    private fun assertQuick(
        what: String,
        bound: Duration = 3.seconds,
        block: () -> Unit,
    ) {
        block()
        val took = measureTime(block)
        assertTrue(took < bound, "$what took $took")
    }

    @Test
    fun `many comments are walked once`() {
        val comments = "<!--a-->".repeat(100_000)
        assertQuick("the guard over 800 KB of comments") { ElementsGuard.check(comments) }
        assertQuick("the guard over comments closed by --!>") { ElementsGuard.check("<!--a--!>".repeat(100_000)) }
        assertQuick("interpolate after 100000 comments") { interpolate("$comments<p>%s</p>", "x") }
    }

    @Test
    fun `many Html values are each judged without reading the markup before them again`() {
        val row = interpolate("<b>%s</b>", "x")
        assertQuick("20000 Html holes") { interpolate("<p>%s</p>".repeat(20_000), List(20_000) { row }) }
    }

    @Test
    fun `end tags that match nothing open do not search the open elements each time`() {
        val markup = "<b>".repeat(20_000) + "</i>".repeat(20_000) + "%s"
        assertQuick("20000 unmatched end tags under 20000 open elements") { interpolate(markup, "x") }
    }

    @Test
    fun `an svg patch over many svg elements is guarded in time`() {
        assertQuick("the guard over many CDATA sections in an svg") {
            ElementsGuard.check("<svg>" + "<![CDATA[x]]><g></g>".repeat(20_000) + "</svg>")
        }
    }
}
