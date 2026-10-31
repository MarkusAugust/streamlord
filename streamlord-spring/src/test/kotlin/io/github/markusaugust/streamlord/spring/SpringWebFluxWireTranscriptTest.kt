package io.github.markusaugust.streamlord.spring

import io.github.markusaugust.streamlord.core.SignalsTooLargeException
import io.github.markusaugust.streamlord.core.application.Streamlord
import io.github.markusaugust.streamlord.core.port.driven.IncomingRequest
import io.github.markusaugust.streamlord.core.port.driven.rejectDeclaredLengthAbove
import io.github.markusaugust.streamlord.core.protocol.DatastarAttributes
import io.github.markusaugust.streamlord.core.protocol.DatastarProtocol
import io.github.markusaugust.streamlord.wire.WireCases
import io.github.markusaugust.streamlord.wire.WireTranscript
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.reactive.asFlow
import org.apache.catalina.startup.Tomcat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.TestInstance
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.codec.ServerSentEvent
import org.springframework.http.server.reactive.TomcatHttpHandlerAdapter
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.reactive.config.EnableWebFlux
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.adapter.WebHttpHandlerBuilder
import java.io.ByteArrayOutputStream
import java.nio.file.Files

/**
 * The approved wire transcripts of the WebFlux adapter on an embedded Tomcat, reached through
 * Spring's reactive servlet adapter: every case of [WireCases] is sent to it on a socket and the
 * answer compared with `conformance/wire/spring-webflux-tomcat/<case>.txt`.
 *
 * WebFlux has no stream of its own here: a flow of events is mapped to Spring's `ServerSentEvent`,
 * which Spring writes. So the comment is Spring's own, the signals are read through the core's port
 * over the exchange, as the WebFlux page leaves to the reader, and `compress` has nothing to act on.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SpringWebFluxWireTranscriptTest {
    companion object {
        val streamlord = Streamlord(heartbeat = null, maxSignalsSize = WireCases.MAX_SIGNALS_SIZE)
        val compressing = Streamlord(heartbeat = null, maxSignalsSize = WireCases.MAX_SIGNALS_SIZE, compress = true)
    }

    /** The handlers behind the cases, the same on every adapter. */
    @RestController
    class Handlers {
        @GetMapping("/stream/elements")
        fun elements(): ResponseEntity<Flow<ServerSentEvent<String>>> = WireCases.elements.asFlow().asDatastarResponse(streamlord)

        @GetMapping("/stream/signals")
        fun signals(): ResponseEntity<Flow<ServerSentEvent<String>>> = WireCases.signals.asFlow().asDatastarResponse(streamlord)

        @GetMapping("/stream/scripts")
        fun scripts(): ResponseEntity<Flow<ServerSentEvent<String>>> = WireCases.scripts.asFlow().asDatastarResponse(streamlord)

        // A comment is no event, so the stream is put together from Spring's own type.
        @GetMapping("/stream/sse-fields")
        fun sseFields(): ResponseEntity<Flow<ServerSentEvent<String>>> {
            val builder = ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM)
            for ((name, value) in DatastarProtocol.SSE_RESPONSE_HEADERS) builder.header(name, value)
            return builder.body(
                flowOf(
                    WireCases.withEventId.toServerSentEvent(streamlord),
                    ServerSentEvent.builder<String>().comment(WireCases.COMMENT).build(),
                    WireCases.withRetry.toServerSentEvent(streamlord),
                ),
            )
        }

        // The prefix is switched inside the flow: the event is encoded as it is emitted, when the response is written.
        @GetMapping("/stream/script-aliased")
        fun scriptAliased(): ResponseEntity<Flow<ServerSentEvent<String>>> =
            flow {
                val prefix = DatastarAttributes.prefix
                DatastarAttributes.prefix = WireCases.ALIASED_PREFIX
                try {
                    emit(WireCases.aliasedScript)
                } finally {
                    DatastarAttributes.prefix = prefix
                }
            }.asDatastarResponse(streamlord)

        @GetMapping("/gzip/stream")
        fun gzip(): ResponseEntity<Flow<ServerSentEvent<String>>> = WireCases.signals.asFlow().asDatastarResponse(compressing)

        @GetMapping("/response/elements")
        fun elementsResponse(): ResponseEntity<String> = WireCases.elementsResponse.toResponseEntity(streamlord)

        @GetMapping("/response/signals")
        fun signalsResponse(): ResponseEntity<String> = WireCases.signalsResponse.toResponseEntity(streamlord)

        @GetMapping("/response/script")
        fun scriptResponse(): ResponseEntity<String> = WireCases.scriptResponse.toResponseEntity(streamlord)

        @GetMapping("/response/no-content")
        fun noContent(): ResponseEntity<Void> = ResponseEntity.noContent().build()

        @RequestMapping("/signals")
        suspend fun readSignals(exchange: ServerWebExchange): ResponseEntity<String> =
            datastarSignals(WireCases.echo(exchange.request.method.name(), streamlord.readSignalsJson(ExchangeIncomingRequest(exchange))))

        @GetMapping("/csp/page", produces = ["text/html;charset=UTF-8"])
        fun page(exchange: ServerWebExchange): String = WireCases.page(exchange.cspNonce)
    }

    /** The core's port over a WebFlux exchange, with the bounded body read the port asks for. */
    private class ExchangeIncomingRequest(
        private val exchange: ServerWebExchange,
    ) : IncomingRequest {
        override val method: String get() = exchange.request.method.name()

        override fun queryParameter(name: String): String? = exchange.request.queryParams.getFirst(name)

        override fun header(name: String): String? = exchange.request.headers.getFirst(name)

        override suspend fun bodyText(maxBytes: Int): String {
            val declared = exchange.request.headers.contentLength
            rejectDeclaredLengthAbove(maxBytes, declared.takeIf { it >= 0 })
            val bytes = ByteArrayOutputStream()
            exchange.request.body.asFlow().collect { buffer ->
                try {
                    val chunk = ByteArray(minOf(maxBytes + 1 - bytes.size(), buffer.readableByteCount()))
                    buffer.read(chunk)
                    bytes.write(chunk)
                } finally {
                    DataBufferUtils.release(buffer)
                }
                if (bytes.size() > maxBytes) throw SignalsTooLargeException(bytes.size().toLong(), maxBytes)
            }
            return bytes.toString(Charsets.UTF_8)
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebFlux
    class Flux {
        @Bean
        fun handlers() = Handlers()

        @Bean
        fun errors() = StreamlordExceptionHandler()

        /** The WebFilter the Security page leaves to the reader, on the page's route alone. */
        @Bean
        fun cspNonce() =
            WebFilter { exchange, chain ->
                val path = exchange.request.path.value()
                if (path.startsWith("/csp/")) exchange.installCspNonce()
                chain.filter(exchange)
            }
    }

    private val baseDir = Files.createTempDirectory("tomcat")
    private val tomcat = Tomcat()
    private var port = 0

    @BeforeAll
    fun start() {
        tomcat.setBaseDir(baseDir.toString())
        tomcat.setPort(0)
        val context = tomcat.addContext("", null)
        val handler = WebHttpHandlerBuilder.applicationContext(AnnotationConfigApplicationContext(Flux::class.java)).build()
        Tomcat.addServlet(context, "reactive", TomcatHttpHandlerAdapter(handler)).apply {
            isAsyncSupported = true
            setLoadOnStartup(1)
        }
        context.addServletMapping("/", "reactive")
        tomcat.start()
        port = tomcat.connector.localPort
    }

    @AfterAll
    fun stop() {
        tomcat.stop()
        tomcat.destroy()
        // As RealTomcat: Tomcat records its base directory JVM-wide, for the next instance to make again.
        System.clearProperty("catalina.base")
        System.clearProperty("catalina.home")
        baseDir.toFile().deleteRecursively()
    }

    @TestFactory
    fun transcripts(): List<DynamicTest> =
        WireCases.all.map { case ->
            DynamicTest.dynamicTest(case.name) {
                WireTranscript.verify("spring-webflux-tomcat", case, port, "streamlord-spring", "SpringWebFluxWireTranscriptTest")
            }
        }
}
