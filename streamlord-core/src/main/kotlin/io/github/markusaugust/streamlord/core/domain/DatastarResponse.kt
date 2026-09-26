package io.github.markusaugust.streamlord.core.domain

import io.github.markusaugust.streamlord.core.DatastarEventValidationException
import io.github.markusaugust.streamlord.core.json.JsonWriter
import io.github.markusaugust.streamlord.core.protocol.DatastarProtocol

/**
 * A plain, non-streaming HTTP response the Datastar client (1.0.2+) understands without SSE.
 *
 * When a fetch action receives `text/html`, `application/json` or `text/javascript` instead of
 * an event stream, the client turns the body into a single patch and reads its options from
 * `datastar-*` response headers. This is the cheapest possible reply for a one-shot request:
 * no stream, no framing, one body.
 *
 * Adapters translate a [DatastarResponse] into the framework's response type.
 */
public sealed interface DatastarResponse {
    /** Full `Content-Type` value, charset included. */
    public val contentType: String

    /** The response body. */
    public val body: String

    /** Datastar option headers to set on the response. */
    public val headers: Map<String, String>
}

/** A `text/html` body patched as elements. Mirrors [PatchElements]. */
public data class ElementsResponse(
    val elements: String,
    val selector: String? = null,
    val mode: ElementPatchMode = ElementPatchMode.DEFAULT,
    val namespace: ElementNamespace = ElementNamespace.DEFAULT,
    val useViewTransition: Boolean = false,
) : DatastarResponse {
    init {
        if (elements.isBlank()) throw DatastarEventValidationException("elements must not be blank")
        selector?.let { Wire.selector(it) }
        if (mode.requiresSelector && selector == null) {
            throw DatastarEventValidationException("Patch mode '${mode.wire}' requires a selector")
        }
    }

    override val contentType: String get() = DatastarProtocol.CONTENT_TYPE_HTML
    override val body: String get() = elements
    override val headers: Map<String, String>
        get() = buildMap {
            selector?.let { put(DatastarProtocol.Headers.SELECTOR, it) }
            if (mode != ElementPatchMode.DEFAULT) put(DatastarProtocol.Headers.MODE, mode.wire)
            if (namespace != ElementNamespace.DEFAULT) put(DatastarProtocol.Headers.NAMESPACE, namespace.wire)
            if (useViewTransition) put(DatastarProtocol.Headers.USE_VIEW_TRANSITION, "true")
        }
}

/** An `application/json` body patched as signals. Mirrors [PatchSignals]. */
public data class SignalsResponse(
    val signals: String,
    val onlyIfMissing: Boolean = false,
) : DatastarResponse {
    init {
        if (signals.isBlank()) throw DatastarEventValidationException("signals must not be blank")
    }

    override val contentType: String get() = DatastarProtocol.CONTENT_TYPE_JSON
    override val body: String get() = signals
    override val headers: Map<String, String>
        get() = if (onlyIfMissing) mapOf(DatastarProtocol.Headers.ONLY_IF_MISSING to "true") else emptyMap()
}

/** A `text/javascript` body executed in the browser. Mirrors [ExecuteScript]. */
public data class ScriptResponse(
    val script: String,
    val attributes: Map<String, String> = emptyMap(),
) : DatastarResponse {
    init {
        if (script.isBlank()) throw DatastarEventValidationException("script must not be blank")
        attributes.keys.forEach(Wire::attributeName)
        attributes.values.forEach { Wire.singleLine(it, "script attribute") }
    }

    override val contentType: String get() = DatastarProtocol.CONTENT_TYPE_JAVASCRIPT
    override val body: String get() = script
    override val headers: Map<String, String>
        get() = if (attributes.isEmpty()) {
            emptyMap()
        } else {
            mapOf(DatastarProtocol.Headers.SCRIPT_ATTRIBUTES to JsonWriter.write(attributes))
        }
}
