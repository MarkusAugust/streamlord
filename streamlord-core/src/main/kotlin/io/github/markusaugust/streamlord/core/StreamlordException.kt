package io.github.markusaugust.streamlord.core

/**
 * The root of every curse Streamlord may lay upon you.
 *
 * All failures raised by the SDK descend from this exception, so a single `catch`
 * suffices for those who wish to contain the darkness in one place.
 */
public open class StreamlordException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * Raised when an event is malformed before it ever reaches the wire: a selector carrying a
 * line break, a remove without a target, a script without a body. Streamlord refuses to
 * forge a weapon it knows will shatter.
 */
public class DatastarEventValidationException(
    message: String,
) : StreamlordException(message)

/**
 * Raised when signals cannot be turned into JSON, or JSON cannot be turned back into signals.
 * The [cause], when present, comes from the codec adapter that failed.
 *
 * Thrown as itself, it is the server's fault: a value JSON cannot hold, a type no codec is set
 * up for. When the request's signals are what failed, it is a [SignalsDecodeException].
 */
public open class SignalsCodecException(
    message: String,
    cause: Throwable? = null,
) : StreamlordException(message, cause)

/**
 * Raised when the signals a request carried cannot be read into the type asked for: a field
 * missing or of the wrong kind, or JSON that is no object at all. The client sent them, so it is
 * a `400`, where a [SignalsCodecException] of any other kind is a `500`.
 */
public open class SignalsDecodeException(
    message: String,
    cause: Throwable? = null,
) : SignalsCodecException(message, cause)

/**
 * Raised when a request body carrying signals is not declared `application/json`, as every body
 * Datastar's client sends signals in is. A form on another site can post `text/plain` that reads
 * as JSON without the browser asking this server first; JSON it cannot send that way. The answer
 * is a `415`, and a handler that knows only [SignalsDecodeException] still answers a `400`.
 *
 * [contentType] is the header as the request had it, or `null` when it had none.
 */
public class SignalsMediaTypeException(
    public val contentType: String?,
) : SignalsDecodeException(
        "Signals in a request body must be sent as application/json; this one is " +
            (contentType?.let { "'$it'" } ?: "without a Content-Type"),
    )

/**
 * Raised when an incoming signal payload exceeds the limit configured on
 * [io.github.markusaugust.streamlord.core.application.Streamlord].
 * Unbounded input is how sieges begin; the wall holds.
 */
public class SignalsTooLargeException(
    public val actualSize: Long,
    public val maxSize: Int,
) : StreamlordException("Incoming signals are $actualSize in size; the limit is $maxSize")

/**
 * Raised by the built-in JSON parser when the text is not valid JSON. [position] is the
 * zero-based character offset at which the parser lost faith.
 */
public class JsonParseException(
    message: String,
    public val position: Int,
) : StreamlordException("$message (at position $position)")

/**
 * Raised inside a stream when a [io.github.markusaugust.streamlord.core.application.StreamAuthorisation]
 * answered no. It ends the response rather than letting the stream go quiet, so the client sees a
 * finished stream instead of an idle server.
 *
 * The adapters catch it where they opened the stream. You will only see it if you call
 * [io.github.markusaugust.streamlord.core.application.Streamlord.stream] yourself.
 */
public class StreamRefusedException : StreamlordException("The stream was refused by its authorisation check")
