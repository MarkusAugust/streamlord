package io.github.markusaugust.streamlord.spring

import io.github.markusaugust.streamlord.core.port.driving.patchSignals
import io.github.markusaugust.streamlord.wire.LOOPBACK
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import kotlinx.coroutines.delay
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.test.web.servlet.client.MockMvcWebTestClient
import org.springframework.test.web.servlet.client.RestTestClient
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.config.annotation.EnableWebMvc
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What Spring's own test clients make of a stream. Against MockMvc they hand back what was there
 * before the async dispatch: RestTestClient never dispatches, WebTestClient dispatches once, which
 * leaves a `suspend` controller's stream unread. When one of these starts failing, Spring has
 * changed, and the testing page's note on them goes. The gate holds each stream for half a second,
 * long after a client that runs too few cycles has answered: what it reads is what the stream had
 * written by then. A client that ran them all would read the stream, and the test would fail on it.
 */
class SpringTestClientsTest {
    companion object {
        var gate = CompletableFuture<Unit>()
    }

    @RestController
    class Feed {
        @GetMapping("/feed")
        fun feed(response: HttpServletResponse): StreamingResponseBody =
            response.datastarStream {
                gate.get(10, TimeUnit.SECONDS)
                patchSignals("heads" to 1)
            }

        @GetMapping("/suspended")
        suspend fun suspended(
            request: HttpServletRequest,
            response: HttpServletResponse,
        ): StreamingResponseBody {
            delay(1)
            return response.datastarStream(request = request) {
                gate.get(10, TimeUnit.SECONDS)
                patchSignals("heads" to 1)
            }
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    class Mvc {
        @Bean
        fun feed() = Feed()
    }

    @BeforeTest
    fun close() {
        gate = CompletableFuture()
    }

    @AfterTest
    fun open() {
        gate.complete(Unit)
    }

    // This test's gate, not whichever is current in half a second.
    private fun openGateSoon() {
        val mine = gate
        thread {
            Thread.sleep(500)
            mine.complete(Unit)
        }
    }

    private fun mockMvc() = MockMvcBuilders.standaloneSetup(Feed()).build()

    private fun RestTestClient.body(path: String): String? =
        get()
            .uri(path)
            .header("Datastar-Request", "true")
            .exchange()
            .expectStatus()
            .isOk
            .returnResult(String::class.java)
            .responseBody

    private fun WebTestClient.body(path: String): String? =
        get()
            .uri(path)
            .header("Datastar-Request", "true")
            .exchange()
            .expectStatus()
            .isOk
            // Read as SSE, since the stream says text/event-stream: the data of each event, no names.
            .returnResult(String::class.java)
            .responseBody
            .collectList()
            .block()
            ?.joinToString("")

    @Test
    fun `RestTestClient bound to a controller answers 200 with no body`() {
        openGateSoon()
        assertEquals(null, RestTestClient.bindToController(Feed()).build().body("/feed"))
    }

    @Test
    fun `RestTestClient bound to MockMvc answers 200 with no body`() {
        openGateSoon()
        assertEquals(null, RestTestClient.bindTo(mockMvc()).build().body("/feed"))
    }

    // What Boot's injected client is bound to without a running server and without a MockMvc bean.
    @Test
    fun `RestTestClient bound to an application context answers 200 with no body`() {
        val context =
            org.springframework.web.context.support.AnnotationConfigWebApplicationContext().apply {
                register(Mvc::class.java)
                servletContext =
                    org.springframework.mock.web
                        .MockServletContext()
                refresh()
            }
        context.use {
            openGateSoon()
            assertEquals(null, RestTestClient.bindToApplicationContext(it).build().body("/feed"))
        }
    }

    @Test
    fun `WebTestClient bound to MockMvc reads a stream`() {
        gate.complete(Unit)
        val body = MockMvcWebTestClient.bindTo(mockMvc()).build().body("/feed")
        assertTrue("heads" in body.orEmpty(), "$body")
    }

    @Test
    fun `WebTestClient bound to MockMvc misses a suspend controller's stream`() {
        openGateSoon()
        val body = MockMvcWebTestClient.bindTo(mockMvc()).build().body("/suspended")
        assertTrue("heads" !in body.orEmpty(), "$body")
    }

    @Test
    fun `RestTestClient against a real server reads a stream that ends`() {
        gate.complete(Unit)
        RealTomcat(Mvc::class.java).use { tomcat ->
            val client = RestTestClient.bindToServer().baseUrl("http://$LOOPBACK:${tomcat.port}").build()
            for (path in listOf("/feed", "/suspended")) {
                val body = client.body(path)
                assertTrue("datastar-patch-signals" in body.orEmpty(), "$path: $body")
            }
        }
    }
}
