package io.github.markusaugust.streamlord.ktor

import io.github.markusaugust.streamlord.core.port.driving.patchSignals
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.engine.BaseApplicationResponse
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.ApplicationSendPipeline
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.tomcat.jakarta.Tomcat
import io.ktor.util.pipeline.PipelinePhase
import jakarta.servlet.AsyncEvent
import jakarta.servlet.AsyncListener
import jakarta.servlet.http.HttpServletRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import org.apache.catalina.connector.CoyoteOutputStream
import org.apache.catalina.connector.OutputBuffer
import java.net.Socket
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import org.apache.catalina.startup.Tomcat as TomcatServer

/*
 * Ktor's servlet engine writes a response on a coroutine of its own. A failing stream must let that
 * writer finish before the request completes. Here the writer's write of the failing frame is held
 * until Tomcat has completed the request, or for a second, and nothing may reach the stream once
 * Tomcat has completed it.
 */
class TomcatWriterTest {
    @Test
    fun `on Tomcat a failing stream completes only after Ktor's writer is done`() =
        writerFinishesFirst(discardFacades = true, Failure.THROW)

    @Test
    fun `on Tomcat that keeps its facades a failing stream completes only after Ktor's writer is done`() =
        writerFinishesFirst(discardFacades = false, Failure.THROW)

    // A CancellationException from the block, here a timeout inside it, while the call goes on.
    @Test
    fun `on Tomcat a stream cancelled from within completes only after Ktor's writer is done`() =
        writerFinishesFirst(discardFacades = true, Failure.TIMEOUT)

    @Test
    fun `on Tomcat that keeps its facades a stream cancelled from within completes only after Ktor's writer is done`() =
        writerFinishesFirst(discardFacades = false, Failure.TIMEOUT)

    // The call itself cancelled: Ktor no longer waits for its writer, so respondDatastar does.
    @Test
    fun `on Tomcat a stream whose call is cancelled completes only after Ktor's writer is done`() =
        writerFinishesFirst(discardFacades = true, Failure.CANCEL_CALL)

    @Test
    fun `on Tomcat that keeps its facades a stream whose call is cancelled completes only after Ktor's writer is done`() =
        writerFinishesFirst(discardFacades = false, Failure.CANCEL_CALL)

    private enum class Failure { THROW, TIMEOUT, CANCEL_CALL }

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
                // The old code completed the request well within this, a fixed one does not.
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

