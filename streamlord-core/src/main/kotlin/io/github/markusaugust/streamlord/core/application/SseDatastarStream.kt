package io.github.markusaugust.streamlord.core.application

import io.github.markusaugust.streamlord.core.StreamRefusedException
import io.github.markusaugust.streamlord.core.domain.DatastarEvent
import io.github.markusaugust.streamlord.core.port.driven.SignalsCodec
import io.github.markusaugust.streamlord.core.port.driven.SseSink
import io.github.markusaugust.streamlord.core.port.driving.DatastarStream
import io.github.markusaugust.streamlord.core.protocol.SseEncoder
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean
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
    }

    /**
     * Ask again if the last answer has expired, and end the stream if the answer is no.
     *
     * Called before taking the write lock, so a slow verdict never holds up another writer.
     * Once refused, every write through this stream throws again. A handler that catches the
     * exception and carries on therefore sends nothing more, which is the whole point: the
     * refusal has to hold even when the handler does not stop.
     */
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
        if (!due || authorisation.allows()) return

        if (!refused.compareAndSet(false, true)) throw StreamRefusedException()
        authorisation.onRefused(LastWords())
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
