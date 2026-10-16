package io.github.markusaugust.streamlord.spring

import io.github.markusaugust.streamlord.core.port.driving.patchSignals
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.config.annotation.EnableWebMvc
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * HTTP/2 forbids connection-specific headers, and curl and Node refuse a response that carries
 * one. Tomcat drops them from requests only, so what the adapter sets reaches the client.
 */
class Http2Test {
    @RestController
    class Streams {
        // Without the request, as the first example on the Spring WebMVC page has it.
        @GetMapping("/stream")
        fun stream(response: HttpServletResponse): StreamingResponseBody = response.datastarStream { patchSignals("n" to 1) }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    class Mvc {
        @Bean
        fun streams() = Streams()
    }

    private lateinit var tomcat: RealTomcat

    @BeforeTest
    fun start() {
        tomcat = RealTomcat(Mvc::class.java, http2 = true)
    }

    @AfterTest
    fun stop() {
        tomcat.close()
    }

    private fun get(version: HttpClient.Version): HttpResponse<String> {
        val client = HttpClient.newBuilder().version(version).build()
        val request = HttpRequest.newBuilder(URI("http://localhost:${tomcat.port}/stream")).build()
        // The first request on a connection is HTTP/1.1 with an Upgrade; the second one is HTTP/2.
        if (version == HttpClient.Version.HTTP_2) client.send(request, HttpResponse.BodyHandlers.discarding())
        return client.send(request, HttpResponse.BodyHandlers.ofString())
    }

    @Test
    fun `a stream over HTTP 2 carries no Connection header`() {
        val response = get(HttpClient.Version.HTTP_2)

        assertEquals(HttpClient.Version.HTTP_2, response.version())
        assertEquals(200, response.statusCode(), response.body())
        assertTrue(response.headers().allValues("connection").isEmpty(), "${response.headers().map()}")
        assertTrue("datastar-patch-signals" in response.body(), response.body())
    }

    @Test
    fun `a stream over HTTP 1_1 keeps Connection keep-alive`() {
        val response = get(HttpClient.Version.HTTP_1_1)

        assertEquals(listOf("keep-alive"), response.headers().allValues("connection"))
    }
}
