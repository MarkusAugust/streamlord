package io.github.markusaugust.streamlord.core.port.driven

/**
 * Driven port: the channel through which encoded events leave the hexagon.
 *
 * Adapters implement this over whatever the framework offers: a Ktor `ByteWriteChannel`, a
 * servlet `OutputStream`, a test buffer. The core never learns which. Every event is written
 * with one [write] followed by one [flush], so an implementation may buffer inside [write]
 * as long as [flush] pushes the bytes all the way to the socket.
 */
public interface SseSink {
    /** Write already-encoded SSE text. */
    public suspend fun write(text: String)

    /** Push everything written so far to the client. Called after every event. */
    public suspend fun flush()
}

/**
 * An [SseSink] that keeps everything in memory. For tests, previews and golden files.
 */
public class BufferedSseSink : SseSink {
    private val buffer = StringBuilder()
    private var flushes = 0

    /** Number of times [flush] was called: one per event when used through a stream. */
    public val flushCount: Int get() = flushes

    override suspend fun write(text: String) {
        buffer.append(text)
    }

    override suspend fun flush() {
        flushes++
    }

    /** Everything written so far. */
    public fun text(): String = buffer.toString()

    override fun toString(): String = text()
}
