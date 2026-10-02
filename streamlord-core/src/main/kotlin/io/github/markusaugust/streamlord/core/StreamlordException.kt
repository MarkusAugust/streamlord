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
 */
public class SignalsCodecException(
    message: String,
    cause: Throwable? = null,
) : StreamlordException(message, cause)

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
