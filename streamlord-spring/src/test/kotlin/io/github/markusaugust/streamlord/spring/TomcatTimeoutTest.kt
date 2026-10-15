package io.github.markusaugust.streamlord.spring

import io.github.markusaugust.streamlord.core.port.driving.patchSignals
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import kotlinx.coroutines.delay
import org.apache.catalina.startup.Tomcat
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
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
    @RestController
    class Streams {
        // Forty events, one every 100 ms: four seconds, long past the 300 ms default.
        private fun HttpServletResponse.count(request: HttpServletRequest, timeout: Duration?): StreamingResponseBody =
            datastarStream(request = request, timeout = timeout) {
                repeat(40) {
                    patchSignals("n" to it)
                    delay(100.milliseconds)
                }
            }

        @GetMapping("/default")
        fun default(request: HttpServletRequest, response: HttpServletResponse) = response.count(request, null)

        @GetMapping("/infinite")
        fun infinite(request: HttpServletRequest, response: HttpServletResponse) = response.count(request, Duration.INFINITE)

        @GetMapping("/two-seconds")
        fun twoSeconds(request: HttpServletRequest, response: HttpServletResponse) = response.count(request, 2.seconds)

        // Without the request: the adapter finds it through Spring's RequestContextHolder.
        @GetMapping("/held")
        fun held(response: HttpServletResponse): StreamingResponseBody =
            response.datastarStream(timeout = Duration.INFINITE) {
                repeat(40) {
                    patchSignals("n" to it)
                    delay(100.milliseconds)
                }
            }

        // Spring runs a suspend handler in an async cycle of its own, and the stream in a second.
        @GetMapping("/suspended")
        suspend fun suspended(request: HttpServletRequest, response: HttpServletResponse) =
            response.count(request, Duration.INFINITE)

        @GetMapping("/suspended-first")
        suspend fun suspendedFirst(request: HttpServletRequest, response: HttpServletResponse): StreamingResponseBody {
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
    }

    private lateinit var tomcat: Tomcat
    private lateinit var baseDir: Path
    private var port = 0

    @BeforeTest
    fun start() {
        tomcat = Tomcat()
        baseDir = Files.createTempDirectory("tomcat")
        tomcat.setBaseDir(baseDir.toString())
        tomcat.setPort(0)
        val context = tomcat.addContext("", null)
        val spring = AnnotationConfigWebApplicationContext().apply { register(Mvc::class.java) }
        Tomcat.addServlet(context, "dispatcher", DispatcherServlet(spring)).apply {
            isAsyncSupported = true
            setLoadOnStartup(1)
        }
        context.addServletMappingDecoded("/", "dispatcher")
        tomcat.start()
        port = tomcat.connector.localPort
    }

    @AfterTest
    fun stop() {
        tomcat.stop()
        tomcat.destroy()
        // Tomcat records its base directory as a JVM-wide property, which the next instance would
        // otherwise make again after this one is gone.
        System.clearProperty("catalina.base")
        System.clearProperty("catalina.home")
        baseDir.toFile().deleteRecursively()
    }

    private fun events(path: String): Pair<Int, Long> {
        val started = System.nanoTime()
        val lines =
            HttpClient.newHttpClient()
                .send(HttpRequest.newBuilder(URI("http://localhost:$port$path")).build(), HttpResponse.BodyHandlers.ofLines())
                .body()
                .use { lines -> lines.filter { it.startsWith("event: ") }.count() }
        return lines.toInt() to (System.nanoTime() - started) / 1_000_000
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
