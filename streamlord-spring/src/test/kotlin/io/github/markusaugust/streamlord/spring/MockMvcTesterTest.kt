package io.github.markusaugust.streamlord.spring

import io.github.markusaugust.streamlord.core.SignalsTooLargeException
import io.github.markusaugust.streamlord.core.application.Streamlord
import io.github.markusaugust.streamlord.core.port.driving.patchSignals
import io.github.markusaugust.streamlord.core.protocol.DatastarProtocol
import io.github.markusaugust.streamlord.test.datastarEvents
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import kotlinx.coroutines.delay
import org.assertj.core.api.Assertions.assertThat
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.assertj.MockMvcTester
import org.springframework.test.web.servlet.assertj.MvcTestResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The recipe the testing page shows, held to what it says. */
class MockMvcTesterTest {
    companion object {
        val streamlord = Streamlord(maxSignalsSize = 32, heartbeat = null)
        var gate = CompletableFuture<Unit>()
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
            val heads = request.readSignals(streamlord).int("heads") ?: 0
            return response.datastarStream(streamlord, request) {
                gate.get(10, TimeUnit.SECONDS)
                patchSignals("heads" to heads + 1)
            }
        }

        @GetMapping("/inside")
        fun inside(
            request: HttpServletRequest,
            response: HttpServletResponse,
        ): StreamingResponseBody =
            response.datastarStream(streamlord, request) {
                patchSignals("heads" to (request.readSignals(streamlord).int("heads") ?: 0))
            }

        @GetMapping("/after")
        fun after(response: HttpServletResponse): StreamingResponseBody =
            response.datastarStream(streamlord) {
                patchSignals("heads" to 1)
                throw SignalsTooLargeException(2, 1)
            }

        @GetMapping("/gated")
        fun gated(
            request: HttpServletRequest,
            response: HttpServletResponse,
        ): StreamingResponseBody =
            response.datastarStream(streamlord, request) {
                gate.get(10, TimeUnit.SECONDS)
                patchSignals("heads" to 1)
            }
    }

    private val mvc = MockMvcTester.of(listOf(Feed())) { it.setControllerAdvice(StreamlordExceptionHandler()).build() }

    @BeforeTest
    fun reset() {
        gate = CompletableFuture()
    }

    private fun MockMvcTester.datastar(
        method: HttpMethod,
        uri: String,
        signals: String? = null,
    ): MvcTestResult {
        var result =
            method(method)
                .uri(uri)
                .header("Datastar-Request", "true")
                .header("Accept", "text/event-stream, text/html, application/json")
                .apply {
                    when {
                        signals == null -> Unit
                        method == HttpMethod.GET || method == HttpMethod.DELETE -> param("datastar", signals)
                        else -> contentType(MediaType.APPLICATION_JSON).content(signals)
                    }
                }.asyncExchange()
        while (result.unresolvedException == null && result.request.isAsyncStarted) {
            val started = result.mvcResult
            started.getAsyncResult(10_000)
            result = perform(MockMvcRequestBuilders.asyncDispatch(started))
        }
        return result
    }

    // A suspend controller runs in one async cycle and its stream in a second; exchange() waits
    // for the first alone, and returns while the stream may still be writing. The gate holds the
    // stream until exchange() has returned.
    @Test
    fun `a suspend controller's stream is dispatched too`() {
        assertEquals(
            "",
            mvc
                .get()
                .uri("/suspended")
                .exchange()
                .response.contentAsString,
        )
        gate.complete(Unit)

        val result = mvc.datastar(HttpMethod.GET, "/suspended", """{"heads":13}""")

        assertThat(result).hasStatusOk().hasHeader("Connection", "keep-alive")
        datastarEvents(result.response.contentAsString).assertSignal("heads", 14)
    }

    @Test
    fun `the helper sends what Datastar's client sends`() {
        assertEquals(DatastarProtocol.ACCEPT, "text/event-stream, text/html, application/json")
        assertEquals(DatastarProtocol.BODYLESS_METHODS, setOf("GET", "DELETE"))
        assertEquals(DatastarProtocol.SIGNALS_PARAMETER, "datastar")
    }

    @Test
    fun `a stream answers with its status, headers and events`() {
        for (method in listOf(HttpMethod.GET, HttpMethod.POST)) {
            val result = mvc.datastar(method, "/feed", """{"heads":13}""")

            assertThat(result).hasStatusOk().hasHeader("X-Accel-Buffering", "no").hasHeader("Connection", "keep-alive")
            datastarEvents(result.response.contentAsString).assertSignal("heads", 14)
        }
    }

    @Test
    fun `signals read before the stream fail on the first request, with no async`() {
        val result = mvc.datastar(HttpMethod.GET, "/feed", """{"heads":13,"far":"too long for the limit"}""")

        assertEquals(413, result.response.status)
        assertTrue(!result.request.isAsyncStarted)
    }

    @Test
    fun `signals read inside the stream fail on the async dispatch`() {
        val result = mvc.datastar(HttpMethod.GET, "/inside", """{"heads":13,"far":"too long for the limit"}""")

        assertEquals(413, result.response.status)
        assertEquals("", result.response.contentAsString)
    }

    @Test
    fun `a failure after the first event is the result's unresolved exception`() {
        val result = mvc.datastar(HttpMethod.GET, "/after")

        assertThat(result).hasFailed()
        // Wrapped by the DispatcherServlet in a ServletException; the response is out of reach.
        assertIs<SignalsTooLargeException>(result.unresolvedException?.cause)
        assertFailsWith<IllegalStateException> { result.response }
    }

    // The test thread reads the first result while the stream's thread runs, as Spring Boot's
    // printer does on every perform; a header added from that thread was a race.
    @Test
    fun `the headers are all there before the stream has run`() {
        val first = mvc.get().uri("/gated").asyncExchange()
        assertTrue(first.request.isAsyncStarted)
        val before = first.response.headerNames.toSet()

        gate.complete(Unit)
        first.mvcResult.getAsyncResult(10_000)

        assertEquals(before, first.response.headerNames.toSet())
        assertNull(first.response.getHeader("Content-Encoding"))
        assertTrue("Connection" in before, "$before")
    }
}
