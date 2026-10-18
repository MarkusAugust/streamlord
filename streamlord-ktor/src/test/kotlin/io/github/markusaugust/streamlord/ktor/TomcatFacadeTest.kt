package io.github.markusaugust.streamlord.ktor

import io.github.markusaugust.streamlord.core.port.driving.patchSignals
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.tomcat.jakarta.Tomcat
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertTrue
import org.apache.catalina.startup.Tomcat as TomcatServer

/*
 * Tomcat hands a request's facade on to the next request unless it discards them, which it does by
 * default. A cut raised while Ktor still held the request reached other requests through a kept
 * facade: streams cut, a failing stream's frame on another connection. Failing streams on fresh
 * connections run here against kept-alive ones that must stay whole.
 */
class TomcatFacadeTest {
    @Test
    fun `a cut on Tomcat leaves every other request whole`() = crossTalk(discardFacades = true)

    // Streamlord does not cut there, and the failing streams end as Tomcat ends them.
    @Test
    fun `on Tomcat that keeps its facades nothing is cut and other requests stay whole`() = crossTalk(discardFacades = false)

    private fun crossTalk(discardFacades: Boolean) {
        val server =
            embeddedServer(Tomcat, port = 0) {
                routing {
                    get("/after") {
                        call.respondDatastar(heartbeat = null) {
                            patchSignals("failing" to 1)
                            error("after the first frame")
                        }
                    }
                    get("/ok") {
                        val id = call.request.queryParameters["id"]
                        call.respondDatastar(heartbeat = null) {
                            patchSignals("ok" to id, "step" to 1)
                            delay(5)
                            patchSignals("ok" to id, "step" to 2)
                        }
                    }
                }
            }.start(wait = false)
        try {
            // Ktor sets up its connectors after configureTomcat runs, so the setting goes on afterwards.
            val field = server.engine.javaClass.getDeclaredField("server")
            field.isAccessible = true
            (field.get(server.engine) as TomcatServer).service.findConnectors().forEach { it.discardFacades = discardFacades }
            val port =
                runBlocking {
                    server.engine
                        .resolvedConnectors()
                        .first()
                        .port
                }
            val pool = Executors.newFixedThreadPool(32)
            val found =
                (0 until 400)
                    .map { i ->
                        pool.submit(
                            Callable {
                                if (i % 2 == 0) {
                                    val r = gets(port, listOf("/after")).single()
                                    when {
                                        "\"ok\"" in r -> "a failing stream got an ok frame:\n$r"
                                        discardFacades && r.endsWith("\r\n0\r\n\r\n") -> "a failing stream ended as if whole:\n$r"
                                        else -> null
                                    }
                                } else {
                                    // Each request its own id, so a frame of any other stream shows.
                                    val ids = (0 until 5).map { "$i-$it" }
                                    gets(port, ids.map { "/ok?id=$it" }).zip(ids).firstNotNullOfOrNull { (r, id) ->
                                        val frames = Regex("\"ok\":\"([^\"]*)\"").findAll(r).map { it.groupValues[1] }.toList()
                                        when {
                                            "failing" in r -> "a kept-alive stream got a failing frame:\n$r"
                                            frames.any { it != id } -> "the stream of $id got another's frame:\n$r"
                                            frames.size != 2 -> "the stream of $id had ${frames.size} frames, not 2:\n$r"
                                            !r.endsWith("\r\n0\r\n\r\n") -> "the stream of $id was cut:\n$r"
                                            else -> null
                                        }
                                    }
                                }
                            },
                        )
                    }.mapNotNull { it.get(120, TimeUnit.SECONDS) }
            pool.shutdown()
            assertTrue(found.isEmpty(), "${found.size} of 400 connections went wrong, the first: ${found.firstOrNull()}")
        } finally {
            server.stop(100, 500)
        }
    }

    /**
     * GETs of [paths] in turn on one kept-alive connection, each read to its last chunk or until the
     * connection ends. One that does neither in twenty seconds says so: the limit stands for
     * "never", so a slow machine does not pass for a stream cut short.
     */
    private fun gets(
        port: Int,
        paths: List<String>,
    ): List<String> =
        Socket("localhost", port).use { socket ->
            socket.soTimeout = 20_000
            paths.map { path ->
                socket.getOutputStream().write("GET $path HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                try {
                    while (true) {
                        val n = socket.getInputStream().read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        if (out.toString(Charsets.ISO_8859_1).endsWith("\r\n0\r\n\r\n")) break
                    }
                } catch (_: SocketTimeoutException) {
                    out.write("[silent for 20 s]".toByteArray())
                } catch (_: java.io.IOException) {
                }
                out.toString(Charsets.ISO_8859_1)
            }
        }
}
