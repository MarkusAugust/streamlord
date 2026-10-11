package io.github.markusaugust.streamlord.spring

import io.github.markusaugust.streamlord.core.SignalsCodecException
import io.github.markusaugust.streamlord.core.SignalsDecodeException
import io.github.markusaugust.streamlord.core.SignalsTooLargeException
import io.github.markusaugust.streamlord.core.StreamlordException
import io.github.markusaugust.streamlord.core.application.Streamlord
import jakarta.servlet.http.HttpServletRequest
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StreamlordExceptionHandlerTest {
    @RestController
    class Signals {
        private val streamlord = Streamlord(maxSignalsSize = 16)

        @PostMapping("/read")
        fun read(request: HttpServletRequest): String = request.readSignals(streamlord).toString()

        @PostMapping("/codec")
        fun codec(): String = throw SignalsDecodeException("Cannot construct instance of `com.example.internal.Secret`")

        @PostMapping("/encode")
        fun encode(): String = throw SignalsCodecException("JSON cannot represent NaN")
    }

    @RestControllerAdvice
    @Order(0)
    class Own {
        @ExceptionHandler(StreamlordException::class)
        fun own(): ResponseEntity<String> = ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT).body("own")
    }

    private fun mvc(vararg advice: Any): MockMvc = MockMvcBuilders.standaloneSetup(Signals()).setControllerAdvice(*advice).build()

    private fun MockMvc.post(
        path: String,
        body: String,
    ) = perform(MockMvcRequestBuilders.post(path).contentType("application/json").content(body)).andReturn().response

    @Test
    fun `signals over the limit are 413`() {
        val response = mvc(StreamlordExceptionHandler()).post("/read", """{"query":"far too long for the limit"}""")

        assertEquals(413, response.status)
        assertEquals("", response.contentAsString)
    }

    @Test
    fun `a body that is not JSON is 400`() {
        assertEquals(400, mvc(StreamlordExceptionHandler()).post("/read", "query=gate").status)
    }

    @Test
    fun `a codec failure is 400 and does not repeat the codec's message`() {
        val response = mvc(StreamlordExceptionHandler()).post("/codec", "{}")

        assertEquals(400, response.status)
        assertFalse(response.contentAsString.contains("Secret"), response.contentAsString)
    }

    // A value the server could not encode is its own bug, not a bad request.
    @Test
    fun `a failure to encode is left alone, to be the 500 it is`() {
        val thrown = runCatching { mvc(StreamlordExceptionHandler()).post("/encode", "{}") }.exceptionOrNull()

        assertTrue(generateSequence(thrown) { it.cause }.any { it is SignalsCodecException }, "$thrown")
    }

    @Test
    fun `without it the exception is not handled`() {
        val thrown = runCatching { mvc().post("/read", "query=gate") }.exceptionOrNull()

        assertTrue(generateSequence(thrown) { it.cause }.any { it is StreamlordException }, "$thrown")
    }

    @Test
    fun `an ordered advice of your own decides`() {
        val response = mvc(StreamlordExceptionHandler(), Own()).post("/read", "query=gate")

        assertEquals(422, response.status)
        assertEquals("own", response.contentAsString)
    }

    @RestController
    class Reactive {
        @PostMapping("/large")
        fun large(): String = throw SignalsTooLargeException(actualSize = 32, maxSize = 16)

        @PostMapping("/codec")
        fun codec(): String = throw SignalsDecodeException("Cannot construct instance of `com.example.internal.Secret`")
    }

    private fun flux(): WebTestClient =
        WebTestClient.bindToController(Reactive()).controllerAdvice(StreamlordExceptionHandler()).build()

    @Test
    fun `on WebFlux, signals over the limit are 413 and a codec failure is 400`() {
        flux().post().uri("/large").exchange().expectStatus().isEqualTo(413).expectBody().isEmpty()
        flux().post().uri("/codec").exchange().expectStatus().isBadRequest().expectBody().isEmpty()
    }
}
