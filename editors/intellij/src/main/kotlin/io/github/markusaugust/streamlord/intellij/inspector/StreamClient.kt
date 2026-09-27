package io.github.markusaugust.streamlord.intellij.inspector

import io.github.markusaugust.streamlord.analysis.DatastarFrame
import io.github.markusaugust.streamlord.analysis.Requests
import io.github.markusaugust.streamlord.analysis.SseParser
import io.github.markusaugust.streamlord.analysis.decodeDatastar
import java.io.InputStream
import java.net.ConnectException
import java.net.URI
import java.net.UnknownHostException
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.channels.UnresolvedAddressException
import java.time.Duration
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The network half of the Stream Inspector, free of any editor API so it can be tested against a
 * real server. Opens a Datastar request exactly as the browser client would (signals in the
 * query string for GET and DELETE, in the body otherwise, `Datastar-Request: true`) and reports
 * what comes back.
 */
class StreamClient(
    private val executor: ExecutorService =
        Executors.newCachedThreadPool { r ->
            Thread(r, "streamlord-inspector").apply { isDaemon = true }
        },
) {
    enum class Status { CONNECTING, OPEN, CLOSED }

    data class NonSseResponse(
        val http: String,
        val contentType: String,
        val body: String,
        val headers: Map<String, String>,
    )

    interface Handlers {
        fun onStatus(
            status: Status,
            http: String? = null,
            contentType: String? = null,
        ) {}

        fun onFrame(frame: DatastarFrame) {}

        fun onComment(text: String) {}

        fun onNonSse(response: NonSseResponse) {}

        fun onError(message: String) {}
    }

    /** A running request; [close] aborts it. */
    class Connection internal constructor(
        private val future: Future<*>,
        private val aborted: AtomicBoolean,
        private val stream: AtomicReferenceStream,
    ) : AutoCloseable {
        override fun close() {
            aborted.set(true)
            stream.close()
            future.cancel(true)
        }
    }

    internal class AtomicReferenceStream {
        @Volatile
        var stream: InputStream? = null

        fun close() {
            runCatching { stream?.close() }
        }
    }

    private val client: HttpClient =
        HttpClient
            .newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .build()

    /** Open the stream and pump events to the handlers until it closes, errors or is aborted. */
    fun open(
        url: String,
        method: String,
        signalsJson: String,
        headers: Map<String, String>,
        handlers: Handlers,
    ): Connection {
        val aborted = AtomicBoolean(false)
        val holder = AtomicReferenceStream()
        val future =
            executor.submit {
                run(url, method, signalsJson, headers, handlers, aborted, holder)
            }
        return Connection(future, aborted, holder)
    }

    private fun run(
        url: String,
        method: String,
        signalsJson: String,
        headers: Map<String, String>,
        handlers: Handlers,
        aborted: AtomicBoolean,
        holder: AtomicReferenceStream,
    ) {
        val request =
            try {
                build(url, method, signalsJson, headers)
            } catch (_: Exception) {
                handlers.onError("Not a valid URL: $url")
                return
            }
        handlers.onStatus(Status.CONNECTING)
        try {
            val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
            val contentType = response.headers().firstValue("content-type").orElse("")
            val http = "${response.statusCode()} ${reason(response.statusCode())}".trim()
            handlers.onStatus(Status.OPEN, http, contentType)
            response.body().use { body ->
                holder.stream = body
                if (!contentType.contains("text/event-stream")) {
                    val text = body.readBytes().toString(Charsets.UTF_8)
                    val datastarHeaders = LinkedHashMap<String, String>()
                    for ((k, v) in response.headers().map()) {
                        if (k.lowercase().startsWith("datastar-")) {
                            datastarHeaders[k.lowercase()] =
                                v.joinToString(", ")
                        }
                    }
                    handlers.onNonSse(NonSseResponse(http, contentType, text, datastarHeaders))
                    handlers.onStatus(Status.CLOSED)
                    return
                }
                val parser = SseParser()
                val buffer = ByteArray(8192)
                val decoder = Charsets.UTF_8.newDecoder()
                while (!aborted.get()) {
                    val n = body.read(buffer)
                    if (n < 0) break
                    val chunk = decoder.decode(java.nio.ByteBuffer.wrap(buffer, 0, n)).toString()
                    for (msg in parser.feed(chunk)) {
                        if (msg.isComment) {
                            handlers.onComment(msg.comments.joinToString("\n"))
                        } else {
                            handlers.onFrame(decodeDatastar(msg))
                        }
                    }
                }
            }
            handlers.onStatus(Status.CLOSED)
        } catch (e: Exception) {
            if (aborted.get() || e is CancellationException || e is InterruptedException) {
                handlers.onStatus(Status.CLOSED)
                return
            }
            handlers.onError(describe(e, url))
            handlers.onStatus(Status.CLOSED)
        }
    }

    private fun build(
        url: String,
        method: String,
        signalsJson: String,
        headers: Map<String, String>,
    ): HttpRequest {
        val bodyless = method == "GET" || method == "DELETE"
        val target = URI(Requests.requestUrl(url, method, signalsJson))
        val builder =
            HttpRequest
                .newBuilder(target)
                .header("Accept", "text/event-stream")
                .header("Datastar-Request", "true")
        for ((k, v) in headers) builder.header(k, v)
        if (bodyless) {
            builder.method(method, HttpRequest.BodyPublishers.noBody())
        } else {
            builder.header("Content-Type", "application/json")
            builder.method(method, HttpRequest.BodyPublishers.ofString(signalsJson))
        }
        return builder.build()
    }

    companion object {
        /** `fetch failed` says nothing; the cause underneath usually says everything. */
        fun describe(
            e: Throwable,
            url: String,
        ): String {
            val host =
                runCatching {
                    URI(url).let {
                        "${it.host}:${if (it.port > 0) {
                            it.port
                        } else if (it.scheme == "https") {
                            443
                        } else {
                            80
                        }}"
                    }
                }.getOrDefault("?")
            var cause: Throwable? = e
            while (cause != null) {
                when (cause) {
                    is ConnectException -> {
                        return "Connection refused at $host. Is the server running?"
                    }

                    is UnknownHostException, is UnresolvedAddressException -> {
                        return "Host not found: ${runCatching { URI(url).host }.getOrNull() ?: url}."
                    }

                    else -> {}
                }
                cause = cause.cause
            }
            return e.message ?: e.javaClass.simpleName
        }

        fun reason(status: Int): String =
            when (status) {
                200 -> "OK"
                201 -> "Created"
                204 -> "No Content"
                301 -> "Moved Permanently"
                302 -> "Found"
                304 -> "Not Modified"
                400 -> "Bad Request"
                401 -> "Unauthorized"
                403 -> "Forbidden"
                404 -> "Not Found"
                405 -> "Method Not Allowed"
                409 -> "Conflict"
                422 -> "Unprocessable Content"
                429 -> "Too Many Requests"
                500 -> "Internal Server Error"
                502 -> "Bad Gateway"
                503 -> "Service Unavailable"
                else -> ""
            }
    }
}
