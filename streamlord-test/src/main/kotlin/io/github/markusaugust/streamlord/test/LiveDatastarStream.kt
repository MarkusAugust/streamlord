package io.github.markusaugust.streamlord.test

import io.github.markusaugust.streamlord.core.domain.DatastarEvent
import io.github.markusaugust.streamlord.core.domain.ElementPatchMode
import io.github.markusaugust.streamlord.core.domain.PatchElements
import io.github.markusaugust.streamlord.core.protocol.SseDecoder
import io.github.markusaugust.streamlord.core.protocol.SseMessage
import io.github.markusaugust.streamlord.core.protocol.SseReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.InputStreamReader
import java.io.Reader
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.concurrent.thread
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A stream that is still open, read as it arrives.
 *
 * [datastarEvents] asserts on a response that has ended. A live view never ends: the page holds a
 * stream open, a command arrives on another request, and the patch it causes goes down the stream
 * that was already there. Testing that takes a stream held open while something else happens:
 *
 * ```kotlin
 * LiveDatastarStream.open(URI("http://localhost:$port/feed")).use { feed ->
 *     feed.awaitSignal("count", 0)
 *     http.send(post("/increment"), discarding())
 *     feed.awaitSignal("count", 1)
 * }
 * ```
 *
 * A test that opens a fresh stream after each command passes while the live path is broken, because
 * the fresh stream renders the new state on its own. This one fails, which is the point.
 *
 * Each `await` reads until what it asks for arrives, and fails with an `AssertionError` that
 * prints what the stream carried if it has not arrived within the timeout, or if the stream ends
 * first. Waiting happens on real time, also inside `runTest`, whose virtual clock would otherwise
 * call every wait finished before the server had answered.
 *
 * Nothing here binds a test framework or an HTTP library: [open] uses the JDK's own client, and
 * the constructor takes any source of text.
 *
 * @param read The next piece of the stream as text, or `null` once it has ended. It may block:
 *   it is called on a thread of its own, so a wait that times out leaves the connection as it
 *   was. An exception from it ends the stream, as `null` does.
 * @param onClose Called by [close], to let go of the connection.
 */
