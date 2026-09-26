package io.github.markusaugust.streamlord.html

import io.github.markusaugust.streamlord.core.domain.ElementNamespace
import io.github.markusaugust.streamlord.core.domain.ElementPatchMode
import io.github.markusaugust.streamlord.core.domain.ElementsResponse
import io.github.markusaugust.streamlord.core.domain.PatchElements
import io.github.markusaugust.streamlord.core.port.driving.DatastarStream
import kotlinx.html.TagConsumer
import kotlinx.html.stream.appendHTML
import kotlin.time.Duration

/**
 * Render a kotlinx.html block to a compact string. Tags are closed, attributes are escaped,
 * and there is no pretty-printing: whitespace between elements is a bug in SSE, not a feature.
 *
 * ```kotlin
 * val html = elements { div { id = "feed"; +"Ashes" } }
 * ```
 */
public inline fun elements(block: TagConsumer<*>.() -> Unit): String =
    StringBuilder().also { it.appendHTML(prettyPrint = false).block() }.toString()

/** A [PatchElements] event whose HTML comes from the kotlinx.html DSL. Ideal inside a `Flow`. */
public inline fun patchElements(
    selector: String? = null,
    mode: ElementPatchMode = ElementPatchMode.DEFAULT,
    namespace: ElementNamespace = ElementNamespace.DEFAULT,
    useViewTransition: Boolean = false,
    viewTransitionSelector: String? = null,
    eventId: String? = null,
    retry: Duration? = null,
    block: TagConsumer<*>.() -> Unit,
): PatchElements = PatchElements(
    elements = elements(block),
    selector = selector,
    mode = mode,
    namespace = namespace,
    useViewTransition = useViewTransition,
    viewTransitionSelector = viewTransitionSelector,
    eventId = eventId,
    retry = retry,
)

/**
 * Patch elements written in the kotlinx.html DSL straight into the stream.
 *
 * ```kotlin
 * stream.patchElements(selector = "#feed", mode = ElementPatchMode.APPEND) {
 *     li { +"A new head on the wall" }
 * }
 * ```
 */
public suspend inline fun DatastarStream.patchElements(
    selector: String? = null,
    mode: ElementPatchMode = ElementPatchMode.DEFAULT,
    namespace: ElementNamespace = ElementNamespace.DEFAULT,
    useViewTransition: Boolean = false,
    viewTransitionSelector: String? = null,
    eventId: String? = null,
    retry: Duration? = null,
    block: TagConsumer<*>.() -> Unit,
) {
    send(
        PatchElements(
            elements = elements(block),
            selector = selector,
            mode = mode,
            namespace = namespace,
            useViewTransition = useViewTransition,
            viewTransitionSelector = viewTransitionSelector,
            eventId = eventId,
            retry = retry,
        ),
    )
}

/** A non-SSE [ElementsResponse] whose HTML comes from the kotlinx.html DSL. */
public inline fun elementsResponse(
    selector: String? = null,
    mode: ElementPatchMode = ElementPatchMode.DEFAULT,
    namespace: ElementNamespace = ElementNamespace.DEFAULT,
    useViewTransition: Boolean = false,
    block: TagConsumer<*>.() -> Unit,
): ElementsResponse = ElementsResponse(elements(block), selector, mode, namespace, useViewTransition)
