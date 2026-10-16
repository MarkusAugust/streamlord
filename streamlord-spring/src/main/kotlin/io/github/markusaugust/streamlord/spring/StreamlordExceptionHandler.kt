package io.github.markusaugust.streamlord.spring

import io.github.markusaugust.streamlord.core.JsonParseException
import io.github.markusaugust.streamlord.core.SignalsDecodeException
import io.github.markusaugust.streamlord.core.SignalsTooLargeException
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes

/**
 * Answers signals that cannot be read with the status they deserve instead of a `500`:
 * [SignalsTooLargeException] with `413 Content Too Large`, and [JsonParseException] and
 * [SignalsDecodeException], a body that is not the signals you expected, with `400 Bad Request`.
 * Any other [io.github.markusaugust.streamlord.core.SignalsCodecException], such as a value
 * JSON cannot hold on the way out, is the server's fault and stays a `500`.
 * The answer is the status and no body. A body would need a message converter for it, and a
 * missing one would turn the answer back into the `500`; nothing of the exception's message, which
 * may come from a codec and name your classes, reaches the client either way.
 *
 * Streamlord does not register it for you. Import it where you configure the application, for
 * WebMVC and WebFlux annotated controllers alike:
 *
 * ```kotlin
 * @SpringBootApplication
 * @Import(StreamlordExceptionHandler::class)
 * class App
 * ```
 *
 * It has the lowest precedence. Spring asks one advice after another and the first that handles
 * the exception answers, so an advice of your own that handles any of these, `StreamlordException`
 * or `Exception`, decides when it carries an `@Order` of its own; without one, the two tie and
 * Spring's registration order picks.
 *
 * A status needs a response that has not started: read the signals before the stream opens. Once
 * a stream has sent its first event, or its first heartbeat comment, at most five seconds after
 * it opened while a heartbeat is on, the handler throws the exception on rather than answer, and
 * the container cuts the connection, which Datastar's client retries. An answer then would end
 * the stream as if it had finished, and the client would not come back. An advice of your own
 * that handles exceptions from inside a stream should do the same.
 *
 * Over HTTP/2 straight to Tomcat under Spring Boot, the cut does not hold: Boot's error page runs
 * into the started response, and Tomcat then ends the stream as if it had finished. Over HTTP/1.1,
 * as a proxy in front usually speaks to the application, Tomcat and Jetty cut it.
 */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public class StreamlordExceptionHandler {
    @ExceptionHandler(SignalsTooLargeException::class)
    public fun signalsTooLarge(e: SignalsTooLargeException): ResponseEntity<Void> =
        notStarted(e).let { ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE).build() }

    @ExceptionHandler(JsonParseException::class, SignalsDecodeException::class)
    public fun signalsUnreadable(e: Exception): ResponseEntity<Void> = notStarted(e).let { ResponseEntity.badRequest().build() }

    /** [signalsTooLarge] as 0.12 had it, for code compiled against it; not a handler. */
    @Deprecated("Kept for binary compatibility.", level = DeprecationLevel.HIDDEN)
    public fun signalsTooLarge(): ResponseEntity<Void> = ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE).build()

    /** [signalsUnreadable] as 0.12 had it, for code compiled against it; not a handler. */
    @Deprecated("Kept for binary compatibility.", level = DeprecationLevel.HIDDEN)
    public fun signalsUnreadable(): ResponseEntity<Void> = ResponseEntity.badRequest().build()

    /*
     * Throws [e] on when the response has started. Spring MVC runs the advice again on the dispatch
     * after a stream fails, with the request it answers in RequestContextHolder; WebFlux has none
     * there, and answers before its body starts.
     */
    private fun notStarted(e: Exception) {
        val response = (RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes)?.response
        if (response?.isCommitted == true) throw e
    }
}
