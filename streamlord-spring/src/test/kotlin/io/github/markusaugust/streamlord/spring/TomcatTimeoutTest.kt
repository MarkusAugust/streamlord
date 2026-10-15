package io.github.markusaugust.streamlord.spring

import io.github.markusaugust.streamlord.core.SignalsTooLargeException
import io.github.markusaugust.streamlord.core.application.StreamAuthorisation
import io.github.markusaugust.streamlord.core.port.driving.patchSignals
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import kotlinx.coroutines.delay
import org.apache.catalina.startup.Tomcat
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext
import org.springframework.web.servlet.DispatcherServlet
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer
import org.springframework.web.servlet.config.annotation.EnableWebMvc
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A real Tomcat and a real DispatcherServlet, without Boot, with Spring's async timeout at 300 ms.
 * Tomcat checks for timeouts once a second, so the bounds here are seconds, not milliseconds.
 */
class TomcatTimeoutTest {
    companion object {
        val seen: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
    }

    @RestController
    class Streams {
        // Forty events, one every 100 ms: four seconds, long past the 300 ms default.
        private fun HttpServletResponse.count(
            request: HttpServletRequest,
            timeout: Duration?,
        ): StreamingResponseBody =
            datastarStream(request = request, timeout = timeout) {
                repeat(40) {
                    patchSignals("n" to it)
                    delay(100.milliseconds)
                }
            }

        @GetMapping("/default")
        fun default(
            request: HttpServletRequest,
            response: HttpServletResponse,
        ) = response.count(request, null)

        @GetMapping("/infinite")
        fun infinite(
            request: HttpServletRequest,
            response: HttpServletResponse,
        ) = response.count(request, Duration.INFINITE)

        @GetMapping("/two-seconds")
        fun twoSeconds(
            request: HttpServletRequest,
            response: HttpServletResponse,
        ) = response.count(request, 2.seconds)

        // Without the request: the adapter finds it through Spring's RequestContextHolder.
        @GetMapping("/held")
        fun held(response: HttpServletResponse): StreamingResponseBody =
            response.datastarStream(timeout = Duration.INFINITE) {
                repeat(40) {
                    patchSignals("n" to it)
                    delay(100.milliseconds)
                }
            }

        // Thrown a second in, before the first event and before the default heartbeat's first
        // comment is due. The timeout keeps the 300 ms default of this test from cutting it first.
        @GetMapping("/too-large")
        fun tooLarge(
            request: HttpServletRequest,
            response: HttpServletResponse,
        ): StreamingResponseBody =
            response.datastarStream(request = request, timeout = 10.seconds) {
                delay(1000.milliseconds)
                throw SignalsTooLargeException(10, 5)
            }

        // Where the request context is: in the block, and in the authorisation's first answer, but
        // not when the heartbeat asks from Dispatchers.IO.
        @GetMapping("/contexts")
        fun contexts(
            request: HttpServletRequest,
            response: HttpServletResponse,
        ): StreamingResponseBody =
            response.datastarStream(
                request = request,
                authorisation =
                    StreamAuthorisation(every = 1.milliseconds) {
                        seen += "allows ${RequestContextHolder.getRequestAttributes() != null}"
                        true
                    },
                heartbeat = 50.milliseconds,
            ) {
                seen += "block ${RequestContextHolder.getRequestAttributes() != null}"
                Thread.sleep(400)
            }

        // Spring runs a suspend handler in an async cycle of its own, and the stream in a second.
        @GetMapping("/suspended")
        suspend fun suspended(
            request: HttpServletRequest,
            response: HttpServletResponse,
        ) = response.count(request, Duration.INFINITE)

        @GetMapping("/suspended-first")
        suspend fun suspendedFirst(
            request: HttpServletRequest,
            response: HttpServletResponse,
        ): StreamingResponseBody {
            delay(50.milliseconds)
            return response.count(request, Duration.INFINITE)
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    class Mvc : WebMvcConfigurer {
        override fun configureAsyncSupport(configurer: AsyncSupportConfigurer) {
            configurer.setDefaultTimeout(300)
        }

        @Bean
        fun streams() = Streams()

        @Bean
        fun errors() = StreamlordExceptionHandler()
    }

    private lateinit var tomcat: RealTomcat
    private val port get() = tomcat.port

    @BeforeTest
    fun start() {
        tomcat = RealTomcat(Mvc::class.java)
    }

    @AfterTest
    fun stop() {
        tomcat.close()
    }

    private fun events(path: String): Pair<Int, Long> {
        val started = System.nanoTime()
        val lines = tomcat.lines(path).count { it.startsWith("event: ") }
        return lines to (System.nanoTime() - started) / 1_000_000
    }

    @Test
    fun `the block has the request context and the heartbeat's authorisation does not`() {
        seen.clear()
        events("/contexts")

        assertEquals(listOf("allows true", "block true"), seen.take(2))
        assertTrue("allows false" in seen.drop(2), seen.toString())
    }

    @Test
    fun `a failure before the first event still answers with its status`() {
        val response =
            HttpClient
                .newHttpClient()
                .send(HttpRequest.newBuilder(URI("http://localhost:$port/too-large")).build(), HttpResponse.BodyHandlers.ofString())

        assertEquals(413, response.statusCode(), response.body())
    }

    @Test
    fun `without a timeout Spring's default cuts the stream`() {
        val (events, millis) = events("/default")

        assertTrue(events < 40, "$events events in $millis ms")
        assertTrue(millis < 3000, "$events events in $millis ms")
    }

    // Cut between two and three seconds: the timeout, then Tomcat's once-a-second check.
    @Test
    fun `a finite timeout replaces Spring's default`() {
        val (events, millis) = events("/two-seconds")

        assertTrue(events in 15..39, "$events events in $millis ms")
    }

    @Test
    fun `without the request the timeout still reaches the stream`() {
        val (events, millis) = events("/held")

        assertEquals(40, events, "$events events in $millis ms")
    }

    @Test
    fun `a suspend controller's timeout reaches the stream`() {
        val (events, millis) = events("/suspended")

        assertEquals(40, events, "$events events in $millis ms")
    }

    @Test
    fun `a suspend controller that suspends first still sets the timeout`() {
        val (events, millis) = events("/suspended-first")

        assertEquals(40, events, "$events events in $millis ms")
    }

    @Test
    fun `an infinite timeout lets the stream run to its end`() {
        val (events, millis) = events("/infinite")

        assertEquals(40, events, "$events events in $millis ms")
    }
}
