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
                        call.respondDatastar(heartbeat = null) {
                            patchSignals("ok" to 1)
                            delay(5)
                            patchSignals("ok" to 2)
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
            val problems =
                (0 until 400)
                    .map { i ->
                        pool.submit(
                            Callable {
                                if (i % 2 == 0) {
                                    val r = gets(port, "/after", 1)
                                    when {
                                        "\"ok\"" in r -> "a failing stream got an ok frame:\n$r"
                                        discardFacades && r.endsWith("0\r\n\r\n") -> "a failing stream ended as if it had finished"
                                        else -> null
                                    }
                                } else {
                                    val r = gets(port, "/ok", 5)
                                    val frames = Regex("datastar-patch-signals").findAll(r).count()
                                    when {
                                        "failing" in r -> "a kept-alive stream got a failing frame:\n$r"
                                        frames != 10 -> "a kept-alive connection had $frames frames, not 10:\n$r"
                                        !r.endsWith("\r\n0\r\n\r\n") -> "a kept-alive stream was cut:\n$r"
                                        else -> null
                                    }
                                }
                            },
                        )
                    }.mapNotNull { it.get(30, TimeUnit.SECONDS) }
            pool.shutdown()
            assertTrue(problems.isEmpty(), "${problems.size} problems, the first: ${problems.firstOrNull()}")
        } finally {
            server.stop(100, 500)
        }
    }

    /** [count] GETs on one kept-alive connection, each read to its last chunk or until the connection ends. */
    private fun gets(
        port: Int,
        path: String,
        count: Int,
    ): String =
        Socket("localhost", port).use { socket ->
            socket.soTimeout = 3000
            val all = StringBuilder()
            repeat(count) {
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
                } catch (_: java.io.IOException) {
                }
                all.append(out.toString(Charsets.ISO_8859_1))
            }
            all.toString()
        }
}
