package io.github.markusaugust.streamlord.demo

import io.github.markusaugust.streamlord.core.domain.DatastarEvent
import io.github.markusaugust.streamlord.core.domain.ElementPatchMode
import io.github.markusaugust.streamlord.core.protocol.SseEncoder
import io.github.markusaugust.streamlord.html.patchElements
import io.github.markusaugust.streamlord.test.datastarEvents
import kotlinx.html.TagConsumer
import kotlinx.html.div
import kotlinx.html.p
import kotlinx.html.pre
import kotlinx.serialization.Serializable

/** Whether the panel's second button was pressed: the test then expects 99, which the feed never says. */
@Serializable
public data class AssertSignals(
    val broken: Boolean = false,
)

/** What the test expects, and what the broken one expects instead. */
internal const val EXPECTED = FEED_START + 1
internal const val BROKEN_EXPECTATION = 99

/**
 * The front page's test panel: a fresh feed from 13, read back by streamlord-test and held to
 * two assertions.
 *
 * The events are the ones `/feed` sends for 13, without the wire panel, put on the wire and
 * read back with `datastarEvents`, which is what a test against the route does. The count on
 * the page is not read: the panel shows this stream, so this stream is what it asserts. The
 * two assertions are the two lines the panel shows, and `broken` swaps the second one's 14
 * for 99, so the reader can see a test fail.
 *
 * A failure is the reader's `AssertionError`, and its message goes to the page as it is, since
 * the message is the point: it prints the whole stream, so the first question a red test raises
 * is answered in the same breath.
 */
public fun assertEvents(signals: AssertSignals): List<DatastarEvent> {
    val expected = if (signals.broken) BROKEN_EXPECTATION else EXPECTED
    val wire = feedEvents(FeedSignals(heads = FEED_START)).joinToString("") { SseEncoder.encode(it) }
    val events = datastarEvents(wire)
    val failure =
        try {
            events.assertPatchElements(selector = "#feed", mode = ElementPatchMode.APPEND, containing = "A new head")
            events.assertSignal("heads", expected)
            null
        } catch (e: AssertionError) {
            e.message ?: "AssertionError"
        }

    return listOf(
        assertionPatch {
            if (failure == null) {
                div("lt-alert") {
                    attributes["data-color"] = "success"
                    p("lt-alert__title") { +"Both assertions passed." }
                    p { +"The stream carried ${events.size} ${if (events.size == 1) "event" else "events"}." }
                }
            } else {
                div("lt-alert") {
                    attributes["data-color"] = "danger"
                    pre("assertion__said") { +failure }
                }
                p("lt-paragraph assertion__why") {
                    attributes["data-size"] = "small"
                    +"This is what the test reader prints when a stream is not what the code said."
                }
            }
        },
    )
}

private fun assertionPatch(block: TagConsumer<*>.() -> Unit): DatastarEvent =
    patchElements(selector = "#assertion", mode = ElementPatchMode.INNER) { block() }