        override fun flush() {
            if (completed.isDone) late += "flush"
            super.flush()
        }
    }

    private fun field(
        o: Any,
        name: String,
    ): java.lang.reflect.Field =
        generateSequence<Class<*>>(o.javaClass) { it.superclass }
            .firstNotNullOf { c -> c.declaredFields.firstOrNull { it.name == name } }
            .also { it.isAccessible = true }

    /** Holds the stream Ktor will write [call]'s body through, and notes when Tomcat completes it. */
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
        failure: Failure,
    ) {
        val inWrite = CountDownLatch(1)
        val released = CountDownLatch(1)
        val completed = CompletableFuture<Unit>()
        val late = Collections.synchronizedList(mutableListOf<String>())
        val failed = CompletableFuture<Throwable>()
        val thrown = CompletableFuture<Throwable?>()
        val module: Application.() -> Unit = {
            routing {
                get("/after") {
                    hold(call, inWrite, released, completed, late)
                    val handler = currentCoroutineContext()[Job]!!
                    val result =
                        runCatching {
                            call.respondDatastar(heartbeat = null) {
                                patchSignals("failing" to 1)
                                // Fails while Ktor's writer is inside its write of the frame.
                                withContext(Dispatchers.IO) { inWrite.await(20, TimeUnit.SECONDS) }
                                try {
                                    when (failure) {
                                        Failure.THROW -> {}

                                        Failure.TIMEOUT -> {
                                            withTimeout(1) { delay(1_000) }
                                        }

                                        Failure.CANCEL_CALL -> {
                                            handler.cancel(null)
                                            yield()
                                        }
                                    }
                                    error("after the first frame")
                                } catch (e: Throwable) {
                                    failed.complete(e)
                                    throw e
                                }
                            }
                        }
                    thrown.complete(result.exceptionOrNull())
                    result.getOrThrow()
                }
            }
        }
        tomcat(discardFacades, module) { port ->
            val seen = read(port, "/after")
            completed.get(20, TimeUnit.SECONDS)
            val cause = failed.get(20, TimeUnit.SECONDS)
            // A write let go after the request completed is noted only then.
            assertTrue(released.await(20, TimeUnit.SECONDS), "Ktor's writer never let go of the failing frame")
            assertTrue(late.isEmpty(), "Ktor's writer went on after Tomcat completed the request: $late")
            val got = thrown.get(20, TimeUnit.SECONDS)
            assertTrue(got.isOrRecovers(cause), "respondDatastar threw $got, not $cause")
            if (failure != Failure.THROW) assertTrue(cause is CancellationException, "$cause")
            // Cut where Streamlord cuts; where it cannot, ended as if whole, not cut short.
            assertEquals(!discardFacades, seen.endsWith("\r\n0\r\n\r\n"), "discardFacades=$discardFacades:\n$seen")
        }
    }

    // A failure after the block's, here from Ktor's send pipeline, is added to it, not thrown in its place.
    @Test
    fun `on Tomcat what fails after the block does not hide the block's failure`() {
        val failed = CompletableFuture<Throwable>()
        val thrown = CompletableFuture<Throwable?>()
        val module: Application.() -> Unit = {
            val last = PipelinePhase("after the engine")
            sendPipeline.insertPhaseAfter(ApplicationSendPipeline.Engine, last)
            sendPipeline.intercept(last) { error("from the send pipeline") }
            routing {
                get("/after") {
                    val result =
                        runCatching {
                            call.respondDatastar(heartbeat = null) {
                                patchSignals("failing" to 1)
                                val failure = IllegalStateException("after the first frame")
                                failed.complete(failure)
                                throw failure
                            }
                        }
                    thrown.complete(result.exceptionOrNull())
                    result.getOrThrow()
                }
            }
        }
        tomcat(discardFacades = true, module) { port ->
            read(port, "/after")
            val failure = failed.get(20, TimeUnit.SECONDS)
            val got = thrown.get(20, TimeUnit.SECONDS)
            assertTrue(got.isOrRecovers(failure), "respondDatastar threw $got, not $failure")
            assertEquals(listOf("from the send pipeline"), failure.suppressed.map { it.message }, failure.stackTraceToString())
        }
    }

    // A reader who stopped reading would hold Ktor's writer, and with it the failure, until Tomcat
    // gave up writing, some forty seconds and more. The frames get a second, as on any engine.
    @Test
    fun `on Tomcat a failing stream to a reader who stopped reading throws within seconds`() {
        val started = CompletableFuture<TimeSource.Monotonic.ValueTimeMark>()
        val thrown = CompletableFuture<TimeSource.Monotonic.ValueTimeMark>()
        val module: Application.() -> Unit = {
            routing {
                get("/stall") {
                    try {
                        call.respondDatastar(heartbeat = null) {
                            started.complete(TimeSource.Monotonic.markNow())
                            withTimeoutOrNull(2.seconds) { repeat(4_000) { patchElements("<i>" + "x".repeat(16_000) + "</i>") } }
                            error("to a reader who stopped reading")
                        }
                    } finally {
                        thrown.complete(TimeSource.Monotonic.markNow())
                    }
                }
            }
        }
        tomcat(discardFacades = true, module) { port ->
            Socket("localhost", port).use { socket ->
                socket.getOutputStream().write("GET /stall HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
                val after = thrown.get(60, TimeUnit.SECONDS) - started.get(60, TimeUnit.SECONDS)
                // Two seconds of writing and one for the frames, with room for a busy machine.
                assertTrue(after < 10.seconds, "respondDatastar threw after $after")
            }
        }
    }

    // kotlinx-coroutines may throw a copy of an exception that crossed coroutines, with the original as its cause.
    private fun Throwable?.isOrRecovers(failure: Throwable) =
        this === failure || (this != null && this.javaClass == failure.javaClass && cause === failure)

    /** [test] against a Ktor Tomcat with [module], its connectors set to [discardFacades], on its port. */
    private fun tomcat(
        discardFacades: Boolean,
        module: Application.() -> Unit,
        test: (Int) -> Unit,
    ) {
        val server = embeddedServer(Tomcat, port = 0, module = module).start(wait = false)
        try {
            // Ktor sets up its connectors after configureTomcat runs, so the setting goes on afterwards.
            val field = server.engine.javaClass.getDeclaredField("server")
            field.isAccessible = true
            (field.get(server.engine) as TomcatServer).service.findConnectors().forEach { it.discardFacades = discardFacades }
            test(
                runBlocking {
                    server.engine
                        .resolvedConnectors()
                        .first()
                        .port
                },
            )
        } finally {
            server.stop(100, 500)
        }
    }

    /** One GET, read to its last chunk or until the connection is cut. */
    private fun read(
        port: Int,
        path: String,
    ): String =
        Socket("localhost", port).use { socket ->
            socket.soTimeout = 20_000
            socket.getOutputStream().write("GET $path HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
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
