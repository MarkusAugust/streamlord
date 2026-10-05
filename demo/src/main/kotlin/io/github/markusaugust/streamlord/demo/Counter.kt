package io.github.markusaugust.streamlord.demo

import io.github.markusaugust.streamlord.core.domain.DatastarEvent
import io.github.markusaugust.streamlord.core.domain.ElementPatchMode
import io.github.markusaugust.streamlord.core.domain.PatchSignals
import io.github.markusaugust.streamlord.html.patchElements
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable

/** How far it counts, and how long it waits between numbers. */
private const val TICKS = 30
private const val EVERY_MS = 400L

/**
 * The signals the counter runs on.
 *
 * [running] is the whole of the cancel button. Datastar sends the store with every request, so
 * the server can see that a stream is already open and answer the second click by ending it
 * rather than starting another.
 */
@Serializable
public data class CounterSignals(
    val running: Boolean = false,
    val count: Int = 0,
    /** Whether the page has a wire panel to show the frames in. The CSP page has none. */
    val wire: Boolean = false,
)

/**
 * One connection, thirty numbers, and a button that stops it.
 *
 * This is the demo that a page of prose cannot be. A search is a request and an answer; this is
 * a single response that stays open for twelve seconds and writes to the page the whole time,
 * which is the thing Server-Sent Events are for and the reason the adapter takes a [Flow].
 *
 * The cancel button is Datastar's own doing, not ours. A request keeps an AbortController per
 * method and URL, so when anything on the page asks for the same endpoint again, the browser
 * aborts the stream that is still running before it sends anything. Ktor sees the disconnect and
 * cancels the coroutine, which is why there is no bookkeeping here and nothing to leak: the
 * `flow` builder simply stops being collected.
 *
 * The second request still arrives, though, and it is the one that puts the page back in order.
 * It reports what the counter reached and turns the button back into Start. The server decides
 * that from the signals it was sent, so the page holds no state of its own and there is no
 * JavaScript anywhere in it.
 */
public fun counterEvents(signals: CounterSignals): Flow<DatastarEvent> =
    flow {
        suspend fun send(vararg events: DatastarEvent) = events.toList().withFrames(signals.wire).forEach { emit(it) }

        if (signals.running) {
            send(
                PatchSignals("""{"running": false}"""),
                patchElements(selector = "#counter", mode = ElementPatchMode.INNER) {
                    counter(signals.count, running = false, stopped = true)
                },
            )
            return@flow
        }

        send(PatchSignals("""{"running": true}"""))
        for (n in 1..TICKS) {
            delay(EVERY_MS)
            send(
                PatchSignals("""{"count": $n}"""),
                patchElements(selector = "#counter", mode = ElementPatchMode.INNER) {
                    counter(n, running = n < TICKS, stopped = false)
                },
            )
        }
        send(PatchSignals("""{"running": false}"""))
    }
