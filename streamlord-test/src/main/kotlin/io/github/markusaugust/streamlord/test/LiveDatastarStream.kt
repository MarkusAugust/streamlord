package io.github.markusaugust.streamlord.test

import io.github.markusaugust.streamlord.core.StreamlordException
import io.github.markusaugust.streamlord.core.domain.DatastarEvent
import io.github.markusaugust.streamlord.core.domain.ElementPatchMode
import io.github.markusaugust.streamlord.core.domain.PatchElements
import io.github.markusaugust.streamlord.core.protocol.SseDecoder
import io.github.markusaugust.streamlord.core.protocol.SseMessage
import io.github.markusaugust.streamlord.core.protocol.SseReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.InputStream
import java.io.InputStreamReader
import java.io.Reader
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.util.concurrent.ConcurrentLinkedDeque
import kotlin.concurrent.thread
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/**
 * A stream that is still open, read as it arrives.
 *
 * [datastarEvents] asserts on a response that has ended. A live view never ends: the page holds a
 * stream open, a command arrives on another request, and the patch it causes goes down the stream
 * that was already there. Testing that takes a stream held open while something else happens:
 *
 * ```kotlin
 * LiveDatastarStream.open(URI("http://127.0.0.1:$port/feed")).use { feed ->
 *     feed.awaitSignal("count", 0)
 *     post("/increment")
 *     feed.awaitSignal("count", 1)
 * }
 * ```
 *
 * A test that opens a fresh stream after each command passes while the live path is broken,
 * because the fresh stream renders the new state on its own. This one fails, which is the point.
 *
 * Each `await` reads until what it asks for arrives, and fails with an `AssertionError` that
 * prints what the stream carried if it has not arrived within the timeout, or if the stream ends
 * first. Waiting happens on real time, also inside `runTest`, whose virtual clock would otherwise
 * call every wait finished before the server had answered. The `await` functions are meant to be
 * called one after another from one test: they share one place in the stream.
 *
 * It is coroutine API, called from a Kotlin test inside `runTest` or `runBlocking`, and binds no
 * test framework: a failure is an `AssertionError`. [open] uses the JDK's own HTTP client; [of]
 * reads from any [Reader], for a client of your own. Close it, with `use { }`, so the server sees
 * the reader leave.
 */
