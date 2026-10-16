package io.github.markusaugust.streamlord.core.application

import io.github.markusaugust.streamlord.core.StreamRefusedException
import io.github.markusaugust.streamlord.core.domain.DatastarEvent
import io.github.markusaugust.streamlord.core.port.driven.SignalsCodec
import io.github.markusaugust.streamlord.core.port.driven.SseSink
import io.github.markusaugust.streamlord.core.port.driving.DatastarStream
import io.github.markusaugust.streamlord.core.protocol.SseEncoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * The one true implementation of [DatastarStream]: encode, take the lock, write, flush, release.
 *
 * The mutex guarantees that events from concurrent coroutines never interleave on the wire,
 * which the SDK specification asks for and which a browser mid-parse would not forgive.
 *
 * Every write passes [authorise] first, which is why a [StreamAuthorisation] needs no hook of its
 * own: there is one way out of this class.
 */
internal class SseDatastarStream(
    private val sink: SseSink,
    override val codec: SignalsCodec,
    private val guard: (DatastarEvent) -> DatastarEvent = { it },
    private val authorisation: StreamAuthorisation? = null,
) : DatastarStream {
    private val lock = Mutex()

    /** Guards the time mark alone, so a slow verdict is never asked for under the write lock. */
    private val gate = Mutex()
    private var asked: TimeSource.Monotonic.ValueTimeMark? = null
    private val refused = AtomicBoolean(false)

    /** One element whenever a frame has reached the client since [pulse] last looked. */
    private val written = Channel<Unit>(Channel.CONFLATED)

    /** Whether the authorisation has answered no. A handler may have swallowed the exception. */
    internal val isRefused: Boolean get() = refused.get()

    override suspend fun send(event: DatastarEvent) {
        write(SseEncoder.encode(guard(event)))
    }

    override suspend fun comment(text: String) {
        write(SseEncoder.comment(text))
    }

    private suspend fun write(text: String) {
        authorise()
        lock.withLock {
            // Asked again under the lock: a writer that passed the check before another
            // coroutine was refused must not get its frame out after the last words.
            if (refused.get()) throw StreamRefusedException()
            emit(text)
        }
    }

    private suspend fun emit(text: String) {
        sink.write(text)
        sink.flush()
        written.trySend(Unit)
    }

    /**
     * Write a keep-alive comment each time [interval] passes with nothing written, until cancelled.
     *
     * Any frame resets the wait, a comment included, so a busy stream never carries one and an
     * idle stream carries one per [interval]. The comment is an ordinary write: it waits for the
     * lock and passes [authorise], which means an idle stream with an authorisation is asked again
     * on the heartbeat's schedule, and a refusal ends it here as it would anywhere else.
     *
     * The first wait is [FIRST_PULSE], or [interval] if it is shorter: some servers, Netty among
     * them, send no headers before the first byte of the body and give up on a response that has
     * written nothing for a while. A stream that writes within it carries no early comment.
     *
     * After a refusal, every comment is refused again, which ends a block that caught its own
     * refusal and went on waiting. A comment of its own also lands in [written], so the wait after
     * it starts at once.
     */
    internal suspend fun pulse(interval: Duration): Nothing {
        var wait = minOf(interval, FIRST_PULSE)
        while (true) {
            if (withTimeoutOrNull(wait) { written.receive() } == null) comment()
            wait = interval
        }
    }

    /**
     * Ask again if the last answer has expired, and end the stream if the answer is no.
     *
     * Called before taking the write lock, so a slow verdict never holds up another writer.
     * Once refused, every write through this stream throws again. A handler that catches the
     * exception and carries on therefore sends nothing more, which is the whole point: the
     * refusal has to hold even when the handler does not stop.
     */
    /*
     * The answer, with a CancellationException of the check's own, such as a withTimeout around a
     * database call, turned into a failure. Thrown as it is, it would end only the coroutine that
     * asked: on the heartbeat that is the heartbeat itself, which stops without a word, and the
     * stream would never be asked again nor find a reader who left.
     */
    private suspend fun allowed(authorisation: StreamAuthorisation): Boolean =
        try {
            authorisation.allows()
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            throw IllegalStateException("The stream's authorisation check threw ${e::class.simpleName}: ${e.message}", e)
        }

    internal suspend fun authorise() {
        val authorisation = authorisation ?: return
        if (refused.get()) throw StreamRefusedException()

        val due =
            gate.withLock {
                val last = asked
                if (last != null && last.elapsedNow() < authorisation.every) {
                    false
                } else {
                    asked = TimeSource.Monotonic.markNow()
                    true
                }
            }
        if (!due || allowed(authorisation)) return

        if (!refused.compareAndSet(false, true)) throw StreamRefusedException()
        // Not cancellable: with a heartbeat, the refusal can land in one coroutine while the
        // other is torn down, and the last words go out whole. Bounded instead, so a slow
        // onRefused, or a reader who stopped reading on a suspending sink, cannot hold the stream
        // open forever. A write that blocks its thread is beyond any timeout.
        withContext(NonCancellable) {
            withTimeoutOrNull(StreamAuthorisation.LAST_WORDS_LIMIT) { authorisation.onRefused(LastWords()) }
        }
        throw StreamRefusedException()
    }

    /**
     * What [StreamAuthorisation.onRefused] writes through. It is a separate stream because the
     * stream itself is closed to everyone by then, and the refusal block is the one caller that
     * may still speak. It takes the same lock, so its frames do not interleave with a write that
     * was already under way.
     */
    private inner class LastWords : DatastarStream {
        override val codec: SignalsCodec get() = this@SseDatastarStream.codec

        override suspend fun send(event: DatastarEvent) {
            val text = SseEncoder.encode(guard(event))
            lock.withLock { emit(text) }
        }

        override suspend fun comment(text: String) {
            val encoded = SseEncoder.comment(text)
            lock.withLock { emit(encoded) }
        }
    }
}

/** Well under the ten seconds Netty allows a response that has written nothing. */
internal val FIRST_PULSE: Duration = 5.seconds
