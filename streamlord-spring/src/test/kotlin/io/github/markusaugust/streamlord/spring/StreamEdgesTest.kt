package io.github.markusaugust.streamlord.spring

import io.github.markusaugust.streamlord.core.SignalsTooLargeException
import io.github.markusaugust.streamlord.core.application.Streamlord
import io.github.markusaugust.streamlord.core.port.driving.patchSignals
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import kotlinx.coroutines.CompletableDeferred
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.filter.ShallowEtagHeaderFilter
import org.springframework.web.servlet.AsyncHandlerInterceptor
import org.springframework.web.servlet.config.annotation.EnableWebMvc
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration

/** How a stream starts, fails and flushes on a real Tomcat, behind the filters a Boot app has. */
class StreamEdgesTest {
    companion object {
        val streamlord = Streamlord(compress = true, heartbeat = null)
        var gate = CompletableFuture<Unit>()
        var resume = CompletableDeferred<Unit>()
        var suspended = CompletableFuture<Unit>()
    }

    @RestController
    class Streams {
        @GetMapping("/fail-before")
        fun failBefore(
            request: HttpServletRequest,
            response: HttpServletResponse,
        ): StreamingResponseBody = response.datastarStream(streamlord, request) { error("before anything was written") }

        @GetMapping("/plain")
        fun plain(
            request: HttpServletRequest,
            response: HttpServletResponse,
        ): StreamingResponseBody = response.datastarStream(streamlord, request) { patchSignals("n" to 1) }

        @GetMapping("/too-large-before")
        fun tooLargeBefore(response: HttpServletResponse): StreamingResponseBody =
            response.datastarStream(streamlord) { throw SignalsTooLargeException(2, 1) }

        @GetMapping("/too-large-after")
        fun tooLargeAfter(response: HttpServletResponse): StreamingResponseBody =
            response.datastarStream(streamlord) {
                patchSignals("n" to 1)
                throw SignalsTooLargeException(2, 1)
            }

        @GetMapping("/gated")
        fun gated(response: HttpServletResponse): StreamingResponseBody =
            response.datastarStream(streamlord) {
                patchSignals("n" to 1)
                gate.get(30, TimeUnit.SECONDS)
                patchSignals("n" to 2)
            }

        // Resumed by /release, on its thread, where Spring's request holder holds the other request.
        @GetMapping("/stale")
        suspend fun stale(response: HttpServletResponse): StreamingResponseBody {
            resume.await()
            return response.datastarStream(streamlord, timeout = Duration.INFINITE) { patchSignals("n" to 1) }
        }

        @GetMapping("/release")
        fun release(response: HttpServletResponse): StreamingResponseBody {
            // Only once /stale has suspended: resumed any earlier, it would go on on its own thread.
            suspended.get(30, TimeUnit.SECONDS)
            resume.complete(Unit)
            return response.datastarStream(streamlord) { patchSignals("released" to true) }
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @Import(StreamlordExceptionHandler::class)
    class Mvc : WebMvcConfigurer {
        @Bean
        fun streams() = Streams()

        // Spring runs a suspend handler until it suspends, then starts the async cycle and lets its
        // thread go; only then is /stale sure to wait for /release.
        override fun addInterceptors(registry: InterceptorRegistry) {
            registry.addInterceptor(
                object : AsyncHandlerInterceptor {
                    override fun afterConcurrentHandlingStarted(
                        request: HttpServletRequest,
                        response: HttpServletResponse,
                        handler: Any,
                    ) {
                        if (request.requestURI == "/stale") suspended.complete(Unit)
                    }
                },
            )
        }
    }

    private lateinit var tomcat: RealTomcat

    @BeforeTest
    fun start() {
        gate = CompletableFuture()
        resume = CompletableDeferred()
        suspended = CompletableFuture()
        tomcat = RealTomcat(Mvc::class.java)
    }

    @AfterTest
    fun stop() {
        tomcat.close()
    }

    private fun raw(
        path: String,
        headers: String = "",
    ): String = rawGet(tomcat.port, path, headers)

    @Test
    fun `an error answer carries none of the stream's own headers`() {
        val response = raw("/fail-before", "Accept-Encoding: gzip\r\n")

        assertTrue(response.startsWith("HTTP/1.1 500"), response)
        assertFalse("content-encoding" in response.lowercase(), response)
        assertFalse("connection: keep-alive" in response.lowercase(), response)
    }

    @Test
    fun `a request that asks to close is not told keep-alive`() {
        val response = raw("/plain", "Connection: close\r\n")

        assertTrue("datastar-patch-signals" in response, response)
        assertFalse("connection: keep-alive" in response.lowercase(), response)
        assertTrue("connection: keep-alive" in raw("/plain").lowercase())
    }

    @Test
    fun `a failure before the stream starts still answers with its status`() {
        assertTrue(raw("/too-large-before").startsWith("HTTP/1.1 413"))
    }

    // An answer would end the chunked body with its last chunk, which Datastar's client takes for
    // a stream that finished, and it would not reconnect.
    @Test
    fun `a failure after the stream started cuts it, whatever handles the exception`() {
        val response = raw("/too-large-after")

        assertTrue("datastar-patch-signals" in response, response)
        assertFalse(response.endsWith("0\r\n\r\n"), response)
    }

    @Test
    fun `ShallowEtagHeaderFilter does not hold the stream back`() {
        RealTomcat(Mvc::class.java, filters = listOf(ShallowEtagHeaderFilter())).use { etag -> readGated(etag.port) }
    }

    private fun readGated(port: Int) {
        Socket("localhost", port).use { socket ->
            socket.soTimeout = 30_000
            socket.getOutputStream().write("GET /gated HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
            val seen = StringBuilder()
            val buffer = ByteArray(8192)
            while ("\"n\":1" !in seen) {
                val n = socket.getInputStream().read(buffer)
                if (n < 0) break
                seen.append(String(buffer, 0, n, Charsets.ISO_8859_1))
            }
            gate.complete(Unit)
            assertTrue("\"n\":1" in seen, "the first event did not arrive before the second was let go:\n$seen")
        }
    }

    @Test
    fun `a suspend controller resumed on another request's thread cannot set that request's timeout`() {
        val staleAnswer = CompletableFuture.supplyAsync { raw("/stale") }
        val released = raw("/release")

        assertTrue(released.startsWith("HTTP/1.1 200"), released)
        val answer = staleAnswer.get(60, TimeUnit.SECONDS)
        assertTrue(answer.startsWith("HTTP/1.1 500"), answer)
    }

    @Test
    fun `a response whose writer is taken fails where the stream is asked for`() {
        // As a container's response refuses its output stream once the writer is taken.
        val writerTaken = MockHttpServletResponse().apply { isOutputStreamAccessAllowed = false }
        val e = assertFailsWith<IllegalStateException> { writerTaken.datastarStream { } }
        assertTrue("writer" in e.message.orEmpty(), e.message)
    }

    @Test
    fun `signals read with no size limit`() {
        val request =
            MockHttpServletRequest("POST", "/").apply {
                contentType = "application/json"
                setContent("""{"a":1}""".toByteArray())
            }

        assertEquals(1, request.readSignals(Streamlord(maxSignalsSize = Int.MAX_VALUE)).int("a"))
    }
}
