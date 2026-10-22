package io.github.markusaugust.streamlord.demo

import io.github.markusaugust.streamlord.core.domain.DatastarEvent
import io.github.markusaugust.streamlord.core.domain.ElementPatchMode
import io.github.markusaugust.streamlord.core.domain.PatchSignals
import io.github.markusaugust.streamlord.html.patchElements
import kotlinx.html.li
import kotlinx.serialization.Serializable

/** The wall holds this many heads and no more; a store claiming more is not counting heads. */
internal const val HEADS_MAX = 10_000

/** The count the front page arrives with, and the count the test panel's stream starts from. */
public const val FEED_START: Int = 13

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
 * The front page shows a handler that reads `heads`, appends one `li` to `#feed` and patches
 * `heads` one higher. This is that handler, so the server counts on from whatever the page
 * holds: two presses are 14 and then 15, not 14 twice. No state here:
 * the store carries the count, the server adds one, and the page is the only place the number
 * lives.
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

/**
 * The panel as the page arrived with it: the list and the frames emptied, the count back at 13.
 *
 * A route, because nothing on the page can do it. No Datastar attribute removes what the server
 * appended, and `data-text` on the list would undo the appends themselves, since it rewrites the
 * text on every change under it. An inner patch of nothing is the one way to clear a container.
 */
public fun resetEvents(): List<DatastarEvent> =
    listOf(
        patchElements(selector = "#feed", mode = ElementPatchMode.INNER) {},
        patchElements(selector = "#wire", mode = ElementPatchMode.INNER) {},
        PatchSignals("""{"heads": $FEED_START}"""),
    )