public class LiveDatastarStream private constructor(
    private val read: () -> String?,
    private val onClose: () -> Unit,
) : AutoCloseable {
    private val reader = SseReader()
    private val lock = Mutex()

    /** A piece that a wait gave up on after taking it, read again before anything newer. */
    private val leftover = ConcurrentLinkedDeque<String>()
    private val chunks = Channel<String>(Channel.UNLIMITED, onUndeliveredElement = { leftover.addFirst(it) })

    // Replaced, never changed in place, so that received can be read from any thread.
    @Volatile private var events: List<DatastarEvent> = emptyList()

    @Volatile private var messages: List<SseMessage> = emptyList()

    /** How many [events] the `await` functions have looked at and moved past. */
    private var cursor = 0
    private var ended = false
    private var endedBy: Throwable? = null

    @Volatile private var closed = false

    init {
        thread(isDaemon = true, name = "streamlord-live-stream") {
            var cause: Throwable? = null
            try {
                while (true) chunks.trySend(read() ?: break)
            } catch (e: Exception) {
                cause = e
            } finally {
                chunks.close(cause)
            }
        }
    }

    /**
     * Everything the stream has carried so far, ready for the assertions of [DatastarEvents]. Only
     * what has been read: the `await` functions are what read.
     */
    public val received: DatastarEvents get() = DatastarEvents(events, messages)

    /**
     * The next element patch matching every argument given, among the events no earlier `await`
     * has moved past. Reads until one arrives. The arguments are those of
     * [DatastarEvents.assertPatchElements].
     */
    public suspend fun awaitPatchElements(
        selector: String? = null,
        mode: ElementPatchMode? = null,
        elements: String? = null,
        containing: String? = null,
        timeout: Duration = DEFAULT_TIMEOUT,
    ): PatchElements {
        val assertion = { seen: DatastarEvents -> seen.assertPatchElements(selector, mode, elements, containing) }
        return lock.withLock {
            val start = cursor
            awaitMatch(timeout, describe = { failure { assertion(DatastarEvents(events.drop(start), messages)) } }) { event ->
                (event as? PatchElements)?.takeIf { holds { assertion(DatastarEvents(listOf(it), emptyList())) } }
            }
        }
    }

    /**
     * Reads until the signal store, folded as the browser folds it, holds [expected] under [name].
     *
     * Whatever has already arrived is read first, so a value the stream has since changed is not
     * taken for the answer. When the store holds [expected] after that, it returns without waiting.
     * See [DatastarEvents.assertSignal] for how values are compared and how a nested name is written.
     */
    public suspend fun awaitSignal(
        name: String,
        expected: Any?,
        timeout: Duration = DEFAULT_TIMEOUT,
    ) {
        val assertion = { seen: DatastarEvents -> seen.assertSignal(name, expected) }
        lock.withLock {
            @Suppress("ControlFlowWithEmptyBody")
            while (readAvailable()) {
                // Everything that has arrived, before the store is judged.
            }
            if (!holds { assertion(received) }) {
                awaitMatch(timeout, describe = { failure { assertion(received) } }) { _ ->
                    Unit.takeIf { holds { assertion(received) } }
                }
            }
            cursor = events.size
        }
    }

    /**
     * The next Datastar event after those the `await` functions have moved past, or `null` once
     * the stream has ended. Comments and other messages are skipped, and kept in [received].
     */
    public suspend fun next(timeout: Duration = DEFAULT_TIMEOUT): DatastarEvent? =
        lock.withLock {
            val outcome =
                realTime(timeout) {
                    while (cursor == events.size) {
                        if (!readMore()) return@realTime Outcome.Ended
                    }
                    Outcome.Found(events[cursor++])
                }
            when (outcome) {
                null -> throw AssertionError(received.withStream("No Datastar event arrived within $timeout"))
                is Outcome.Found -> outcome.event
                Outcome.Ended -> null
            }
        }

    /** Let go of the connection. The server sees the reader leave on its next write. */
    override fun close() {
        closed = true
        onClose()
    }

    private sealed interface Outcome {
        data class Found(
            val event: DatastarEvent,
        ) : Outcome

        data object Ended : Outcome
    }

    private suspend fun <T : Any> awaitMatch(
        timeout: Duration,
        describe: () -> String,
        match: (DatastarEvent) -> T?,
    ): T {
        var found: T? = null
        val arrived =
            realTime(timeout) {
                while (found == null) {
                    if (cursor == events.size && !readMore()) return@realTime false
                    while (found == null && cursor < events.size) found = match(events[cursor++])
                }
                true
            }
        return when (arrived) {
            null -> throw AssertionError("${describe()}\n\nWaited $timeout.")
            false -> throw AssertionError("${describe()}\n\n${endedText()}", endedBy)
            true -> found!!
        }
    }

    private fun holds(assertion: () -> Unit): Boolean =
        try {
            assertion()
            true
        } catch (_: AssertionError) {
            false
        }

    /** The assertion's own message, which prints the events it looked at. */
    private fun failure(assertion: () -> Unit): String =
        try {
            assertion()
            received.withStream("It arrived as the wait ran out")
        } catch (e: AssertionError) {
            e.message ?: received.withStream("Nothing matched")
        }

    private fun endedText(): String =
        when {
            closed -> "The stream was closed first."
            endedBy != null -> "The stream ended first, on ${endedBy!!::class.simpleName}: ${endedBy!!.message}"
            else -> "The stream ended first."
        }

    /**
     * Run [block] on real time, also inside `runTest`; null when [timeout] passed first. Only this
     * wait's own timeout becomes null: a cancellation from outside stays a cancellation.
     */
    private suspend fun <T> realTime(
        timeout: Duration,
        block: suspend () -> T,
    ): T? = withContext(Dispatchers.Default) { withTimeoutOrNull(timeout) { block() } }

    /** Take a piece that has already arrived, without waiting. False when there was none. */
    private fun readAvailable(): Boolean {
        if (ended) return false
        val chunk = leftover.pollFirst() ?: chunks.tryReceive().getOrNull() ?: return false
        take(chunk)
        return true
    }

    /** One more piece of the stream into [events] and [messages]; false once it has ended. */
    private suspend fun readMore(): Boolean {
        if (ended) return false
        leftover.pollFirst()?.let {
            take(it)
            return true
        }
        val result = chunks.receiveCatching()
        val chunk = result.getOrNull()
        if (chunk == null) {
            ended = true
            endedBy = result.exceptionOrNull()
            reader.finish()?.let { messages = messages + it }
            return false
        }
        take(chunk)
        return true
    }

    private fun take(chunk: String) {
        val arrived = reader.feed(chunk)
        messages = messages + arrived
        val decoded =
            arrived.mapNotNull { message ->
                try {
                    SseDecoder.event(message)
                } catch (e: StreamlordException) {
                    throw AssertionError(received.withStream("The stream carried a frame the client would reject: ${e.message}"), e)
                }
            }
        events = events + decoded
    }

    public companion object {
        /** Long enough for a slow CI machine, short enough that a broken test says so. */
        public val DEFAULT_TIMEOUT: Duration = 5.seconds

        /** HTTP/1.1, as a browser speaks to a stream on a plain `http://` origin. */
        private val client: HttpClient by lazy { HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build() }

        /**
         * Open [uri] with a `GET` the way the browser's Datastar does, with `Datastar-Request: true`
         * and `Accept: text/event-stream`, and read it as it arrives.
         *
         * The answer has to start within [timeout]. A server sends its headers with its first
         * flush, so a handler that writes nothing until a command arrives holds `open` until it
         * does; send the current state on open, as a live view does anyway.
         *
         * @param headers Added to the request, for a session cookie or an authorisation header.
         * @throws AssertionError when no answer starts within [timeout], or the answer is not a
         *   200 `text/event-stream`.
         */
        public fun open(
            uri: URI,
            headers: Map<String, String> = emptyMap(),
            timeout: Duration = DEFAULT_TIMEOUT,
        ): LiveDatastarStream {
            val request =
                HttpRequest
                    .newBuilder(uri)
                    .timeout(timeout.toJavaDuration())
                    .header("Datastar-Request", "true")
                    .header("Accept", "text/event-stream")
                    .apply { headers.forEach { (name, value) -> header(name, value) } }
                    .GET()
                    .build()
            val response =
                try {
                    client.send(request, HttpResponse.BodyHandlers.ofInputStream())
                } catch (e: HttpTimeoutException) {
                    throw AssertionError("No answer from $uri within $timeout", e)
                }
            val body: InputStream = response.body()
            val contentType = response.headers().firstValue("Content-Type").orElse("")
            val isStream = contentType.substringBefore(';').trim().equals("text/event-stream", ignoreCase = true)
            if (response.statusCode() != 200 || !isStream) {
                body.close()
                throw AssertionError("Expected a 200 text/event-stream from $uri, got ${response.statusCode()} '$contentType'")
            }
            // The stream itself is what close() closes: a read in progress holds the reader's lock.
            return of(InputStreamReader(body, Charsets.UTF_8), onClose = body)
        }

        /**
         * Read a stream from [text], for an HTTP client of your own. [onClose] is what [close]
         * closes. Pass the underlying stream when the reader's own `close` waits for a read in
         * progress, as an `InputStreamReader`'s does.
         */
        public fun of(
            text: Reader,
            onClose: AutoCloseable = text,
        ): LiveDatastarStream {
            val buffer = CharArray(8192)
            return LiveDatastarStream(
                read = {
                    val count = text.read(buffer)
                    if (count < 0) null else String(buffer, 0, count)
                },
                onClose = { onClose.close() },
            )
        }
    }
}
