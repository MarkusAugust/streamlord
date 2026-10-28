package io.github.markusaugust.streamlord.spring

import io.github.markusaugust.streamlord.core.application.Streamlord
import io.github.markusaugust.streamlord.core.protocol.DatastarAttributes
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import kotlinx.coroutines.flow.asFlow
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.TestInstance
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.config.annotation.EnableWebMvc
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody

/**
 * The approved wire transcripts of the WebMVC adapter on an embedded Tomcat: every case of
 * [WireCases] is sent to it on a socket and the answer compared with
 * `conformance/wire/spring-mvc-tomcat/<case>.txt`.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SpringMvcWireTranscriptTest {
    companion object {
        val streamlord = Streamlord(heartbeat = null, maxSignalsSize = WireCases.MAX_SIGNALS_SIZE)
        val compressing = Streamlord(heartbeat = null, maxSignalsSize = WireCases.MAX_SIGNALS_SIZE, compress = true)
    }

    /** The handlers behind the cases, the same on every adapter. */
    @RestController
    class Handlers {
        @GetMapping("/stream/elements")
        fun elements(
            request: HttpServletRequest,
            response: HttpServletResponse,
        ): StreamingResponseBody = response.datastarStream(streamlord, request) { sendAll(WireCases.elements.asFlow()) }

        @GetMapping("/stream/signals")
        fun signals(
            request: HttpServletRequest,
            response: HttpServletResponse,
        ): StreamingResponseBody = response.datastarStream(streamlord, request) { sendAll(WireCases.signals.asFlow()) }

        @GetMapping("/stream/scripts")
        fun scripts(
            request: HttpServletRequest,
            response: HttpServletResponse,
        ): StreamingResponseBody = response.datastarStream(streamlord, request) { sendAll(WireCases.scripts.asFlow()) }

        @GetMapping("/stream/sse-fields")
        fun sseFields(
            request: HttpServletRequest,
            response: HttpServletResponse,
        ): StreamingResponseBody =
            response.datastarStream(streamlord, request) {
                send(WireCases.withEventId)
                comment(WireCases.COMMENT)
                send(WireCases.withRetry)
            }

        // The prefix is switched inside the block: the body runs on another thread, after this returns.
        @GetMapping("/stream/script-aliased")
        fun scriptAliased(
            request: HttpServletRequest,
            response: HttpServletResponse,
        ): StreamingResponseBody =
            response.datastarStream(streamlord, request) {
                val prefix = DatastarAttributes.prefix
                DatastarAttributes.prefix = WireCases.ALIASED_PREFIX
                try {
                    send(WireCases.aliasedScript)
                } finally {
                    DatastarAttributes.prefix = prefix
                }
            }

        @GetMapping("/gzip/stream")
        fun gzip(
            request: HttpServletRequest,
            response: HttpServletResponse,
        ): StreamingResponseBody = response.datastarStream(compressing, request) { sendAll(WireCases.signals.asFlow()) }

        @GetMapping("/response/elements")
        fun elementsResponse(): ResponseEntity<String> = WireCases.elementsResponse.toResponseEntity(streamlord)

        @GetMapping("/response/signals")
        fun signalsResponse(): ResponseEntity<String> = WireCases.signalsResponse.toResponseEntity(streamlord)

        @GetMapping("/response/script")
        fun scriptResponse(): ResponseEntity<String> = WireCases.scriptResponse.toResponseEntity(streamlord)

        @GetMapping("/response/no-content")
        fun noContent(): ResponseEntity<Void> = ResponseEntity.noContent().build()

        @RequestMapping("/signals")
        fun readSignals(request: HttpServletRequest): ResponseEntity<String> =
            datastarSignals(WireCases.echo(request.method, request.readSignalsJson(streamlord)))

        @GetMapping("/csp/page", produces = ["text/html;charset=UTF-8"])
        fun page(request: HttpServletRequest): String = WireCases.page(request.cspNonce)
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    class Mvc {
        @Bean
        fun handlers() = Handlers()

        @Bean
        fun errors() = StreamlordExceptionHandler()
    }

    private lateinit var tomcat: RealTomcat

    @BeforeAll
    fun start() {
        tomcat = RealTomcat(Mvc::class.java, filters = listOf(CspNonceFilter()), filterPattern = "/csp/*")
    }

    @AfterAll
    fun stop() {
        tomcat.close()
    }

    @TestFactory
    fun transcripts(): List<DynamicTest> =
        WireCases.all.map { case ->
            DynamicTest.dynamicTest(case.name) {
                WireTranscript.verify("spring-mvc-tomcat", case, tomcat.port, "streamlord-spring", "SpringMvcWireTranscriptTest")
            }
        }
}
