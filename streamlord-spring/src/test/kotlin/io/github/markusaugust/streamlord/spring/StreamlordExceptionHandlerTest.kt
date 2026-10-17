package io.github.markusaugust.streamlord.spring

import io.github.markusaugust.streamlord.core.SignalsCodecException
import io.github.markusaugust.streamlord.core.SignalsDecodeException
import io.github.markusaugust.streamlord.core.SignalsMediaTypeException
import io.github.markusaugust.streamlord.core.SignalsTooLargeException
import io.github.markusaugust.streamlord.core.StreamlordException
import io.github.markusaugust.streamlord.core.application.Streamlord
import jakarta.servlet.http.HttpServletRequest
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
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
        contentType: String = "application/json",
    ) = perform(MockMvcRequestBuilders.post(path).contentType(contentType).content(body)).andReturn().response

    // What a <form enctype="text/plain"> on another site sends, which needs no preflight.
    @Test
    fun `a body not declared application json is 415`() {
        val response = mvc(StreamlordExceptionHandler()).post("/read", """{"a":1,"p":"="}""", "text/plain")

        assertEquals(415, response.status)
        assertEquals("", response.contentAsString)
        assertEquals(200, mvc(StreamlordExceptionHandler()).post("/read", """{"a":1}""").status)
    }

    // An answer on a started stream would end it as if it had finished; thrown on, the container cuts it.
    @Test
    fun `a response that has started gets no answer`() {
        val handler = StreamlordExceptionHandler()
        val answers: List<Pair<Exception, (Exception) -> ResponseEntity<Void>>> =
            listOf(
                SignalsMediaTypeException("text/plain") to { e -> handler.signalsNotJson(e as SignalsMediaTypeException) },
                SignalsTooLargeException(actualSize = 32, maxSize = 16) to { e -> handler.signalsTooLarge(e as SignalsTooLargeException) },
                SignalsDecodeException("no") to { e -> handler.signalsUnreadable(e) },
            )
        for (committed in listOf(false, true)) {
            val response = MockHttpServletResponse().apply { setCommitted(committed) }
            RequestContextHolder.setRequestAttributes(ServletRequestAttributes(MockHttpServletRequest(), response))
            try {
                for ((e, answer) in answers) {
                    val thrown = runCatching { answer(e) }
                    if (committed) assertSame(e, thrown.exceptionOrNull(), "$e") else assertTrue(thrown.isSuccess, "$e")
                }
            } finally {
                RequestContextHolder.resetRequestAttributes()
            }
        }
    }

    // A handler that knows only the decode failure answers it as one.
    @RestControllerAdvice
    class DecodeOnly {
        @ExceptionHandler(SignalsDecodeException::class)
        fun decode(): ResponseEntity<String> = ResponseEntity.badRequest().body("decode")
    }

    @Test
    fun `an advice that maps only the decode failure answers 400`() {
        assertEquals(400, mvc(DecodeOnly()).post("/read", """{"a":1}""", "text/plain").status)
    }

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
