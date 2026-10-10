package io.github.markusaugust.streamlord.core.protocol

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * The runes of the Datastar 1.0.4 protocol, transcribed from `datastar-sdk-config-v1.json`
 * and the client source. Nothing here is invented; every constant is a word the browser knows.
 */
public object DatastarProtocol {
    /** The Datastar version this SDK speaks. */
    public const val VERSION: String = "1.0.4"

    /** Query parameter (GET, DELETE) and JSON namespace under which the client sends signals. */
    public const val SIGNALS_PARAMETER: String = "datastar"

    /** HTTP methods whose signals travel in the query string rather than the body. */
    public val BODYLESS_METHODS: Set<String> = setOf("GET", "DELETE")

    /** Default SSE reconnection delay. Omitted from the wire when unchanged. */
    public val DEFAULT_RETRY: Duration = 1000.milliseconds

    public const val CONTENT_TYPE_EVENT_STREAM: String = "text/event-stream"
    public const val CONTENT_TYPE_HTML: String = "text/html; charset=utf-8"
    public const val CONTENT_TYPE_JSON: String = "application/json"
    public const val CONTENT_TYPE_JAVASCRIPT: String = "text/javascript; charset=utf-8"

    /** SSE event names. */
    public object Events {
        public const val PATCH_ELEMENTS: String = "datastar-patch-elements"
        public const val PATCH_SIGNALS: String = "datastar-patch-signals"
    }

    /** Data line literals. */
    public object DataLines {
        public const val SELECTOR: String = "selector"
        public const val MODE: String = "mode"
        public const val NAMESPACE: String = "namespace"
        public const val USE_VIEW_TRANSITION: String = "useViewTransition"
        public const val VIEW_TRANSITION_SELECTOR: String = "viewTransitionSelector"
        public const val ELEMENTS: String = "elements"
        public const val SIGNALS: String = "signals"
        public const val ONLY_IF_MISSING: String = "onlyIfMissing"
    }

    /** HTTP headers spoken by the client. */
    public object Headers {
        /** Sent by the client on every fetch action, with the value `true`. */
        public const val REQUEST: String = "Datastar-Request"

        /** Response headers read for non-SSE `text/html` replies. */
        public const val SELECTOR: String = "datastar-selector"
        public const val MODE: String = "datastar-mode"
        public const val NAMESPACE: String = "datastar-namespace"
        public const val USE_VIEW_TRANSITION: String = "datastar-use-view-transition"

        /** Response header read for non-SSE `application/json` replies. */
        public const val ONLY_IF_MISSING: String = "datastar-only-if-missing"

        /** Response header read for non-SSE `text/javascript` replies: a JSON object of attributes. */
        public const val SCRIPT_ATTRIBUTES: String = "datastar-script-attributes"
    }

    /**
     * Headers every SSE response should carry. `Cache-Control` comes from the SDK specification;
     * `X-Accel-Buffering: no` tells nginx-style proxies to stop hoarding the stream.
     */
    public val SSE_RESPONSE_HEADERS: Map<String, String> = mapOf(
        "Cache-Control" to "no-cache",
        "X-Accel-Buffering" to "no",
    )

    /**
     * Whether an `Accept-Encoding` header takes gzip: listed by name or as `*`, with no `q=0`.
     * Names are matched without regard to case, as HTTP says.
     */
    public fun acceptsGzip(acceptEncoding: String?): Boolean {
        if (acceptEncoding.isNullOrBlank()) return false
        var star: Boolean? = null
        for (entry in acceptEncoding.split(',')) {
            val parts = entry.split(';').map { it.trim() }
            val name = parts.first().lowercase()
            val quality =
                parts.drop(1).firstOrNull { it.startsWith("q=", ignoreCase = true) }
                    ?.substring(2)?.toDoubleOrNull() ?: 1.0
            when (name) {
                "gzip", "x-gzip" -> return quality > 0.0
                "*" -> star = quality > 0.0
            }
        }
        return star == true
    }
}