public class LiveDatastarStream(
    private val read: () -> String?,
    private val onClose: () -> Unit = {},
) : AutoCloseable {
    private val reader = SseReader()
    private val chunks = Channel<String>(Channel.UNLIMITED)

    init {
        thread(isDaemon = true, name = "streamlord-live-stream") {
            try {
                while (true) chunks.trySend(read() ?: break)
            } catch (_: Exception) {
                // A connection that fails has ended, which is what the reading side is told.
            } finally {
                chunks.close()
            }
        }
    }
    private val lock = Mutex()
    private val events = ArrayList<DatastarEvent>()
    private val messages = ArrayList<SseMessage>()

    /** How many [events] the `await` functions have already looked at and moved past. */
    private var cursor = 0
    private var ended = false

    /**
     * Everything the stream has carried so far, ready for the assertions of [DatastarEvents]. Only
     * what has been read: the `await` functions are what read.
     */
    public val received: DatastarEvents get() = DatastarEvents(events.toList(), messages.toList())

    /**
     * The next element patch matching every argument given, among the events no earlier `await`
     * has moved past. Reads until one arrives.
     */
    public suspend fun awaitPatchElements(
        selector: String? = null,
        mode: ElementPatchMode? = null,
        containing: String? = null,
        timeout: Duration = DEFAULT_TIMEOUT,
    ): PatchElements {
        val wanted = { all: DatastarEvents -> all.assertPatchElements(selector, mode, containing = containing) }
        val describe = { all: DatastarEvents -> wanted(all).let { } }
        return awaitEvent(timeout, describe) { event ->
            DatastarEvents(listOf(event), emptyList()).let { one -> runCatching { wanted(one) }.getOrNull() }
        }
    }

    /**
     * Reads until the signal store, folded as the browser folds it, holds [expected] under [name].
     * Returns at once when it already does. See [DatastarEvents.assertSignal] for how values are
     * compared and how a nested name is written.
     */
    public suspend fun awaitSignal(
        name: String,
        expected: Any?,
        timeout: Duration = DEFAULT_TIMEOUT,
    ) {
        val wanted = { all: DatastarEvents -> all.assertSignal(name, expected) }
        if (runCatching { wanted(received) }.isSuccess) return
        awaitEvent(timeout, wanted) { _ -> runCatching { wanted(received) }.getOrNull() }
    }

    /**
     * The next Datastar event after those the `await` functions have moved past, or `null` once
     * the stream has ended. Comments and other messages are skipped, and kept in [received].
     */
    public suspend fun next(timeout: Duration = DEFAULT_TIMEOUT): DatastarEvent? =
        lock.withLock {
            within(timeout, { "No Datastar event arrived" }) {
                while (cursor == events.size) {
                    if (!readMore()) return@within null
                }
                events[cursor++]
            }
        }

    /** Let go of the connection. The server sees the reader leave. */
    override fun close() {
        onClose()
    }

    private suspend fun <T : Any> awaitEvent(
        timeout: Duration,
        describe: (DatastarEvents) -> Unit,
        match: (DatastarEvent) -> T?,
    ): T =
        lock.withLock {
            within(timeout, { failure(describe) }) {
                var found: T? = null
                while (found == null) {
                    if (cursor == events.size && !readMore()) {
                        throw AssertionError("${failure(describe)}\n\nThe stream ended first.")
                    }
                    while (found == null && cursor < events.size) found = match(events[cursor++])
                }
                found
            }
        }

    /** The assertion's own message, which prints the stream, for a wait that came up empty. */
    private fun failure(describe: (DatastarEvents) -> Unit): String =
        try {
            describe(received)
            "What was awaited arrived only after the wait"
        } catch (e: AssertionError) {
            e.message ?: "Nothing matched"
        }

    private suspend fun <T> within(
        timeout: Duration,
        what: () -> String,
        block: suspend () -> T,
    ): T =
        // Real time: inside runTest, a wait on the test's own clock is over before the server answers.
        withContext(Dispatchers.Default) {
            try {
                withTimeout(timeout) { block() }
            } catch (_: TimeoutCancellationException) {
                throw AssertionError("${what()}\n\nWaited $timeout.")
            }
        }

    /** One more piece of the stream into [events] and [messages]; false once it has ended. */
    private suspend fun readMore(): Boolean {
        if (ended) return false
        val chunk = chunks.receiveCatching().getOrNull()
        if (chunk == null) {
            ended = true
            return false
        }
        for (message in reader.feed(chunk)) {
            messages += message
            SseDecoder.event(message)?.let { events += it }
        }
        return true
    }

    public companion object {
        /** Long enough for a slow CI machine, short enough that a broken test says so. */
        public val DEFAULT_TIMEOUT: Duration = 5.seconds

        /**
         * Open [uri] with a `GET` the way the browser's Datastar does, with `Datastar-Request: true`
         * and `Accept: text/event-stream`, and read it as it arrives.
         *
         * @param headers Added to the request, for a session cookie or an authorisation header.
         * @param client The JDK client to send it with; a new one by default.
         * @throws AssertionError when the answer is not a 200 `text/event-stream`.
         */
        public fun open(
            uri: URI,
            headers: Map<String, String> = emptyMap(),
            client: HttpClient = HttpClient.newHttpClient(),
        ): LiveDatastarStream {
            val request =
                HttpRequest
                    .newBuilder(uri)
                    .header("Datastar-Request", "true")
                    .header("Accept", "text/event-stream")
                    .apply { headers.forEach { (name, value) -> header(name, value) } }
                    .GET()
                    .build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
            val contentType = response.headers().firstValue("Content-Type").orElse("")
            if (response.statusCode() != 200 || !contentType.startsWith("text/event-stream")) {
                response.body().close()
                throw AssertionError("Expected a 200 text/event-stream from $uri, got ${response.statusCode()} '$contentType'")
            }
            val body = response.body()
            val text: Reader = InputStreamReader(body, Charsets.UTF_8)
            val buffer = CharArray(8192)
            return LiveDatastarStream(
                read = {
                    val count = text.read(buffer)
                    if (count < 0) null else String(buffer, 0, count)
                },
                // The stream itself, not the reader: the reader's lock is held by a read in progress.
                onClose = { body.close() },
            )
        }
    }
}
