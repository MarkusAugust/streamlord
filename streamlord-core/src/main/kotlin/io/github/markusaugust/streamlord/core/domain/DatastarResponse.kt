package io.github.markusaugust.streamlord.core.domain

import io.github.markusaugust.streamlord.core.DatastarEventValidationException
import io.github.markusaugust.streamlord.core.json.JsonWriter
import io.github.markusaugust.streamlord.core.protocol.DatastarProtocol
import org.intellij.lang.annotations.Language

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
    /** Full `Content-Type` value: `text/html` and `text/javascript` with `charset=utf-8`, `application/json` without. */
    public val contentType: String

    /** The response body. */
    public val body: String

    /** Datastar option headers to set on the response. */
    public val headers: Map<String, String>
}

/** A `text/html` body patched as elements. Mirrors [PatchElements]. */
public data class ElementsResponse(
    @Language("HTML") val elements: String,
    val selector: String? = null,
    val mode: ElementPatchMode = ElementPatchMode.DEFAULT,
    val namespace: ElementNamespace = ElementNamespace.DEFAULT,
    val useViewTransition: Boolean = false,
) : DatastarResponse {
    init {
        // An inner patch of nothing empties its target, and a remove needs no elements at all.
        if (Wire.noElements(elements, mode, selector) && mode != ElementPatchMode.INNER && mode != ElementPatchMode.REMOVE) {
            throw DatastarEventValidationException("elements must not be blank")
        }
        selector?.let { Wire.selector(it) }
        // A header travels as Latin-1 to the client, which reads it so, and some servers send UTF-8
        // and others drop what Latin-1 lacks: only printable ASCII arrives as written.
        selector?.firstOrNull { it !in ' '..'~' }?.let {
            throw DatastarEventValidationException(
                "A selector in a response header must be printable ASCII, not U+%04X; send it in a stream instead".format(it.code),
            )
        }
        if (mode.requiresSelector && selector == null) {
            throw DatastarEventValidationException("Patch mode '${mode.wire}' requires a selector")
        }
    }

    override val contentType: String get() = DatastarProtocol.CONTENT_TYPE_HTML
    override val body: String get() = Wire.loneSurrogatesReplaced(elements)
    override val headers: Map<String, String>
        get() =
            buildMap {
                selector?.let { put(DatastarProtocol.Headers.SELECTOR, it) }
                if (mode != ElementPatchMode.DEFAULT) put(DatastarProtocol.Headers.MODE, mode.wire)
                if (namespace != ElementNamespace.DEFAULT) put(DatastarProtocol.Headers.NAMESPACE, namespace.wire)
                if (useViewTransition) put(DatastarProtocol.Headers.USE_VIEW_TRANSITION, "true")
            }
}

/** An `application/json` body patched as signals. Mirrors [PatchSignals]. */
public data class SignalsResponse(
    @Language("JSON") val signals: String,
    val onlyIfMissing: Boolean = false,
) : DatastarResponse {
    init {
        Wire.signals(signals)
    }

    override val contentType: String get() = DatastarProtocol.CONTENT_TYPE_JSON
    override val body: String get() = Wire.loneSurrogatesEscaped(signals)
    override val headers: Map<String, String>
        get() = if (onlyIfMissing) mapOf(DatastarProtocol.Headers.ONLY_IF_MISSING to "true") else emptyMap()
}

/**
 * A `text/javascript` body executed in the browser. Mirrors [ExecuteScript], also in that a
 * surrogate without its other half, in the script or an attribute, reaches the client as U+FFFD,
 * as it does in the `<script>` element a stream sends: UTF-8 has no bytes for it.
 */
public data class ScriptResponse(
    @Language("JavaScript") val script: String,
    val attributes: Map<String, String> = emptyMap(),
) : DatastarResponse {
    init {
        if (script.isBlank()) throw DatastarEventValidationException("script must not be blank")
        attributes.keys.forEach(Wire::attributeName)
        attributes.values.forEach { Wire.singleLine(it, "script attribute") }
    }

    override val contentType: String get() = DatastarProtocol.CONTENT_TYPE_JAVASCRIPT
    override val body: String get() = Wire.loneSurrogatesReplaced(script)
    override val headers: Map<String, String>
        get() =
            if (attributes.isEmpty()) {
                emptyMap()
            } else {
                // As \u escapes: the header reaches the client as Latin-1, and JSON.parse restores them.
                mapOf(
                    DatastarProtocol.Headers.SCRIPT_ATTRIBUTES to
                        asciiOnly(JsonWriter.write(attributes.mapValues { Wire.loneSurrogatesReplaced(it.value) })),
                )
            }
}

/** JSON text with every character past printable ASCII written as a `\u` escape, which only a string can hold. */
private fun asciiOnly(json: String): String =
    buildString(json.length) {
        for (c in json) if (c in ' '..'~') append(c) else append("\\u%04x".format(c.code))
    }
