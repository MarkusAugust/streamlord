package io.github.markusaugust.streamlord.spring

import io.github.markusaugust.streamlord.core.application.Streamlord
import io.github.markusaugust.streamlord.core.port.driving.patchSignals
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import kotlinx.coroutines.delay
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

// MockMvc never fires a timeout; these check the value the async request is left with.
class StreamTimeoutTest {
    @RestController
    class Streams {
        @GetMapping("/default")
        fun default(request: HttpServletRequest, response: HttpServletResponse): StreamingResponseBody =
            response.datastarStream(request = request) { patchSignals("n" to 1) }

        @GetMapping("/infinite")
        fun infinite(request: HttpServletRequest, response: HttpServletResponse): StreamingResponseBody =
            response.datastarStream(request = request, timeout = Duration.INFINITE) { patchSignals("n" to 1) }

        @GetMapping("/five")
        fun five(request: HttpServletRequest, response: HttpServletResponse): StreamingResponseBody =
            response.datastarStream(request = request, timeout = 5.seconds) { patchSignals("n" to 1) }

        // Without the request: the adapter finds it through Spring's RequestContextHolder.
        @GetMapping("/held")
        fun held(response: HttpServletResponse): StreamingResponseBody =
            response.datastarStream(timeout = 5.seconds) { patchSignals("n" to 1) }

        @GetMapping("/tiny")
        fun tiny(request: HttpServletRequest, response: HttpServletResponse): StreamingResponseBody =
            response.datastarStream(request = request, timeout = 500.microseconds) { patchSignals("n" to 1) }

        @GetMapping("/flow")
        fun flow(request: HttpServletRequest, response: HttpServletResponse): StreamingResponseBody =
            response.datastarStream(kotlinx.coroutines.flow.flowOf(), Streamlord(), request, timeout = 5.seconds)
    }

    private val mvc: MockMvc = MockMvcBuilders.standaloneSetup(Streams()).setAsyncRequestTimeout(200).build()

    private fun timeoutOf(path: String): Long = mvc.perform(get(path)).andReturn().request.asyncContext!!.timeout

    @Test
    fun `without a timeout the stream keeps Spring's`() = assertEquals(200, timeoutOf("/default"))

    @Test
    fun `an infinite timeout lifts the limit`() = assertEquals(-1, timeoutOf("/infinite"))

    @Test
    fun `a timeout is set in milliseconds`() = assertEquals(5000, timeoutOf("/five"))

    @Test
    fun `without the request the timeout still reaches the stream`() = assertEquals(5000, timeoutOf("/held"))

    // Zero means no limit to a servlet container, so a timeout under a millisecond rounds up.
    @Test
    fun `a timeout under a millisecond is one millisecond`() = assertEquals(1, timeoutOf("/tiny"))

    @Test
    fun `the flow variant takes a timeout`() = assertEquals(5000, timeoutOf("/flow"))

    @Test
    fun `a timeout that is not positive fails when the stream is asked for`() {
        for (timeout in listOf(Duration.ZERO, (-1).seconds)) {
            assertFailsWith<IllegalArgumentException> {
                MockHttpServletResponse().datastarStream(timeout = timeout) {}
            }
        }
    }

    // Outside a Spring MVC request there is no async request to set it on, and ignoring it would
    // leave the stream on the container's limit with nothing said.
    @Test
    fun `a timeout outside Spring MVC fails rather than being ignored`() {
        assertFailsWith<IllegalStateException> {
            MockHttpServletResponse().datastarStream(request = MockHttpServletRequest(), timeout = 5.seconds) {}
        }
    }

    @Test
    fun `without a timeout a stream outside Spring MVC is fine`() {
        val response = MockHttpServletResponse()
        response.datastarStream(request = MockHttpServletRequest()) { delay(1.milliseconds) }.writeTo(response.outputStream)
        assertEquals("", response.contentAsString)
    }
}
