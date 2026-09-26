package io.github.markusaugust.streamlord.core.application

import io.github.markusaugust.streamlord.core.domain.DatastarEvent
import io.github.markusaugust.streamlord.core.port.driven.SignalsCodec
import io.github.markusaugust.streamlord.core.port.driven.SseSink
import io.github.markusaugust.streamlord.core.port.driving.DatastarStream
import io.github.markusaugust.streamlord.core.protocol.SseEncoder
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The one true implementation of [DatastarStream]: encode, take the lock, write, flush, release.
 *
 * The mutex guarantees that events from concurrent coroutines never interleave on the wire,
 * which the SDK specification asks for and which a browser mid-parse would not forgive.
 */
internal class SseDatastarStream(
    private val sink: SseSink,
    override val codec: SignalsCodec,
) : DatastarStream {
    private val lock = Mutex()

    override suspend fun send(event: DatastarEvent) {
        write(SseEncoder.encode(event))
    }

    override suspend fun comment(text: String) {
        write(SseEncoder.comment(text))
    }

    private suspend fun write(text: String) {
        lock.withLock {
            sink.write(text)
            sink.flush()
        }
    }
}
