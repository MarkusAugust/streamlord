package io.github.markusaugust.streamlord.spring

import io.github.markusaugust.streamlord.core.port.driving.patchSignals
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer
import org.springframework.web.servlet.config.annotation.EnableWebMvc
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody
import java.util.concurrent.CompletableFuture
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * A real Tomcat whose Spring MVC executor has one thread, as Boot's would once its eight are
 * taken: a stream holding it makes the next one wait, unless that one brings its own executor.
 */
class StreamExecutorTest {
    companion object {
        fun executor(name: String): ThreadPoolTaskExecutor =
            ThreadPoolTaskExecutor().apply {
                corePoolSize = 1
                maxPoolSize = 1
                setThreadNamePrefix("$name-")
                setDaemon(true)
                initialize()
            }

        val own: ThreadPoolTaskExecutor = executor("own")
    }

    @RestController
    class Streams {
        // Holds its thread for a second and a half.
        @GetMapping("/hold")
        fun hold(response: HttpServletResponse): StreamingResponseBody =
            response.datastarStream(heartbeat = null) {
                patchSignals("held" to true)
                Thread.sleep(1500)
            }

        @GetMapping("/shared")
        fun shared(response: HttpServletResponse): StreamingResponseBody =
            response.datastarStream { patchSignals("thread" to Thread.currentThread().name) }

        @GetMapping("/own")
        fun own(response: HttpServletResponse): StreamingResponseBody =
            response.datastarStream(executor = own) { patchSignals("thread" to Thread.currentThread().name) }

        @GetMapping("/own-flow")
        fun ownFlow(response: HttpServletResponse): StreamingResponseBody =
            response.datastarStream(
                flow { emit(io.github.markusaugust.streamlord.core.domain.PatchSignals("{\"flow\":true}")) },
                executor = own,
            )

        // Spring sets its own executor again for the cycle that runs the stream of a suspend handler.
        @GetMapping("/own-suspended")
        suspend fun ownSuspended(response: HttpServletResponse): StreamingResponseBody {
            delay(10.milliseconds)
            return response.datastarStream(executor = own) { patchSignals("thread" to Thread.currentThread().name) }
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    class Mvc : WebMvcConfigurer {
        override fun configureAsyncSupport(configurer: AsyncSupportConfigurer) {
            configurer.setTaskExecutor(executor("mvc"))
            configurer.setDefaultTimeout(10_000)
        }

        @Bean
        fun streams() = Streams()
    }

    private lateinit var tomcat: RealTomcat

    @BeforeTest
    fun start() {
        tomcat = RealTomcat(Mvc::class.java)
    }

    @AfterTest
    fun stop() {
        tomcat.close()
    }

    /** The body of [path], and how long it took, while /hold has the MVC thread. */
    private fun whileHeld(path: String): Pair<String, Long> {
        val held = CompletableFuture.supplyAsync { tomcat.get("/hold").body().use { it.toList() } }
        Thread.sleep(200)
        val started = System.nanoTime()
        val body = tomcat.get(path).body().use { it.toList().joinToString("\n") }
        val millis = (System.nanoTime() - started) / 1_000_000
        held.join()
        return body to millis
    }

    @Test
    fun `without its own executor a stream waits for the MVC thread`() {
        val (body, millis) = whileHeld("/shared")

        assertTrue("mvc-" in body, body)
        assertTrue(millis > 1000, "answered in $millis ms while the MVC thread was held")
    }

    @Test
    fun `with its own executor a stream runs at once`() {
        val (body, millis) = whileHeld("/own")

        assertTrue("own-" in body, body)
        assertTrue(millis < 1000, "answered in $millis ms")
    }

    @Test
    fun `the flow variant takes an executor`() {
        val (body, millis) = whileHeld("/own-flow")

        assertTrue("\"flow\":true" in body, body)
        assertTrue(millis < 1000, "answered in $millis ms")
    }

    @Test
    fun `a suspend controller's executor runs the stream`() {
        val body = tomcat.get("/own-suspended").body().use { it.toList().joinToString("\n") }

        assertTrue("own-" in body, body)
    }

    @Test
    fun `an executor outside Spring MVC fails rather than being ignored`() {
        assertFailsWith<IllegalStateException> {
            MockHttpServletResponse().datastarStream(request = MockHttpServletRequest(), executor = own) {}
        }
    }
}
