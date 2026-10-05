package io.github.markusaugust.streamlord.demo

import io.github.markusaugust.streamlord.core.domain.DatastarEvent
import io.github.markusaugust.streamlord.core.domain.ElementPatchMode
import io.github.markusaugust.streamlord.core.protocol.SseEncoder
import io.github.markusaugust.streamlord.html.patchElements

/**
 * These events, followed by a patch that shows their own frames in the live page's wire panel.
 *
 * Only when [shown], which is the page saying it has a `#wire` to patch. The live page sends it
 * with every request, so whichever demo the reader used last is what the panel shows. The
 * counter calls this once per tick, so the panel follows the stream as it runs.
 *
 * The search builds its own, because it also answers the masthead on every other page.
 */
internal fun List<DatastarEvent>.withFrames(shown: Boolean): List<DatastarEvent> =
    if (!shown) {
        this
    } else {
        this +
            patchElements(selector = "#wire", mode = ElementPatchMode.INNER) {
                frames(map { SseEncoder.encode(it) })
            }
    }
