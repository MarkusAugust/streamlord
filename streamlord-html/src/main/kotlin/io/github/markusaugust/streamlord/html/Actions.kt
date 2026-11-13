package io.github.markusaugust.streamlord.html

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

    /**
     * Extra request headers whose values are raw JavaScript expressions, e.g. `"'Bearer ' + ${'$'}token"`,
     * written after [headers] in one object. A name is written as a literal, and one in both, in any case, is refused.
     */
    public var headerExprs: Map<String, String> = emptyMap()

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

    /**
     * A raw JavaScript expression for requestCancellation, e.g. `${'$'}controller`: the client takes an
     * `AbortController` there, and aborts the request when it is aborted. Set with [requestCancellation], it is refused.
     */
    public var requestCancellationExpr: String? = null

    private var payloadSet = false

    /**
     * Send this value instead of the signal store. Written as a JavaScript literal by [JsLiteral].
     * Set to `null`, it is written as `null`, and the client sends `null`; left unset, the signals go.
     */
    public var payload: Any? = null
        set(value) {
            field = value
            payloadSet = true
        }

    /** Send this raw JavaScript expression as the payload, e.g. `"{id: ${'$'}selected}"`, written in parentheses. Overrides [payload]. */
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
                if (headers.isNotEmpty() || headerExprs.isNotEmpty()) add("headers: ${headersJs()}")
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
                require(requestCancellationExpr == null || requestCancellation == null) {
                    "requestCancellation and requestCancellationExpr are one option; set one"
                }
                (requestCancellationExpr?.let(::expression) ?: requestCancellation?.let { js(it.wire) })?.let {
                    add("requestCancellation: $it")
                }
                (payloadExpr?.let(::expression) ?: if (payloadSet) JsLiteral.write(payload) else null)?.let { add("payload: $it") }
            }
        return if (entries.isEmpty()) null else entries.joinToString(", ", "{", "}")
    }

    private fun headersJs(): String {
        // HTTP names a header in any case, and the browser's Headers joins the values of one name.
        val names = (headers.keys.toList() + headerExprs.keys).groupBy { it.lowercase() }.values.firstOrNull { it.size > 1 }
        require(names == null) { "headers ${names?.joinToString(" and ")} are one header: HTTP names a header in any case" }
        val written = headers.map { (k, v) -> "${JsLiteral.key(k)}: ${js(v)}" }
        val computed = headerExprs.map { (k, v) -> "${JsLiteral.key(k)}: ${expression(v)}" }
        return (written + computed).joinToString(", ", "{", "}")
    }
}

/**
 * [text], a raw JavaScript expression, in parentheses: one value where it is written, which cannot
 * close the argument or the object around it and open one of its own. Refused when there is none.
 */
private fun expression(text: String): String {
    require(text.isNotBlank()) { "an expression must not be blank" }
    return "($text)"
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
    return if (opts == null) "@$action($uri)" else "@$action($uri, $opts)"
}

/** `@get('/uri')`: fetch with signals in the query string, response as SSE or a Datastar response. */
public fun get(
    uri: String,
    options: FetchOptions.() -> Unit = {},
): String = backend("get", js(uri), options)

/** `@get(expression)`: [get] with the address a raw JavaScript expression, e.g. `"'/items/' + ${'$'}id"`. */
public fun getExpr(
    expression: String,
    options: FetchOptions.() -> Unit = {},
): String = backend("get", expression(expression), options)

/** `@post('/uri')`: signals in the body. */
public fun post(
    uri: String,
    options: FetchOptions.() -> Unit = {},
): String = backend("post", js(uri), options)

/** `@post(expression)`: [post] with the address an expression, as in [getExpr]. */
public fun postExpr(
    expression: String,
    options: FetchOptions.() -> Unit = {},
): String = backend("post", expression(expression), options)

/** `@put('/uri')`. */
public fun put(
    uri: String,
    options: FetchOptions.() -> Unit = {},
): String = backend("put", js(uri), options)

/** `@put(expression)`: [put] with the address an expression, as in [getExpr]. */
public fun putExpr(
    expression: String,
    options: FetchOptions.() -> Unit = {},
): String = backend("put", expression(expression), options)

/** `@patch('/uri')`. */
public fun patch(
    uri: String,
    options: FetchOptions.() -> Unit = {},
): String = backend("patch", js(uri), options)

/** `@patch(expression)`: [patch] with the address an expression, as in [getExpr]. */
public fun patchExpr(
    expression: String,
    options: FetchOptions.() -> Unit = {},
): String = backend("patch", expression(expression), options)

/** `@delete('/uri')`: signals in the query string. */
public fun delete(
    uri: String,
    options: FetchOptions.() -> Unit = {},
): String = backend("delete", js(uri), options)

/** `@delete(expression)`: [delete] with the address an expression, as in [getExpr]. */
public fun deleteExpr(
    expression: String,
    options: FetchOptions.() -> Unit = {},
): String = backend("delete", expression(expression), options)

/** `@query('/uri')`: the HTTP QUERY method, signals in the body, read-only semantics. */
public fun query(
    uri: String,
    options: FetchOptions.() -> Unit = {},
): String = backend("query", js(uri), options)

/** `@query(expression)`: [query] with the address an expression, as in [getExpr]. */
public fun queryExpr(
    expression: String,
    options: FetchOptions.() -> Unit = {},
): String = backend("query", expression(expression), options)

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

/** `@setAll(expression, filter)`: [setAll] with the value a raw JavaScript expression, e.g. a signal. */
public fun setAllExpr(
    expression: String,
    filter: SignalFilter? = null,
): String {
    val value = expression(expression)
    return if (filter == null) "@setAll($value)" else "@setAll($value, ${filter.withInclude().toJs()})"
}

/** `@toggleAll(filter)`: flip every matching boolean signal. See [setAll] for the filter. */
public fun toggleAll(filter: SignalFilter? = null): String =
    if (filter == null) "@toggleAll()" else "@toggleAll(${filter.withInclude().toJs()})"

private fun SignalFilter.withInclude(): SignalFilter = if (include == null) copy(include = Regex(".*")) else this

/** `@peek(() => expression)`: read signals without subscribing. */
public fun peek(expression: String): String = "@peek(() => $expression)"
