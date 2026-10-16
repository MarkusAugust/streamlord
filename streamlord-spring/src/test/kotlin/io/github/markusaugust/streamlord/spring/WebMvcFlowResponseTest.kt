package io.github.markusaugust.streamlord.spring

import io.github.markusaugust.streamlord.core.application.Streamlord
import io.github.markusaugust.streamlord.core.domain.PatchSignals
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.ResponseEntity
import org.springframework.http.codec.ServerSentEvent
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer
import org.springframework.web.servlet.config.annotation.EnableWebMvc
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * asDatastarResponse returned from a WebMVC controller, on a real Tomcat with Spring's async
 * timeout at 300 ms. Spring writes the flow through an SseEmitter of its own that has no
 * timeout, and the Streamlord stream is never opened: no heartbeat or gzip reaches it.
 */
class WebMvcFlowResponseTest {
    @RestController
    class Streams {
        private val streamlord = Streamlord(heartbeat = 50.milliseconds, compress = true)

        // Fifteen events, one every 100 ms, with a quiet second in the middle.
        @GetMapping("/flow")
        fun flow(): ResponseEntity<kotlinx.coroutines.flow.Flow<ServerSentEvent<String>>> =
            flow {
                repeat(15) {
                    if (it == 7) delay(1000.milliseconds)
                    threads += Thread.currentThread().name
                    emit(PatchSignals("{\"n\":$it}"))
                    delay(100.milliseconds)
                }
            }.asDatastarResponse(streamlord)
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

    private lateinit var tomcat: RealTomcat

    companion object {
        val threads = java.util.concurrent.CopyOnWriteArrayList<String>()
    }

    @BeforeTest
    fun start() {
        tomcat = RealTomcat(Mvc::class.java)
    }

    @AfterTest
    fun stop() {
        tomcat.close()
    }

    @Test
    fun `a flow returned from WebMVC streams past the async timeout, without heartbeat or gzip`() {
        val started = System.nanoTime()
        val response =
            HttpClient
                .newHttpClient()
                .send(
                    HttpRequest.newBuilder(URI("http://localhost:${tomcat.port}/flow")).header("Accept-Encoding", "gzip").build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
        val body = response.body()
        val took = (System.nanoTime() - started) / 1_000_000

        assertEquals(200, response.statusCode(), body)
        assertEquals(15, Regex("^event: ?datastar-patch-signals$", RegexOption.MULTILINE).findAll(body).count(), body)
        assertFalse(": keep-alive" in body, "a heartbeat reached it:\n$body")
        assertEquals(null, response.headers().firstValue("content-encoding").orElse(null))
        assertTrue(took > 1500, "the stream took $took ms")
        // The flow suspends between elements; after the first, no request thread runs it.
        assertTrue(threads.drop(1).none { it.startsWith("http-") }, "$threads")
    }
}
