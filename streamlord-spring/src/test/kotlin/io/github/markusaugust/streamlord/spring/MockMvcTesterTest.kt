package io.github.markusaugust.streamlord.spring

import io.github.markusaugust.streamlord.core.SignalsTooLargeException
import io.github.markusaugust.streamlord.core.application.Streamlord
import io.github.markusaugust.streamlord.core.port.driving.patchSignals
import io.github.markusaugust.streamlord.core.protocol.DatastarProtocol
import io.github.markusaugust.streamlord.spring.test.datastar
import io.github.markusaugust.streamlord.test.datastarEvents
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import kotlinx.coroutines.delay
import org.assertj.core.api.Assertions.assertThat
import org.springframework.http.HttpMethod
import org.springframework.test.web.servlet.assertj.MockMvcTester
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestMethod
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** [datastar], the helper the testing page shows, held to what it says. */
class MockMvcTesterTest {
    companion object {
        val streamlord = Streamlord(maxSignalsSize = 32, heartbeat = null)
        var gate = CompletableFuture<Unit>()
        var seen: Map<String, String?> = emptyMap()
        val ticks = AtomicInteger()
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

        @RequestMapping("/echo", method = [RequestMethod.GET, RequestMethod.POST])
        fun echo(
            request: HttpServletRequest,
            response: HttpServletResponse,
        ): StreamingResponseBody {
            seen =
                mapOf(
                    "Accept" to request.getHeader("Accept"),
                    "Datastar-Request" to request.getHeader("Datastar-Request"),
                    "Content-Type" to request.getHeader("Content-Type"),
                    "datastar" to request.getParameterValues("datastar")?.single(),
                    "query" to request.queryString,
                    "body" to request.inputStream.readAllBytes().decodeToString(),
                )
            return response.datastarStream(streamlord, request) { patchSignals("heads" to 1) }
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

        @GetMapping("/interruptsCaller")
        fun interruptsCaller(
            request: HttpServletRequest,
            response: HttpServletResponse,
        ): StreamingResponseBody {
            // MockMvc runs the controller on the test's thread.
            Thread.currentThread().interrupt()
            return response.datastarStream(streamlord, request) { patchSignals("heads" to 1) }
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

        @GetMapping("/endless")
        fun endless(
            request: HttpServletRequest,
            response: HttpServletResponse,
        ): StreamingResponseBody =
            response.datastarStream(streamlord, request) {
                while (true) {
                    patchSignals("tick" to ticks.incrementAndGet())
                    delay(10)
                }
            }

        @GetMapping("/slow")
        suspend fun slow(
            request: HttpServletRequest,
            response: HttpServletResponse,
        ): StreamingResponseBody {
            delay(150)
            return response.datastarStream(streamlord, request) {
                Thread.sleep(150)
                patchSignals("heads" to 1)
            }
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
        ticks.set(0)
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

    // What Datastar 1.0.5's fetch.ts sends, as the controller receives it.
    @Test
    fun `the helper sends what Datastar's client sends`() {
        mvc.datastar(HttpMethod.GET, "/echo", """{"heads":13}""")
        assertEquals(
            mapOf(
                "Accept" to "text/event-stream, text/html, application/json",
                "Datastar-Request" to "true",
                "Content-Type" to null,
                "datastar" to """{"heads":13}""",
                "query" to "datastar=%7B%22heads%22:13%7D",
                "body" to "",
            ),
            seen,
        )

        // In the query string, where the client puts them, in place of one the URI already has.
        mvc.datastar(HttpMethod.GET, "/echo?x=1&datastar=%7B%7D", """{"heads":13}""")
        assertEquals("""{"heads":13}""", seen["datastar"])
        assertEquals("x=1&datastar=%7B%22heads%22:13%7D", seen["query"])

        mvc.datastar(HttpMethod.POST, "/echo", """{"heads":13}""")
        assertEquals("application/json", seen["Content-Type"])
        assertEquals("""{"heads":13}""", seen["body"])
        assertEquals(null, seen["datastar"])

        mvc.datastar(HttpMethod.POST, "/echo")
        assertEquals("application/json", seen["Content-Type"])
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

    @Test
    fun `a stream that does not end is stopped when the wait fails`() {
        assertFailsWith<IllegalStateException> { mvc.datastar(HttpMethod.GET, "/endless", asyncTimeout = 200.milliseconds) }
        Thread.sleep(50) // a write already under way when the stream was told
        val after = ticks.get()
        Thread.sleep(300)

        assertEquals(after, ticks.get(), "the stream kept writing after the wait gave up")
    }

    @Test
    fun `the timeout is for the whole answer, every cycle together`() {
        val failure = assertFailsWith<IllegalStateException> { mvc.datastar(HttpMethod.GET, "/slow", asyncTimeout = 200.milliseconds) }

        assertTrue("had not ended after 200ms" in failure.message.orEmpty(), failure.message)
    }

    @Test
    fun `an interrupt during the request is not lost when its cycle has already ended`() {
        try {
            assertFailsWith<InterruptedException> { mvc.datastar(HttpMethod.GET, "/interruptsCaller") }
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `a thread interrupted before the call sends nothing`() {
        Thread.currentThread().interrupt()
        try {
            assertFailsWith<InterruptedException> { mvc.datastar(HttpMethod.GET, "/eager") }
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `a request completed without a dispatch is answered as it stands`() {
        mvc.datastar(HttpMethod.GET, "/feed")
        val started = System.nanoTime()
        val result = mvc.datastar(HttpMethod.GET, "/raw", asyncTimeout = 5.seconds)

        assertEquals("done", result.response.contentAsString)
        assertTrue(System.nanoTime() - started < 4_000_000_000, "took ${(System.nanoTime() - started) / 1_000_000} ms")
    }

    @Test
    fun `a request completed at once without a dispatch is answered every time`() {
        repeat(200) { assertEquals("done", mvc.datastar(HttpMethod.GET, "/rawNow", asyncTimeout = 5.seconds).response.contentAsString) }
    }

    // The dispatched answer is written on the test's thread, where Spring itself may clear an
    // interrupt; what must not happen is the interrupt turning into a failure of Spring's own wait.
    @Test
    fun `an interrupt just after the dispatch is no failure of the answer`() {
        repeat(100) {
            try {
                val result = runCatching { mvc.datastar(HttpMethod.GET, "/deferred") }
                result.exceptionOrNull()?.let { assertIs<InterruptedException>(it) }
                result.getOrNull()?.let { assertEquals("late", it.response.contentAsString) }
            } finally {
                // Past the controller's interrupt, which may come after the answer, then clear it.
                Thread.interrupted()
                runCatching { Thread.sleep(30) }
                Thread.interrupted()
            }
        }
    }

    @Test
    fun `the helper answers as soon as the stream has ended`() {
        mvc.datastar(HttpMethod.GET, "/feed")
        val started = System.nanoTime()
        repeat(50) { mvc.datastar(HttpMethod.GET, "/feed") }

        assertTrue(System.nanoTime() - started < 2_000_000_000, "50 answers took ${(System.nanoTime() - started) / 1_000_000} ms")
    }

    @Test
    fun `an interrupted test is told so, stays interrupted, and the stream is stopped`() {
        val test = Thread.currentThread()
        thread {
            while (ticks.get() == 0) Thread.sleep(1)
            test.interrupt()
        }
        try {
            assertFailsWith<InterruptedException> { mvc.datastar(HttpMethod.GET, "/endless") }
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
        Thread.sleep(50) // a write already under way when the stream was told
        val after = ticks.get()
        Thread.sleep(300)

        assertEquals(after, ticks.get(), "the stream kept writing after the interrupt")
    }

    @Test
    fun `an infinite timeout is taken, and waits for the answer`() {
        val result = mvc.datastar(HttpMethod.GET, "/slow", asyncTimeout = kotlin.time.Duration.INFINITE)

        assertThat(result).hasStatusOk()
    }

    @Test
    fun `a request that goes async outside Spring MVC fails without a false interrupt`() {
        val tester =
            MockMvcTester.of(listOf(Feed())) {
                it
                    .addFilter<org.springframework.test.web.servlet.setup.StandaloneMockMvcBuilder>(
                        jakarta.servlet.Filter { request, _, _ -> request.startAsync() },
                        "/byfilter",
                    ).build()
            }

        val failure =
            assertFailsWith<IllegalStateException> { tester.datastar(HttpMethod.GET, "/byfilter", asyncTimeout = 500.milliseconds) }

        assertTrue(!Thread.currentThread().isInterrupted, failure.message)
        assertTrue("had not ended" in failure.message.orEmpty(), failure.message)
    }

    @Test
    fun `a timeout that is not positive is refused`() {
        assertFailsWith<IllegalArgumentException> { mvc.datastar(HttpMethod.GET, "/feed", asyncTimeout = kotlin.time.Duration.ZERO) }
    }

    @Test
    fun `a stream that does not end fails the wait, naming the way to read one`() {
        val failure =
            try {
                assertFailsWith<IllegalStateException> { mvc.datastar(HttpMethod.GET, "/gated", asyncTimeout = 200.milliseconds) }
            } finally {
                gate.complete(Unit)
            }

        assertTrue("200ms" in failure.message.orEmpty() && "LiveDatastarStream" in failure.message.orEmpty(), failure.message)
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
