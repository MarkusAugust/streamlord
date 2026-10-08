package io.github.markusaugust.streamlord.spring

import io.github.markusaugust.streamlord.core.JsonParseException
import io.github.markusaugust.streamlord.core.SignalsCodecException
import io.github.markusaugust.streamlord.core.SignalsTooLargeException
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * Answers signals that cannot be read with the status they deserve instead of a `500`:
 * [SignalsTooLargeException] with `413 Payload Too Large`, and [JsonParseException] and
 * [SignalsCodecException], a body that is not the signals you expected, with `400 Bad Request`.
 * The answer is the status and no body. A body would need a message converter for it, and a
 * missing one would turn the answer back into the `500`; nothing of the exception's message, which
 * may come from a codec and name your classes, reaches the client either way.
 *
 * Streamlord does not register it for you. Import it where you configure the application, in
 * WebMVC and WebFlux alike:
 *
 * ```kotlin
 * @SpringBootApplication
 * @Import(StreamlordExceptionHandler::class)
 * class App
 * ```
 *
 * It has the lowest precedence. Spring asks one advice after another and the first that handles
 * the exception answers, so an advice of your own that handles any of these, or
 * `StreamlordException`, decides when it carries an `@Order` of its own; without one, the two
 * tie and Spring's registration order picks.
 */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public class StreamlordExceptionHandler {
    @ExceptionHandler(SignalsTooLargeException::class)
    public fun signalsTooLarge(): ResponseEntity<Void> = ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).build()

    @ExceptionHandler(JsonParseException::class, SignalsCodecException::class)
    public fun signalsUnreadable(): ResponseEntity<Void> = ResponseEntity.badRequest().build()
}
