package io.github.markusaugust.streamlord.ktor

import io.github.markusaugust.streamlord.core.JsonParseException
import io.github.markusaugust.streamlord.core.SignalsDecodeException
import io.github.markusaugust.streamlord.core.SignalsMediaTypeException
import io.github.markusaugust.streamlord.core.SignalsTooLargeException
import io.github.markusaugust.streamlord.core.protocol.DatastarAttributes
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.ApplicationEngineFactory
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.httpMethod
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.TestInstance

/**
 * The approved wire transcripts of the Ktor adapter, one engine per subclass: every case of
 * [WireCases] is sent to a real server on a socket and the answer compared with
 * `conformance/wire/<adapter>/<case>.txt`. The gzip case goes to a second server whose plugin has
 * `compress = true`, since the plugin configures the whole application.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class KtorWireTranscriptTest<E : ApplicationEngine, C : ApplicationEngine.Configuration>(
    private val adapter: String,
    private val engine: ApplicationEngineFactory<E, C>,
) {
    private lateinit var plain: EmbeddedServer<E, C>
    private lateinit var compressing: EmbeddedServer<E, C>

    @BeforeAll
    fun start() {
        plain = embeddedServer(engine, port = 0, host = "127.0.0.1") { transcriptApplication(compress = false) }.start()
        compressing = embeddedServer(engine, port = 0, host = "127.0.0.1") { transcriptApplication(compress = true) }.start()
    }

    @AfterAll
    fun stop() {
        plain.stop(100, 500)
        compressing.stop(100, 500)
    }

    @TestFactory
    fun transcripts(): List<DynamicTest> =
        WireCases.all.map { case ->
            DynamicTest.dynamicTest(case.name) {
                val port = portOf(if (case.compressed) compressing else plain)
                WireTranscript.verify(adapter, case, port, "streamlord-ktor", "KtorWireTranscriptTest")
            }
        }

    private fun portOf(server: EmbeddedServer<E, C>): Int =
        runBlocking {
            server.engine
                .resolvedConnectors()
                .first()
                .port
        }
}

/** The handlers behind the cases, the same on every adapter; the error block is the Security page's. */
private fun Application.transcriptApplication(compress: Boolean) {
    install(StreamlordPlugin) {
        heartbeat = null
        maxSignalsSize = WireCases.MAX_SIGNALS_SIZE
        this.compress = compress
    }
    install(StatusPages) {
        exception<SignalsTooLargeException> { call, _ -> call.respond(HttpStatusCode.PayloadTooLarge) }
        exception<JsonParseException> { call, _ -> call.respond(HttpStatusCode.BadRequest) }
        exception<SignalsDecodeException> { call, _ -> call.respond(HttpStatusCode.BadRequest) }
        exception<SignalsMediaTypeException> { call, _ -> call.respond(HttpStatusCode.UnsupportedMediaType) }
        exception<Throwable> { call, _ -> call.respond(HttpStatusCode.InternalServerError) }
    }
    routing {
        get("/stream/elements") { call.respondDatastar { sendAll(WireCases.elements.asFlow()) } }
        get("/stream/signals") { call.respondDatastar { sendAll(WireCases.signals.asFlow()) } }
        get("/stream/scripts") { call.respondDatastar { sendAll(WireCases.scripts.asFlow()) } }
        get("/stream/sse-fields") {
            call.respondDatastar {
                send(WireCases.withEventId)
                comment(WireCases.COMMENT)
                send(WireCases.withRetry)
            }
        }
        get("/stream/script-aliased") {
            call.respondDatastar {
                val prefix = DatastarAttributes.prefix
                DatastarAttributes.prefix = WireCases.ALIASED_PREFIX
                try {
                    send(WireCases.aliasedScript)
                } finally {
                    DatastarAttributes.prefix = prefix
                }
            }
        }
        get("/response/elements") { call.respondDatastar(WireCases.elementsResponse) }
        get("/response/signals") { call.respondDatastar(WireCases.signalsResponse) }
        get("/response/script") { call.respondDatastar(WireCases.scriptResponse) }
        get("/response/no-content") { call.respond(HttpStatusCode.NoContent) }
        route("/signals") {
            handle { call.respondSignals(WireCases.echo(call.request.httpMethod.value, call.readSignalsJson())) }
        }
        route("/csp") {
            install(CspNoncePlugin)
            get("/page") { call.respondText(WireCases.page(call.cspNonce), ContentType.Text.Html) }
        }
        if (compress) get("/gzip/stream") { call.respondDatastar { sendAll(WireCases.signals.asFlow()) } }
    }
}

class KtorCioWireTranscriptTest : KtorWireTranscriptTest<CIOApplicationEngine, CIOApplicationEngine.Configuration>("ktor-cio", CIO)

class KtorNettyWireTranscriptTest :
    KtorWireTranscriptTest<NettyApplicationEngine, NettyApplicationEngine.Configuration>("ktor-netty", Netty)
