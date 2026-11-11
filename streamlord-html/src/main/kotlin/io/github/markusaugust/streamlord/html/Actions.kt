package io.github.markusaugust.streamlord.html

import io.github.markusaugust.streamlord.core.domain.ElementNamespace
import io.github.markusaugust.streamlord.core.domain.ElementPatchMode
import kotlin.time.Duration

/**
 * Options for the backend actions `@get`, `@post`, `@put`, `@patch`, `@delete` and `@query`,
 * rendered as a JavaScript object literal. Only the options you set are written, so the common
 * case renders nothing at all; one set to the value Datastar takes by default is written all
 * the same, and means what leaving it out means.
 */
public class FetchOptions {
    /** `'json'` (default) or `'form'`. */
    public var contentType: ContentType? = null

    /**
     * Which signals to send. Datastar's default includes everything and excludes `/(^|\.)_/`:
     * every signal except those whose name, at any nesting level, starts with `_` (`_draft`, `user._token`).
     */
    public var filterSignals: SignalFilter? = null

    /** CSS selector of the form to read when [contentType] is FORM. */
    public var selector: String? = null

    /** Extra request headers. */
    public var headers: Map<String, String> = emptyMap()

    /** Keep the connection open while the tab is hidden. Default: false for GET, true otherwise. */
    public var openWhenHidden: Boolean? = null

    /**
     * Retry policy: `'auto'` (default), `'error'`, `'always'`, `'never'`. `'auto'` retries a
     * request that fails on the network, before or during the response, and no answer; `'never'`
     * retries nothing, and the action fails with the first error. A retry is made at most
     * [retryMaxCount] times over the whole action.
     */
    public var retry: Retry? = null

    /** Written in whole milliseconds, a fraction rounded down, as a modifier's duration is. */
    public var retryInterval: Duration? = null
    public var retryScaler: Double? = null

    /** Written in whole milliseconds, a fraction rounded down, as a modifier's duration is. */
    public var retryMaxWait: Duration? = null
    public var retryMaxCount: Int? = null

    /** `'auto'` (default), `'cleanup'` or `'disabled'`. */
    public var requestCancellation: RequestCancellation? = null

    /** Send this value instead of the signal store. Written as a JavaScript literal by [JsLiteral]. */
    public var payload: Any? = null

    /** Send this raw JavaScript expression as the payload, e.g. `"{id: ${'$'}selected}"`. Overrides [payload]. */
    public var payloadExpr: String? = null

    public enum class ContentType(
        public val wire: String,
    ) {
        JSON("json"),
        FORM("form"),
    }

    public enum class Retry(
        public val wire: String,
    ) {
        AUTO("auto"),
        ERROR("error"),
        ALWAYS("always"),
        NEVER("never"),
    }

    public enum class RequestCancellation(
        public val wire: String,
    ) {
        AUTO("auto"),
        CLEANUP("cleanup"),
        DISABLED("disabled"),
    }

    /** Render as a JavaScript object literal, or `null` when every option is at its default. */
    public fun toJs(): String? {
        val entries =
            buildList {
                contentType?.let { add("contentType: ${js(it.wire)}") }
                filterSignals?.let { add("filterSignals: ${it.toJs()}") }
                selector?.let { add("selector: ${js(it)}") }
                if (headers.isNotEmpty()) add("headers: ${JsLiteral.write(headers)}")
                openWhenHidden?.let { add("openWhenHidden: $it") }
                retry?.let { add("retry: ${js(it.wire)}") }
                retryInterval?.let { add("retryInterval: ${it.wholeMilliseconds()}") }
                retryScaler?.let {
                    // Below zero the backoff turns into no wait at all.
                    require(it.isFinite() && it >= 0) { "retryScaler must be a finite number of 0 or more, not $it" }
                    add("retryScaler: $it")
                }
                retryMaxWait?.let { add("retryMaxWait: ${it.wholeMilliseconds()}") }
                retryMaxCount?.let {
                    require(it >= 0) { "retryMaxCount must be 0 or more, not $it" }
                    add("retryMaxCount: $it")
                }
                requestCancellation?.let { add("requestCancellation: ${js(it.wire)}") }
                (payloadExpr ?: payload?.let { JsLiteral.write(it) })?.let { add("payload: $it") }
            }
        return if (entries.isEmpty()) null else entries.joinToString(", ", "{", "}")
    }
}

/**
 * A JavaScript string literal in single quotes, safely escaped: `js("/x")` is `'/x'`. Single
 * quotes, because the result ends up inside a double-quoted HTML attribute; kotlinx.html would
 * escape double quotes, but a string or a template would not. See [JsLiteral].
 */
public fun js(value: String): String = JsLiteral.string(value)

private fun backend(
    action: String,
    uri: String,
    options: FetchOptions.() -> Unit,
): String {
    val opts = FetchOptions().apply(options).toJs()
    return if (opts == null) "@$action(${js(uri)})" else "@$action(${js(uri)}, $opts)"
}

/** `@get('/uri')`: fetch with signals in the query string, response as SSE or a Datastar response. */
public fun get(
    uri: String,
    options: FetchOptions.() -> Unit = {},
): String = backend("get", uri, options)

/** `@post('/uri')`: signals in the body. */
public fun post(
    uri: String,
    options: FetchOptions.() -> Unit = {},
): String = backend("post", uri, options)

/** `@put('/uri')`. */
public fun put(
    uri: String,
    options: FetchOptions.() -> Unit = {},
): String = backend("put", uri, options)

/** `@patch('/uri')`. */
public fun patch(
    uri: String,
    options: FetchOptions.() -> Unit = {},
): String = backend("patch", uri, options)

/** `@delete('/uri')`: signals in the query string. */
public fun delete(
    uri: String,
    options: FetchOptions.() -> Unit = {},
): String = backend("delete", uri, options)

/** `@query('/uri')`: the HTTP QUERY method, signals in the body, read-only semantics. */
public fun query(
    uri: String,
    options: FetchOptions.() -> Unit = {},
): String = backend("query", uri, options)

/**
 * `@setAll(value, filter)`: set every matching signal. The filter of these two actions is
 * documented with `include` required, so a filter that only excludes gets an include of
 * everything written in front of it.
 */
public fun setAll(
    value: Any?,
    filter: SignalFilter? = null,
): String =
    if (filter == null) "@setAll(${JsLiteral.write(value)})" else "@setAll(${JsLiteral.write(value)}, ${filter.withInclude().toJs()})"

/** `@toggleAll(filter)`: flip every matching boolean signal. See [setAll] for the filter. */
public fun toggleAll(filter: SignalFilter? = null): String =
    if (filter == null) "@toggleAll()" else "@toggleAll(${filter.withInclude().toJs()})"

private fun SignalFilter.withInclude(): SignalFilter = if (include == null) copy(include = Regex(".*")) else this

/** `@peek(() => expression)`: read signals without subscribing. */
public fun peek(expression: String): String = "@peek(() => $expression)"
