package io.github.markusaugust.streamlord.spring

import io.github.markusaugust.streamlord.core.application.Streamlord
import io.github.markusaugust.streamlord.core.port.driving.patchSignals
import io.github.markusaugust.streamlord.spring.test.datastarRequestFactory
import io.github.markusaugust.streamlord.test.datastarEvents
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import kotlinx.coroutines.delay
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.client.RestTestClient
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.annotation.CookieValue
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody
import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/** RestTestClient over MockMvc, with every async cycle of a stream run before the answer. */
class DatastarRequestFactoryTest {
    companion object {
        val streamlord = Streamlord(maxSignalsSize = 32, heartbeat = null)
        val release = CompletableFuture<Unit>()
    }

    @RestController
    class Feed {
        @GetMapping("/feed")
        fun feed(
            request: HttpServletRequest,
            response: HttpServletResponse,
        ): StreamingResponseBody {
            val heads = request.readSignals(streamlord).int("heads") ?: 0
            return response.datastarStream(streamlord, request) { patchSignals("heads" to heads + 1) }
        }

        @PostMapping("/feed")
        fun post(
            request: HttpServletRequest,
            response: HttpServletResponse,
        ): StreamingResponseBody {
            val heads = request.readSignals(streamlord).int("heads") ?: 0
            return response.datastarStream(streamlord, request) { patchSignals("heads" to heads + 1) }
        }

        @GetMapping("/suspended")
        suspend fun suspended(
            request: HttpServletRequest,
            response: HttpServletResponse,
        ): StreamingResponseBody {
            delay(1)
            return response.datastarStream(streamlord, request) { patchSignals("heads" to 1) }
        }

        @GetMapping("/who")
        fun who(
            @CookieValue("who") who: String,
            request: HttpServletRequest,
            response: HttpServletResponse,
        ): StreamingResponseBody = response.datastarStream(streamlord, request) { patchSignals("who" to who) }

        @GetMapping("/forever")
        fun forever(
            request: HttpServletRequest,
            response: HttpServletResponse,
        ): StreamingResponseBody = response.datastarStream(streamlord, request) { release.get() }
    }

    private val mvc = MockMvcBuilders.standaloneSetup(Feed()).setControllerAdvice(StreamlordExceptionHandler()).build()
    private val client = RestTestClient.bindToServer(mvc.datastarRequestFactory()).build()

    @Test
    fun `a stream is read to its end`() {
        val result =
            client
                .get()
                .uri("/feed?datastar={heads}", """{"heads":13}""")
                .header("Datastar-Request", "true")
                .exchange()
                .expectStatus()
                .isOk
                .expectHeader()
                .contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
                .returnResult(String::class.java)

        datastarEvents(result.responseBody.orEmpty()).assertSignal("heads", 14)
    }

    @Test
    fun `a suspend controller's stream is read too`() {
        val body =
            client
                .get()
                .uri("/suspended")
                .exchange()
                .returnResult(String::class.java)
                .responseBody

        datastarEvents(body.orEmpty()).assertSignal("heads", 1)
    }

    @Test
    fun `signals in the body reach the controller`() {
        val body =
            client
                .post()
                .uri("/feed")
                .contentType(MediaType.APPLICATION_JSON)
                .body("""{"heads":2}""")
                .exchange()
                .returnResult(String::class.java)
                .responseBody

        datastarEvents(body.orEmpty()).assertSignal("heads", 3)
    }

    @Test
    fun `a cookie reaches the controller`() {
        val body =
            client
                .get()
                .uri("/who")
                .cookie("who", "Gorvek")
                .exchange()
                .returnResult(String::class.java)
                .responseBody

        datastarEvents(body.orEmpty()).assertSignal("who", "Gorvek")
    }

    @Test
    fun `a failure before the stream keeps its status`() {
        val status =
            client
                .get()
                .uri("/feed?datastar={s}", """{"heads":13,"far":"too long for the limit"}""")
                .exchange()
                .returnResult(String::class.java)
                .status

        assertEquals(413, status.value())
    }

    @Test
    fun `a stream that does not end fails the test instead of hanging`() {
        val quick = RestTestClient.bindToServer(mvc.datastarRequestFactory(asyncTimeout = 200.milliseconds)).build()

        val failure =
            try {
                assertFailsWith<IllegalStateException> { quick.get().uri("/forever").exchange() }
            } finally {
                release.complete(Unit)
            }

        assertTrue("200ms" in failure.message.orEmpty(), failure.message)
    }
}
