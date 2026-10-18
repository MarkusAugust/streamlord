package io.github.markusaugust.streamlord.spring

import io.github.markusaugust.streamlord.core.port.driving.patchSignals
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.config.annotation.EnableWebMvc
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody
import java.net.Socket
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

    // The Connection headers of a raw HTTP/1.1 request that sends [connection], read off the socket.
    private fun connectionHeaders(connection: String?): List<String> =
        Socket("localhost", tomcat.port).use { socket ->
            socket.soTimeout = 5_000
            val asked = connection?.let { "Connection: $it\r\n" } ?: ""
            socket.getOutputStream().write("GET /stream HTTP/1.1\r\nHost: localhost\r\n$asked\r\n".toByteArray())
            val head = StringBuilder()
            val input = socket.getInputStream()
            while (!head.endsWith("\r\n\r\n")) head.append(input.read().toChar())
            head.lines().filter { it.startsWith("connection:", ignoreCase = true) }.map { it.substringAfter(':').trim() }
        }

    // Tomcat answers a request that asks for keep-alive with a keep-alive of its own, which made
    // the stream's own a second one: Connection: keep-alive, keep-alive.
    @Test
    fun `a stream over HTTP 1_1 says keep-alive once, whatever the request asked`() {
        assertEquals(listOf("keep-alive"), connectionHeaders("keep-alive"))
        assertEquals(listOf("keep-alive"), connectionHeaders("Keep-Alive"))
        assertEquals(listOf("keep-alive"), connectionHeaders(null))
        assertEquals(listOf("close"), connectionHeaders("close"))
    }
}
