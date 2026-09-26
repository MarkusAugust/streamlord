package io.github.markusaugust.streamlord.core.port.driven

import io.github.markusaugust.streamlord.core.SignalsTooLargeException
import io.github.markusaugust.streamlord.core.protocol.DatastarProtocol

/**
 * Driven port: the little the core needs to know about an HTTP request in order to find the
 * signals the browser sent.
 *
 * Adapters wrap `ApplicationCall`, `HttpServletRequest` or whatever else; the core applies the
 * protocol rule (query parameter for GET and DELETE, body for everything else) without ever
 * importing a framework.
 */
public interface IncomingRequest {
    /** HTTP method, any case. */
    public val method: String

    /** First value of a query parameter, URL-decoded, or `null`. */
    public fun queryParameter(name: String): String?

    /** First value of a header, case-insensitive, or `null`. */
    public fun header(name: String): String?

    /**
     * The request body as text, read with a hard ceiling.
     *
     * Implementations **must not** read more than [maxBytes] bytes into memory. They should
     * reject a declared `Content-Length` above the limit before reading anything, stop reading
     * once the limit is passed, and throw [SignalsTooLargeException]. The wall is only as strong
     * as this method. Called at most once, and only for methods that carry a body.
     */
    public suspend fun bodyText(maxBytes: Int): String
}

/** `true` when the client announced itself with the `Datastar-Request: true` header. */
public val IncomingRequest.isDatastarRequest: Boolean
    get() = header(DatastarProtocol.Headers.REQUEST)?.equals("true", ignoreCase = true) == true

/** `true` when this request's signals travel in the query string. */
public val IncomingRequest.carriesSignalsInQuery: Boolean
    get() = method.uppercase() in DatastarProtocol.BODYLESS_METHODS

/**
 * Helper for adapters: reject a declared content length above [maxBytes] before a single byte
 * is read. A missing or negative length (chunked transfer) passes; the bounded read catches it.
 */
public fun rejectDeclaredLengthAbove(maxBytes: Int, declaredLength: Long?) {
    if (declaredLength != null && declaredLength > maxBytes) {
        throw SignalsTooLargeException(declaredLength, maxBytes)
    }
}
