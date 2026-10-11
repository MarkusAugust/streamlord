package io.github.markusaugust.streamlord.ktor

import io.github.markusaugust.streamlord.core.application.StreamAuthorisation
import io.github.markusaugust.streamlord.core.application.Streamlord
import io.ktor.utils.io.ByteChannel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class) // currentTime, to read how long the virtual clock ran
class GzipStallTest {
    // Random text barely compresses, so this fills the channel and the write waits on the reader.
    private val noise = Random(7).nextBytes(3 * 1024 * 1024).joinToString("") { "%02x".format(it) }

    // A reader that stopped reading: nothing ever takes from the channel.
    @Test
    fun `a refused gzip stream to a reader that stopped ends within the last words limit`() =
        runTest {
            val channel = ByteChannel()
            val refuse = StreamAuthorisation(onRefused = { patchSignals("noise" to noise) }) { false }

            Streamlord(compress = true).streamGzipped(channel, refuse) { patchSignals("n" to 1) }

            // The last words and the trailer each get the limit, and no more.
            val limit = StreamAuthorisation.LAST_WORDS_LIMIT
            assertTrue(currentTime <= (limit * 2).inWholeMilliseconds, "took ${currentTime}ms")
            assertTrue(currentTime >= limit.inWholeMilliseconds, "took ${currentTime}ms: the write never waited")
        }
}
