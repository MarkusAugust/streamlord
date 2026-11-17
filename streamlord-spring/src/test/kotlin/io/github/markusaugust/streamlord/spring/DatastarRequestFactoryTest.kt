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
import kotlin.concurrent.thread
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** RestTestClient over MockMvc, with every async cycle of a stream run before the answer. */
class DatastarRequestFactoryTest {
    companion object {
        val streamlord = Streamlord(maxSignalsSize = 32, heartbeat = null)
        var release = CompletableFuture<Unit>()
        val waiting =
            java.util.concurrent.atomic
                .AtomicInteger()
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

        @GetMapping("/inside")
        fun inside(
            request: HttpServletRequest,
            response: HttpServletResponse,
        ): StreamingResponseBody =
            response.datastarStream(streamlord, request) {
                patchSignals("heads" to (request.readSignals(streamlord).int("heads") ?: 0))
            }

        @GetMapping("/rawNow")
        fun rawNow(
            request: HttpServletRequest,
            response: HttpServletResponse,
        ) {
            val context = request.startAsync()
            thread {
                response.writer.write("done")
                response.flushBuffer()
                context.complete()
            }
        }

        @GetMapping("/deferred")
        fun deferred(): org.springframework.web.context.request.async.DeferredResult<String> {
            val result =
                org.springframework.web.context.request.async
                    .DeferredResult<String>()
            val test = Thread.currentThread()
            thread {
                Thread.sleep(20)
                result.setResult("late")
                // Just after the dispatch, while the test's thread goes on to perform it.
                val until = System.nanoTime() + 20_000
                while (System.nanoTime() < until) Thread.onSpinWait()
                test.interrupt()
            }
            return result
        }

        @GetMapping("/raw")
        fun raw(
            request: HttpServletRequest,
            response: HttpServletResponse,
        ) {
            val context = request.startAsync()
            thread {
                Thread.sleep(50)
                response.writer.write("done")
                response.flushBuffer()
                context.complete()
            }
        }

        @GetMapping("/eager")
        suspend fun eager(
            request: HttpServletRequest,
            response: HttpServletResponse,
        ): StreamingResponseBody = response.datastarStream(streamlord, request) { patchSignals("heads" to 1) }

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
        ): StreamingResponseBody =
            response.datastarStream(streamlord, request) {
                waiting.incrementAndGet()
                try {
                    release.get()
                } finally {
                    waiting.decrementAndGet()
                }
            }
    }

    @BeforeTest
    fun reset() {
        release = CompletableFuture()
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
    fun `a failure inside the stream, before its first event, keeps its status`() {
        val status =
            client
                .get()
                .uri("/inside?datastar={s}", """{"heads":13,"far":"too long for the limit"}""")
                .exchange()
                .returnResult(String::class.java)
                .status

        assertEquals(413, status.value())
    }

    @Test
    fun `a cookie the servlet API refuses answers 500, as Spring's binding does`() {
        val result =
            client
                .get()
                .uri("/who")
                .cookie("a b", "1")
                .exchange()
                .returnResult(String::class.java)

        assertEquals(500, result.status.value())
    }

    @Test
    fun `a timeout that is not positive is refused where the factory is made`() {
        assertFailsWith<IllegalArgumentException> { mvc.datastarRequestFactory(kotlin.time.Duration.ZERO) }
    }

    @Test
    fun `an infinite timeout is taken, and waits for the answer`() {
        val slow = RestTestClient.bindToServer(mvc.datastarRequestFactory(kotlin.time.Duration.INFINITE)).build()

        datastarEvents(
            slow
                .get()
                .uri("/suspended")
                .exchange()
                .returnResult(String::class.java)
                .responseBody
                .orEmpty(),
        ).assertSignal("heads", 1)
    }

    @Test
    fun `an interrupted test is told so, and the stream is stopped`() {
        val test = Thread.currentThread()
        thread {
            while (waiting.get() == 0) Thread.sleep(1)
            test.interrupt()
        }
        try {
            assertFailsWith<InterruptedException> { client.get().uri("/forever").exchange() }
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
        Thread.sleep(50)

        assertTrue(!release.isDone && waiting.get() == 0, "the stream is still waiting")
    }

    @Test
    fun `an interrupt is not lost when the cycle has already ended`() {
        Thread.currentThread().interrupt()
        try {
            assertFailsWith<InterruptedException> { client.get().uri("/eager").exchange() }
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `a request completed without a dispatch is answered as it stands`() {
        val quick = RestTestClient.bindToServer(mvc.datastarRequestFactory(5.seconds)).build()
        quick.get().uri("/feed").exchange()
        val started = System.nanoTime()

        assertEquals(
            "done",
            quick
                .get()
                .uri("/raw")
                .exchange()
                .returnResult(String::class.java)
                .responseBody,
        )
        assertTrue(System.nanoTime() - started < 4_000_000_000, "took ${(System.nanoTime() - started) / 1_000_000} ms")
    }

    @Test
    fun `a request completed at once without a dispatch is answered every time`() {
        repeat(200) {
            assertEquals(
                "done",
                client
                    .get()
                    .uri("/rawNow")
                    .exchange()
                    .returnResult(String::class.java)
                    .responseBody,
            )
        }
    }

    @Test
    fun `an interrupt just after the dispatch is no failure of the answer`() {
        repeat(100) {
            try {
                val result =
                    runCatching {
                        client
                            .get()
                            .uri("/deferred")
                            .exchange()
                            .returnResult(String::class.java)
                    }
                result.exceptionOrNull()?.let { assertIs<InterruptedException>(it) }
                result.getOrNull()?.let {
                    assertEquals(200, it.status.value(), it.responseBody)
                    assertEquals("late", it.responseBody)
                }
            } finally {
                // Past the controller's interrupt, which may come after the answer, then clear it.
                Thread.interrupted()
                runCatching { Thread.sleep(30) }
                Thread.interrupted()
            }
        }
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
