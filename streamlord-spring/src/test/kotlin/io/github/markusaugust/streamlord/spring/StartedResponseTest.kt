package io.github.markusaugust.streamlord.spring

import io.github.markusaugust.streamlord.core.port.driving.patchSignals
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
import kotlin.test.assertTrue

/**
 * A response something has already written to. Tomcat refuses a new buffer size once anything is
 * written, committed or not, and it only ever grows its buffer, so the stream cannot fail on that.
 */
class StartedResponseTest {
    @RestController
    class Streams {
        @GetMapping("/started")
        fun started(response: HttpServletResponse): StreamingResponseBody {
            response.outputStream.write(": started\n\n".toByteArray())
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

    @Test
    fun `a stream opens on a response that something has written to`() {
        val response = tomcat.get("/started")
        val body = response.body().use { lines -> lines.toList().joinToString("\n") }

        assertEquals(200, response.statusCode(), body)
        assertTrue("event: datastar-patch-signals" in body, body)
    }
}
