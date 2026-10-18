// Ktor deprecates ktor-server-tomcat, but ships it still, and its servlet engine is what a WAR on Tomcat 9 runs.
@file:Suppress("DEPRECATION")

package io.github.markusaugust.streamlord.ktor

import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.engine.BaseApplicationResponse
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.tomcat.Tomcat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.apache.catalina.connector.CoyoteOutputStream
import org.apache.catalina.connector.OutputBuffer
import java.net.Socket
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.servlet.AsyncEvent
import javax.servlet.AsyncListener
import javax.servlet.http.HttpServletRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.apache.catalina.startup.Tomcat as TomcatServer

/*
 * TomcatWriterTest on Ktor's javax servlet engine, ktor-server-tomcat on Tomcat 9: the
 * writer's write of the failing frame is held until Tomcat has completed the request, or for a
 * second, and nothing may reach the stream once Tomcat has completed it.
 */
class JavaxTomcatWriterTest {
    @Test
    fun `on Tomcat 9 a failing stream completes only after Ktor's writer is done`() =
        writerFinishesFirst(discardFacades = true, cancelCall = false)

    @Test
    fun `on Tomcat 9 that keeps its facades a failing stream completes only after Ktor's writer is done`() =
        writerFinishesFirst(discardFacades = false, cancelCall = false)

    @Test
    fun `on Tomcat 9 a stream whose call is cancelled completes only after Ktor's writer is done`() =
        writerFinishesFirst(discardFacades = true, cancelCall = true)

    @Test
    fun `on Tomcat 9 that keeps its facades a stream whose call is cancelled completes only after Ktor's writer is done`() =
        writerFinishesFirst(discardFacades = false, cancelCall = true)

    /** The output stream Ktor writes through, holding its write of the failing frame. */
    private class HeldStream(
        ob: OutputBuffer,
        private val inWrite: CountDownLatch,
        private val released: CountDownLatch,
        private val completed: CompletableFuture<Unit>,
        private val late: MutableList<String>,
    ) : CoyoteOutputStream(ob) {
        override fun write(
            b: ByteArray,
            off: Int,
            len: Int,
        ) {
            if ("failing" !in String(b, off, len, Charsets.ISO_8859_1)) return pass(b, off, len)
            inWrite.countDown()
            try {
                runCatching { completed.get(1, TimeUnit.SECONDS) }
                pass(b, off, len)
            } finally {
                released.countDown()
            }
        }

        private fun pass(
            b: ByteArray,
            off: Int,
            len: Int,
        ) {
            if (completed.isDone) late += "write of $len bytes"
            super.write(b, off, len)
        }
    }

    private fun field(
        o: Any,
        name: String,
    ): java.lang.reflect.Field =
        generateSequence<Class<*>>(o.javaClass) { it.superclass }
            .firstNotNullOf { c -> c.declaredFields.firstOrNull { it.name == name } }
            .also { it.isAccessible = true }

    private fun hold(
        call: ApplicationCall,
        inWrite: CountDownLatch,
        released: CountDownLatch,
        completed: CompletableFuture<Unit>,
        late: MutableList<String>,
    ) {
        val response = call.attributes[BaseApplicationResponse.EngineResponseAttributeKey]
        val facade = field(response, "servletResponse").get(response)
        val catalina = field(facade, "response").get(facade)
        val ob = field(catalina, "outputBuffer").get(catalina) as OutputBuffer
        field(catalina, "outputStream").set(catalina, HeldStream(ob, inWrite, released, completed, late))
        val request = field(response.call.request, "servletRequest").get(response.call.request) as HttpServletRequest
        request.asyncContext.addListener(
            object : AsyncListener {
                override fun onComplete(event: AsyncEvent) {
                    completed.complete(Unit)
                }

                override fun onTimeout(event: AsyncEvent) = Unit

                override fun onError(event: AsyncEvent) = Unit

                override fun onStartAsync(event: AsyncEvent) = Unit
            },
        )
    }

    private fun writerFinishesFirst(
        discardFacades: Boolean,
        cancelCall: Boolean,
    ) {
        val inWrite = CountDownLatch(1)
        val released = CountDownLatch(1)
        val completed = CompletableFuture<Unit>()
        val late = Collections.synchronizedList(mutableListOf<String>())
        val module: Application.() -> Unit = {
            routing {
                get("/after") {
                    hold(call, inWrite, released, completed, late)
                    val handler = currentCoroutineContext()[Job]!!
                    call.respondDatastar(heartbeat = null) {
                        patchElements("<i id=\"failing\"></i>")
                        withContext(Dispatchers.IO) { inWrite.await(20, TimeUnit.SECONDS) }
                        if (cancelCall) {
                            handler.cancel(null)
                            yield()
                        }
                        error("after the first frame")
                    }
                }
            }
        }
        val server = embeddedServer(Tomcat, port = 0, module = module).start(wait = false)
        try {
            val engine = server.engine.javaClass.getDeclaredField("server")
            engine.isAccessible = true
            (engine.get(server.engine) as TomcatServer).service.findConnectors().forEach { it.discardFacades = discardFacades }
            val port =
                runBlocking {
                    server.engine
                        .resolvedConnectors()
                        .first()
                        .port
                }
            val seen = read(port)
            completed.get(20, TimeUnit.SECONDS)
            assertTrue(released.await(20, TimeUnit.SECONDS), "Ktor's writer never let go of the failing frame")
            assertTrue(late.isEmpty(), "Ktor's writer went on after Tomcat completed the request: $late")
            if (!cancelCall) assertEquals(!discardFacades, seen.endsWith("\r\n0\r\n\r\n"), "discardFacades=$discardFacades:\n$seen")
        } finally {
            server.stop(100, 500)
        }
    }

    private fun read(port: Int): String =
        Socket("localhost", port).use { socket ->
            socket.soTimeout = 20_000
            socket.getOutputStream().write("GET /after HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
            val seen = StringBuilder()
            val buffer = ByteArray(8192)
            try {
                while (!seen.endsWith("\r\n0\r\n\r\n")) {
                    val n = socket.getInputStream().read(buffer)
                    if (n < 0) break
                    seen.append(String(buffer, 0, n, Charsets.ISO_8859_1))
                }
            } catch (_: java.io.IOException) {
            }
            seen.toString()
        }
}
