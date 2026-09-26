package io.github.markusaugust.streamlord.core.protocol

import io.github.markusaugust.streamlord.core.domain.DatastarEvent
import io.github.markusaugust.streamlord.core.domain.ElementNamespace
import io.github.markusaugust.streamlord.core.domain.ElementPatchMode
import io.github.markusaugust.streamlord.core.domain.ExecuteScript
import io.github.markusaugust.streamlord.core.domain.PatchElements
import io.github.markusaugust.streamlord.core.domain.PatchSignals
import io.github.markusaugust.streamlord.core.domain.Wire
import io.github.markusaugust.streamlord.core.protocol.DatastarProtocol.DataLines

/**
 * Turns a [DatastarEvent] into the text the browser reads. Pure, stateless, thread-safe:
 * the same event always yields the same output.
 *
 * Only non-default options are written, exactly as the SDK specification prescribes, and in
 * the order it lists them. The Datastar client groups data lines by their first word, so
 * order does not matter to it, but a predictable wire makes golden tests and debugging sane.
 */
public object SseEncoder {
    /** Encode one event as a complete SSE frame, blank line included. */
    public fun encode(event: DatastarEvent): String = frame(event).render()

    /** Decompose an event into its SSE fields. */
    public fun frame(event: DatastarEvent): SseFrame = when (event) {
        is PatchElements -> SseFrame(
            event = DatastarProtocol.Events.PATCH_ELEMENTS,
            id = event.eventId,
            retry = event.retry,
            dataLines = patchElementsLines(event),
        )

        is PatchSignals -> SseFrame(
            event = DatastarProtocol.Events.PATCH_SIGNALS,
            id = event.eventId,
            retry = event.retry,
            dataLines = patchSignalsLines(event),
        )

        is ExecuteScript -> frame(event.toPatchElements())
    }

    /** An SSE comment. Invisible to the client; useful as a heartbeat or to force a flush. */
    public fun comment(text: String): String =
        Wire.lines(text).joinToString(separator = "") { ": $it\n" } + "\n"

    private fun patchElementsLines(event: PatchElements): List<String> = buildList {
        event.selector?.let { add("${DataLines.SELECTOR} $it") }
        if (event.mode != ElementPatchMode.DEFAULT) add("${DataLines.MODE} ${event.mode.wire}")
        if (event.useViewTransition) {
            add("${DataLines.USE_VIEW_TRANSITION} true")
            event.viewTransitionSelector?.let { add("${DataLines.VIEW_TRANSITION_SELECTOR} $it") }
        }
        if (event.namespace != ElementNamespace.DEFAULT) add("${DataLines.NAMESPACE} ${event.namespace.wire}")
        event.elements?.let { html -> Wire.lines(html).forEach { add("${DataLines.ELEMENTS} $it") } }
    }

    private fun patchSignalsLines(event: PatchSignals): List<String> = buildList {
        if (event.onlyIfMissing) add("${DataLines.ONLY_IF_MISSING} true")
        Wire.lines(event.signals).forEach { add("${DataLines.SIGNALS} $it") }
    }
}
