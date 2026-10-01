package io.github.markusaugust.streamlord.core.domain

import io.github.markusaugust.streamlord.core.DatastarEventValidationException
import io.github.markusaugust.streamlord.core.protocol.DatastarAttributes
import org.intellij.lang.annotations.Language
import kotlin.time.Duration

/**
 * An event bound for the browser over the Datastar SSE protocol (v1.0.4).
 *
 * The protocol itself knows only two rites, `datastar-patch-elements` and
 * `datastar-patch-signals`. Streamlord models a third, [ExecuteScript], because the SDK
 * specification defines it as sugar over the first and every developer wants it. Older
 * incantations such as `merge-fragments`, `remove-fragments`, `merge-signals` and
 * `remove-signals` belong to Datastar 0.x and are gone: removal is a [PatchElements] with
 * [ElementPatchMode.REMOVE], and a signal is removed by patching it to `null`.
 *
 * Every event is an immutable value. Validation happens at construction, so an event that
 * exists can always be written to the wire.
 */
public sealed interface DatastarEvent {
    /** Optional SSE `id:` line, used by browsers for replay after reconnection. */
    public val eventId: String?

    /** Optional SSE `retry:` line. `null` leaves the browser default (1000 ms) in place. */
    public val retry: Duration?
}

/**
 * Patch one or more complete HTML elements into the DOM.
 *
 * Datastar demands *elements*, not fragments: each top-level node must be a complete element,
 * and when no [selector] is given each of them must carry an `id` so the client can find its
 * counterpart.
 *
 * @property elements The HTML to patch. May be `null` only when [mode] is [ElementPatchMode.REMOVE].
 * @property selector CSS selector for the target. Required for every mode except OUTER and REPLACE.
 * @property mode How the elements are driven into the DOM. Defaults to OUTER (morph).
 * @property namespace The namespace of the new elements. Defaults to HTML.
 * @property useViewTransition Wrap the patch in the View Transitions API.
 * @property viewTransitionSelector CSS selector of the element to run the view transition on.
 *   Only sent when [useViewTransition] is `true`.
 */
public data class PatchElements(
    @Language("HTML") val elements: String? = null,
    val selector: String? = null,
    val mode: ElementPatchMode = ElementPatchMode.DEFAULT,
    val namespace: ElementNamespace = ElementNamespace.DEFAULT,
    val useViewTransition: Boolean = false,
    val viewTransitionSelector: String? = null,
    override val eventId: String? = null,
    override val retry: Duration? = null,
) : DatastarEvent {
    init {
        validateCommon(eventId, retry)
        selector?.let { Wire.selector(it) }
        viewTransitionSelector?.let { Wire.selector(it, "viewTransitionSelector") }
        if (mode.requiresSelector && selector == null) {
            throw DatastarEventValidationException("Patch mode '${mode.wire}' requires a selector")
        }
        if (mode != ElementPatchMode.REMOVE && elements.isNullOrBlank()) {
            throw DatastarEventValidationException("Patch mode '${mode.wire}' requires elements")
        }
    }

    public companion object {
        /** A patch that removes every element matching [selector]. */
        public fun remove(
            selector: String,
            useViewTransition: Boolean = false,
            eventId: String? = null,
            retry: Duration? = null,
        ): PatchElements =
            PatchElements(
                selector = selector,
                mode = ElementPatchMode.REMOVE,
                useViewTransition = useViewTransition,
                eventId = eventId,
                retry = retry,
            )
    }
}

/**
 * Patch signals into the browser's signal store using RFC 7386 JSON Merge Patch semantics:
 * a key with a value is set, a key set to `null` is removed, nested objects merge recursively.
 *
 * @property signals A JSON object as text. Use a codec or the built-in
 *   [io.github.markusaugust.streamlord.core.json.JsonWriter] to produce it.
 * @property onlyIfMissing Only set signals that do not already exist on the client.
 */
public data class PatchSignals(
    @Language("JSON") val signals: String,
    val onlyIfMissing: Boolean = false,
    override val eventId: String? = null,
    override val retry: Duration? = null,
) : DatastarEvent {
    init {
        validateCommon(eventId, retry)
        if (signals.isBlank()) throw DatastarEventValidationException("signals must not be blank")
    }
}

/**
 * Execute JavaScript in the browser. On the wire this is a [PatchElements] that appends a
 * `<script>` element to `body`, exactly as the Datastar SDK specification prescribes.
 *
 * @property script One or more lines of JavaScript. `</script` inside it is neutralised.
 * @property autoRemove Add `data-effect="el.remove()"` so the script tag vanishes after it runs. The
 *   attribute follows [io.github.markusaugust.streamlord.core.protocol.DatastarAttributes.prefix], so the
 *   aliased bundle gets `data-star-effect`.
 * @property attributes Extra attributes for the script tag, such as `type`. Not a CSP nonce:
 *   under CSP mode Datastar re-creates every script it patches in and sets the nonce itself,
 *   from the one the page was loaded with, so a nonce passed here is overwritten. With CSP mode
 *   off it is simply ignored. The nonce that matters is the one on the document's `<html>`.
 */
public data class ExecuteScript(
    @Language("JavaScript") val script: String,
    val autoRemove: Boolean = true,
    val attributes: Map<String, String> = emptyMap(),
    override val eventId: String? = null,
    override val retry: Duration? = null,
) : DatastarEvent {
    init {
        validateCommon(eventId, retry)
        if (script.isBlank()) throw DatastarEventValidationException("script must not be blank")
        attributes.keys.forEach(Wire::attributeName)
    }

    /** The script element exactly as it is sent to the browser. */
    public val scriptTag: String
        get() =
            buildString {
                append("<script")
                for ((name, value) in attributes) {
                    append(' ')
                        .append(name)
                        .append("=\"")
                        .append(Wire.escapeAttribute(value))
                        .append('"')
                }
                if (autoRemove) append(' ').append(DatastarAttributes.name("effect")).append("=\"el.remove()\"")
                append('>')
                append(Wire.escapeScriptBody(script))
                append("</script>")
            }

    /** The wire-level event this script becomes. */
    public fun toPatchElements(): PatchElements =
        PatchElements(
            elements = scriptTag,
            selector = "body",
            mode = ElementPatchMode.APPEND,
            eventId = eventId,
            retry = retry,
        )
}

private fun validateCommon(
    eventId: String?,
    retry: Duration?,
) {
    eventId?.let {
        if (it.isEmpty()) throw DatastarEventValidationException("eventId must not be empty")
        Wire.singleLine(it, "eventId")
    }
    retry?.let {
        if (!it.isPositive() || it.isInfinite()) {
            throw DatastarEventValidationException("retry must be a positive, finite duration")
        }
    }
}
