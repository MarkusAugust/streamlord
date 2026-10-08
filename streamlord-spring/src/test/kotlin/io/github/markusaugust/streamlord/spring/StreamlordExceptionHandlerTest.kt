package io.github.markusaugust.streamlord.spring

import io.github.markusaugust.streamlord.core.SignalsCodecException
import io.github.markusaugust.streamlord.core.StreamlordException
import io.github.markusaugust.streamlord.core.application.Streamlord
import jakarta.servlet.http.HttpServletRequest
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.test.web.servlet.MockMvc
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
        fun codec(): String = throw SignalsCodecException("Cannot construct instance of `com.example.internal.Secret`")
    }

    @RestControllerAdvice
    @Order(0)
    class Own {
        @ExceptionHandler(StreamlordException::class)
        fun own(): ResponseEntity<String> = ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body("own")
    }

    private fun mvc(vararg advice: Any): MockMvc = MockMvcBuilders.standaloneSetup(Signals()).setControllerAdvice(*advice).build()

    // A RequestBuilder of our own: MockMvcRequestBuilders is not binary compatible between Spring 6.2 and 7.0.
    private fun MockMvc.post(
        path: String,
        body: String,
    ) = perform { context ->
        MockHttpServletRequest(context, "POST", path).apply {
            contentType = "application/json"
            setContent(body.toByteArray())
        }
    }.andReturn().response

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
}
