package io.github.markusaugust.streamlord.ktor

import io.github.markusaugust.streamlord.core.application.StreamAuthorisation
import io.github.markusaugust.streamlord.core.application.Streamlord
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.availableForRead
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import java.util.zip.Inflater
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
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

            Streamlord(compress = true).streamGzipped(channel, refuse, null) { patchSignals("n" to 1) }

            // The last words and the trailer each get the limit, and no more.
            val limit = StreamAuthorisation.LAST_WORDS_LIMIT
            assertTrue(currentTime <= (limit * 2).inWholeMilliseconds, "took ${currentTime}ms")
            assertTrue(currentTime >= limit.inWholeMilliseconds, "took ${currentTime}ms: the write never waited")

            // A Ktor write is whole or not at all, also when it is cancelled: what the channel holds
            // inflates to whole frames, the last words among them, and never to part of one.
            val held = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(64 * 1024)
            while (channel.availableForRead > 0) held.write(chunk, 0, channel.readAvailable(chunk))
            val bytes = held.toByteArray()
            val inflater = Inflater(true).apply { setInput(bytes, 10, bytes.size - 10) }
            val text = StringBuilder()
            val out = ByteArray(64 * 1024)
            while (!inflater.needsInput() && !inflater.finished()) {
                val n = inflater.inflate(out)
                text.append(String(out, 0, n, Charsets.UTF_8))
            }
            // Refused before the block runs, so the last words are the one frame, and they are whole.
            assertTrue(
                text.startsWith("event: datastar-patch-signals\ndata: signals {\"noise\":\"$noise\"}\n\n"),
                "the last words were cut",
            )
            assertEquals(1, Regex("^event: ", RegexOption.MULTILINE).findAll(text).count())
        }
}
