package io.github.markusaugust.streamlord.html

import io.github.markusaugust.streamlord.core.domain.ElementNamespace
import io.github.markusaugust.streamlord.core.domain.ElementPatchMode
import io.github.markusaugust.streamlord.core.json.JsonWriter
import kotlin.time.Duration

/**
 * Options for the backend actions `@get`, `@post`, `@put`, `@patch`, `@delete` and `@query`,
 * rendered as a JavaScript object literal. Only values that differ from Datastar's own defaults
 * are written, so the common case renders nothing at all.
 */
public class FetchOptions {
    /** `'json'` (default) or `'form'`. */
    public var contentType: ContentType? = null

    /** Which signals to send. Datastar's default sends everything not prefixed with `_`. */
    public var filterSignals: SignalFilter? = null

    /** CSS selector of the form to read when [contentType] is FORM. */
    public var selector: String? = null

    /** Extra request headers. */
    public var headers: Map<String, String> = emptyMap()

    /** Keep the connection open while the tab is hidden. Default: false for GET, true otherwise. */
    public var openWhenHidden: Boolean? = null

    /** Retry policy: `'auto'` (default), `'error'`, `'always'`, `'never'`. */
    public var retry: Retry? = null
    public var retryInterval: Duration? = null
    public var retryScaler: Double? = null
    public var retryMaxWait: Duration? = null
    public var retryMaxCount: Int? = null

    /** `'auto'` (default), `'cleanup'` or `'disabled'`. */
    public var requestCancellation: RequestCancellation? = null

    /** Send this value instead of the signal store. Serialised as JSON. */
    public var payload: Any? = null

    /** Send this raw JavaScript expression as the payload, e.g. `"{id: ${'$'}selected}"`. Overrides [payload]. */
    public var payloadExpr: String? = null

    /** Options the client applies to a non-SSE response regardless of its `datastar-*` headers. */
    public var responseOverrides: ResponseOverrides? = null

    /** Configure [responseOverrides] inline. */
    public fun responseOverrides(block: ResponseOverrides.() -> Unit) {
        responseOverrides = ResponseOverrides().apply(block)
    }

    public enum class ContentType(public val wire: String) { JSON("json"), FORM("form") }
    public enum class Retry(public val wire: String) { AUTO("auto"), ERROR("error"), ALWAYS("always"), NEVER("never") }
    public enum class RequestCancellation(public val wire: String) { AUTO("auto"), CLEANUP("cleanup"), DISABLED("disabled") }

    /**
     * Overrides for non-SSE responses: the element options for a `text/html` body, or
     * `onlyIfMissing` for an `application/json` body.
     */
    public class ResponseOverrides {
        public var selector: String? = null
        public var mode: ElementPatchMode? = null
        public var namespace: ElementNamespace? = null
        public var useViewTransition: Boolean? = null
        public var onlyIfMissing: Boolean? = null

        internal fun toJs(): String? {
            val entries = buildList {
                selector?.let { add("selector: ${js(it)}") }
                mode?.let { add("mode: ${js(it.wire)}") }
                namespace?.let { add("namespace: ${js(it.wire)}") }
                useViewTransition?.let { add("useViewTransition: $it") }
                onlyIfMissing?.let { add("onlyIfMissing: $it") }
            }
            return if (entries.isEmpty()) null else entries.joinToString(", ", "{", "}")
        }
    }

    /** Render as a JavaScript object literal, or `null` when every option is at its default. */
    public fun toJs(): String? {
        val entries = buildList {
            contentType?.let { add("contentType: ${js(it.wire)}") }
            filterSignals?.let { add("filterSignals: ${it.toJs()}") }
            selector?.let { add("selector: ${js(it)}") }
            if (headers.isNotEmpty()) add("headers: ${JsonWriter.write(headers)}")
            openWhenHidden?.let { add("openWhenHidden: $it") }
            retry?.let { add("retry: ${js(it.wire)}") }
            retryInterval?.let { add("retryInterval: ${it.inWholeMilliseconds}") }
            retryScaler?.let { add("retryScaler: $it") }
            retryMaxWait?.let { add("retryMaxWait: ${it.inWholeMilliseconds}") }
            retryMaxCount?.let { add("retryMaxCount: $it") }
            requestCancellation?.let { add("requestCancellation: ${js(it.wire)}") }
            (payloadExpr ?: payload?.let { JsonWriter.write(it) })?.let { add("payload: $it") }
            responseOverrides?.toJs()?.let { add("responseOverrides: $it") }
        }
        return if (entries.isEmpty()) null else entries.joinToString(", ", "{", "}")
    }
}

/** A JavaScript string literal, safely quoted. */
public fun js(value: String): String = JsonWriter.writeString(value)

private fun backend(action: String, uri: String, options: FetchOptions.() -> Unit): String {
    val opts = FetchOptions().apply(options).toJs()
    return if (opts == null) "@$action(${js(uri)})" else "@$action(${js(uri)}, $opts)"
}

/** `@get('/uri')`: fetch with signals in the query string, response as SSE or a Datastar response. */
public fun get(uri: String, options: FetchOptions.() -> Unit = {}): String = backend("get", uri, options)

/** `@post('/uri')`: signals in the body. */
public fun post(uri: String, options: FetchOptions.() -> Unit = {}): String = backend("post", uri, options)

/** `@put('/uri')`. */
public fun put(uri: String, options: FetchOptions.() -> Unit = {}): String = backend("put", uri, options)

/** `@patch('/uri')`. */
public fun patch(uri: String, options: FetchOptions.() -> Unit = {}): String = backend("patch", uri, options)

/** `@delete('/uri')`: signals in the query string. */
public fun delete(uri: String, options: FetchOptions.() -> Unit = {}): String = backend("delete", uri, options)

/** `@query('/uri')`: the HTTP QUERY method, signals in the body, read-only semantics. */
public fun query(uri: String, options: FetchOptions.() -> Unit = {}): String = backend("query", uri, options)

/** `@setAll(value, filter)`: set every matching signal. */
public fun setAll(value: Any?, filter: SignalFilter? = null): String =
    if (filter == null) "@setAll(${JsonWriter.write(value)})" else "@setAll(${JsonWriter.write(value)}, ${filter.toJs()})"

/** `@toggleAll(filter)`: flip every matching boolean signal. */
public fun toggleAll(filter: SignalFilter? = null): String =
    if (filter == null) "@toggleAll()" else "@toggleAll(${filter.toJs()})"

/** `@peek(() => expression)`: read signals without subscribing. */
public fun peek(expression: String): String = "@peek(() => $expression)"
