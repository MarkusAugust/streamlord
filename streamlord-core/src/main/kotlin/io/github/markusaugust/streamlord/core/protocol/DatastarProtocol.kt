package io.github.markusaugust.streamlord.core.protocol

import io.github.markusaugust.streamlord.core.catalog.CatalogNames
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * The runes of the Datastar 1.0.4 protocol, transcribed from `datastar-sdk-config-v1.json`
 * and the client source. Nothing here is invented; every constant is a word the browser knows.
 */
public object DatastarProtocol {
    /** The Datastar version this SDK speaks, the version of its catalog. */
    public const val VERSION: String = CatalogNames.VERSION

    /** The `Accept` the client sends with every request (fetch.ts:82). */
    public const val ACCEPT: String = "text/event-stream, text/html, application/json"

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
    public val SSE_RESPONSE_HEADERS: Map<String, String> =
        mapOf(
            "Cache-Control" to "no-cache",
            "X-Accel-Buffering" to "no",
        )

    /**
     * Whether an `Accept-Encoding` header takes gzip: `gzip` or `x-gzip` listed with a weight
     * above zero, or `*` above zero with gzip not refused by name. Names are matched without
     * regard to case, and a weight that does not parse counts as a refusal. The adapters ask it
     * when [compress][io.github.markusaugust.streamlord.core.application.Streamlord.compress] is on.
     */
    public fun acceptsGzip(acceptEncoding: String?): Boolean {
        if (acceptEncoding.isNullOrBlank()) return false
        var named: Boolean? = null
        var star = false
        for (entry in acceptEncoding.split(',')) {
            val parts = entry.split(';')
            val name = parts.first().trim().lowercase()
            val weight =
                parts
                    .drop(1)
                    .map { it.split('=', limit = 2).map(String::trim) }
                    .firstOrNull { it.size == 2 && it[0].equals("q", ignoreCase = true) }
                    ?.let { if (QVALUE.matches(it[1])) it[1].toDouble() else 0.0 } ?: 1.0
            when (name) {
                "gzip", "x-gzip" -> named = (named == true) || weight > 0.0
                "*" -> star = weight > 0.0
            }
        }
        return named ?: star
    }

    // RFC 9110's weight: 0 to 1, with at most three decimals. Anything else, Infinity included, is no.
    private val QVALUE = Regex("""0(\.\d{0,3})?|1(\.0{0,3})?""")
}
