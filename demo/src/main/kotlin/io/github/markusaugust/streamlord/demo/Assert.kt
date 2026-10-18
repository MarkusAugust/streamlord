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

/** The count the page holds, as `/feed` reads it, and the count the reader's field says to expect. */
@Serializable
public data class AssertSignals(
    val heads: Int = 0,
    val expect: Int = 0,
)

/**
 * The front page's test panel: the feed, read back by streamlord-test and held to two assertions.
 *
 * The events are the ones `/feed` sends for the same count, without the wire panel, put on the
 * wire and read back with `datastarEvents`, which is what a test against the route does. The two
 * assertions are the two lines the panel shows beside the field. A count the wall cannot hold
 * is the feed's own verdict, and then the first assertion is what fails, which is the truth.
 *
 * A failure is the reader's `AssertionError`, and its message goes to the page as it is, since
 * the message is the point: it prints the whole stream, so the first question a red test raises
 * is answered in the same breath.
 */
public fun assertEvents(signals: AssertSignals): List<DatastarEvent> {
    if (signals.expect !in 0..HEADS_MAX) {
        return listOf(
            assertionPatch {
                div("fs-alert") {
                    attributes["data-color"] = "warning"
                    p("fs-alert__title") { +"Not a count" }
                    p { +"The wall counts from nought to $HEADS_MAX heads; ${signals.expect} is not a count it keeps." }
                }
            },
        )
    }

    val wire = feedEvents(FeedSignals(heads = signals.heads)).joinToString("") { SseEncoder.encode(it) }
    val events = datastarEvents(wire)
    val failure =
        try {
            events.assertPatchElements(selector = "#feed", mode = ElementPatchMode.APPEND, containing = "A new head")
            events.assertSignal("heads", signals.expect)
            null
        } catch (e: AssertionError) {
            e.message ?: "AssertionError"
        }

    return listOf(
        assertionPatch {
            if (failure == null) {
                div("fs-alert") {
                    attributes["data-color"] = "success"
                    p("fs-alert__title") { +"Both assertions passed." }
                    p { +"The stream carried ${events.size} ${if (events.size == 1) "event" else "events"}." }
                }
            } else {
                div("fs-alert") {
                    attributes["data-color"] = "danger"
                    pre("assertion__said") { +failure }
                }
            }
        },
    )
}

private fun assertionPatch(block: TagConsumer<*>.() -> Unit): DatastarEvent =
    patchElements(selector = "#assertion", mode = ElementPatchMode.INNER) { block() }
