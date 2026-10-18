package io.github.markusaugust.streamlord.demo

import io.github.markusaugust.streamlord.core.domain.DatastarEvent
import io.github.markusaugust.streamlord.core.domain.ElementPatchMode
import io.github.markusaugust.streamlord.core.domain.PatchSignals
import io.github.markusaugust.streamlord.html.patchElements
import kotlinx.html.li
import kotlinx.serialization.Serializable

/** The wall holds this many heads and no more; a store claiming more is not counting heads. */
internal const val HEADS_MAX = 10_000

/** The count the page keeps. The server adds one and hands it back; it remembers nothing. */
@Serializable
public data class FeedSignals(
    val heads: Int = 0,
    /** Whether the page has a wire panel to show the frames in. */
    val wire: Boolean = false,
)

/**
 * The front page's code example, run.
 *
 * The hero shows a handler that appends one `li` to `#feed` and patches `heads`. This is that
 * handler, with the count read from the request instead of written as 13, so pressing the button
 * twice counts to two rather than to thirteen twice. No state here: the store carries the count,
 * the server adds one, and the page is the only place the number lives.
 *
 * A count outside what the wall holds gets a verdict, as a line in the feed, and no new count. An
 * exception would be a 500 for a value nobody could have reached by pressing the button, and the
 * reader would see nothing.
 */
public fun feedEvents(signals: FeedSignals): List<DatastarEvent> {
    if (signals.heads !in 0..HEADS_MAX) {
        return listOf(
            patchElements(selector = "#feed", mode = ElementPatchMode.APPEND) {
                li("feed__verdict") {
                    +"The wall counts from nought to $HEADS_MAX heads; ${signals.heads} is not a count it keeps."
                }
            },
        ).withFrames(signals.wire)
    }

    return listOf(
        patchElements(selector = "#feed", mode = ElementPatchMode.APPEND) {
            li { +"A new head hangs on the wall" }
        },
        PatchSignals("""{"heads": ${signals.heads + 1}}"""),
    ).withFrames(signals.wire)
}
