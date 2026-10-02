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

    override suspend fun send(event: DatastarEvent) {
        write(SseEncoder.encode(guard(event)))
    }

    override suspend fun comment(text: String) {
        write(SseEncoder.comment(text))
    }

    private suspend fun write(text: String) {
        authorise()
        lock.withLock {
            sink.write(text)
            sink.flush()
        }
    }

    /**
     * Ask again if the last answer has expired, and end the stream if the answer is no.
     *
     * Called before taking the write lock, because [StreamAuthorisation.onRefused] writes to this
     * same stream and a mutex that is already held would deadlock it. Once refused, this returns
     * at once, which is what lets the refusal block write its last words.
     */
    internal suspend fun authorise() {
        val authorisation = authorisation ?: return
        if (refused.get()) return

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

        // Set before the block runs: its own writes come back through here.
        if (!refused.compareAndSet(false, true)) return
        authorisation.onRefused(this)
        throw StreamRefusedException()
    }
}
