package io.github.markusaugust.streamlord.spring

import io.github.markusaugust.streamlord.core.application.Streamlord
import io.github.markusaugust.streamlord.core.port.driving.patchSignals
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.config.annotation.EnableWebMvc
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * A response something has already written to. Tomcat refuses a new buffer size once anything is
 * written, committed or not, and it only ever grows its buffer, so the stream cannot fail on that.
 */
class StartedResponseTest {
    @RestController
    class Streams {
        @GetMapping("/started")
        fun started(
            request: HttpServletRequest,
            response: HttpServletResponse,
        ): StreamingResponseBody {
            response.outputStream.write(": started\n\n".toByteArray())
            return response.datastarStream(Streamlord(compress = true), request) { patchSignals("n" to 1) }
        }

        @GetMapping("/written")
        fun written(response: HttpServletResponse): StreamingResponseBody {
            response.writer.write(": started\n\n")
            return response.datastarStream { patchSignals("n" to 1) }
        }
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
        tomcat = RealTomcat(Mvc::class.java)
    }

    @AfterTest
    fun stop() {
        tomcat.close()
    }

    // Not gzipped, even when the request takes it: the bytes already written would sit in front of
    // the gzip member and break it.
    @Test
    fun `a stream opens on a response that something has written to, without gzip`() {
        val response =
            java.net.http.HttpClient
                .newHttpClient()
                .send(
                    java.net.http.HttpRequest
                        .newBuilder(java.net.URI("http://localhost:${tomcat.port}/started"))
                        .header("Accept-Encoding", "gzip")
                        .build(),
                    java.net.http.HttpResponse.BodyHandlers
                        .ofString(),
                )

        assertEquals(200, response.statusCode(), response.body())
        assertEquals(null, response.headers().firstValue("content-encoding").orElse(null))
        assertEquals(": started\n\nevent: datastar-patch-signals\ndata: signals {\"n\":1}\n\n", response.body())
    }

    // The stream writes to the output stream, which the servlet API refuses once the writer is taken.
    @Test
    fun `a response written to through its writer fails`() {
        val response = tomcat.get("/written")
        val body = response.body().use { it.toList().joinToString("\n") }

        assertEquals(500, response.statusCode(), body)
        assertFalse("datastar-patch-signals" in body, body)
    }
}
