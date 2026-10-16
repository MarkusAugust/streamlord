package io.github.markusaugust.streamlord.intellij.inspector

import io.github.markusaugust.streamlord.analysis.DatastarFrame
import io.github.markusaugust.streamlord.analysis.Requests
import io.github.markusaugust.streamlord.analysis.SseMessage
import io.github.markusaugust.streamlord.analysis.SseParser
import io.github.markusaugust.streamlord.analysis.decodeDatastar
import io.github.markusaugust.streamlord.core.json.JsonParser
import java.io.InputStream
import java.io.InputStreamReader
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
 * what comes back. The URL and the signals are the ones the curl export writes.
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

    /** Open the stream and pump events to the handlers until it closes, errors or is aborted. [signals] is JSON as the user wrote it. */
    fun open(
        url: String,
        method: String,
        signals: String,
        headers: Map<String, String>,
        handlers: Handlers,
    ): Connection {
        val aborted = AtomicBoolean(false)
        val holder = AtomicReferenceStream()
        val future =
            executor.submit {
                run(url, method, signals, headers, handlers, aborted, holder)
            }
        return Connection(future, aborted, holder)
    }

    private fun run(
        url: String,
        method: String,
        signals: String,
        headers: Map<String, String>,
        handlers: Handlers,
        aborted: AtomicBoolean,
        holder: AtomicReferenceStream,
    ) {
        // Judged as written, so the message shows the user's URL and not the one with the signals added.
        Requests.sendableUrl(url).error?.let {
            handlers.onError(it)
            return
        }
        val signalsJson = Requests.compactSignals(signals)
        // Judged on the compacted text, which is what goes out: a lone no-break space is not blank to it.
        try {
            JsonParser.parse(signalsJson)
        } catch (e: Exception) {
            handlers.onError("Signals are not valid JSON: ${e.message}")
            return
        }
        val sendable = Requests.sendableUrl(Requests.requestUrl(url, method, signalsJson))
        val target =
            sendable.url ?: run {
                handlers.onError(sendable.error ?: "Cannot send $url")
                return
            }
        val request =
            try {
                build(target, method, signalsJson, headers)
            } catch (e: Exception) {
                // A header the HTTP client will not set, such as Host, or a port that is not a number.
                handlers.onError("Cannot send the request: ${e.message ?: e.javaClass.simpleName}")
                return
            }
        handlers.onStatus(Status.CONNECTING)
        try {
            val response =
                try {
                    client.send(request, HttpResponse.BodyHandlers.ofInputStream())
                } catch (e: ConnectException) {
                    /*
                     * localhost is 127.0.0.1 first, and Java's client tries no other address. A
                     * server listening on ::1 alone is reached by curl and the browser, and by the
                     * VS Code inspector, so it is tried here before the refusal is reported.
                     */
                    val v6 = ipv6Loopback(target) ?: throw e
                    client.send(build(v6, method, signalsJson, headers), HttpResponse.BodyHandlers.ofInputStream())
                }
            response.body().use { body ->
                // Held before anything is read, so a close from here on ends the read; one that came while the
                // request was in flight is honoured here, and the response is never reported open.
                holder.stream = body
                if (aborted.get()) {
                    handlers.onStatus(Status.CLOSED)
                    return
                }
                val contentType = response.headers().firstValue("content-type").orElse("")
                val http = "${response.statusCode()} ${reason(response.statusCode())}".trim()
                handlers.onStatus(Status.OPEN, http, contentType)
                if (!Requests.readsAsStream(response.statusCode(), response.headers().firstValue("content-type").orElse(null))) {
                    // Up to the limit, so a large response does not land in memory whole.
                    val bytes = body.readNBytes(Requests.NON_STREAM_LIMIT + 1)
                    val cut = bytes.size > Requests.NON_STREAM_LIMIT
                    val read = String(bytes, 0, minOf(bytes.size, Requests.NON_STREAM_LIMIT), Charsets.UTF_8)
                    val text = if (cut) "$read\n\n(cut at 1 MiB)" else read
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
                pump(body, handlers, aborted)
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
        val builder =
            HttpRequest
                .newBuilder(URI(url))
                .header("Accept", Requests.ACCEPT)
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
        /**
         * Read an event stream from [body] and hand each message to [handlers], until it ends or [aborted] is set.
         *
         * The reader keeps a character split between two reads for the next one and turns a byte that is not
         * UTF-8 into U+FFFD, as the browser's decoder does. It is not closed here: closing it waits for a read
         * under way, and [Connection.close] ends one by closing [body] itself.
         */
        internal fun pump(
            body: InputStream,
            handlers: Handlers,
            aborted: AtomicBoolean,
        ) {
            val reader = InputStreamReader(body, Charsets.UTF_8)
            val parser = SseParser()
            val buffer = CharArray(8192)

            fun show(msg: SseMessage) {
                if (msg.isComment) {
                    handlers.onComment(msg.comments.joinToString("\n"))
                } else {
                    handlers.onFrame(decodeDatastar(msg))
                }
            }
            // A byte order mark is the parser's to drop, before each field as the client drops it.
            while (!aborted.get()) {
                val n = reader.read(buffer)
                if (n < 0) {
                    parser.finish()?.let(::show)
                    break
                }
                parser.feed(String(buffer, 0, n)).forEach(::show)
            }
        }

        private val LOCALHOST = Regex("""^(https?://(?:[^@/?#]*@)?)localhost(?=[:/?#]|$)""", RegexOption.IGNORE_CASE)

        /** [url] with its host `localhost` written as `[::1]`, or null when the host is something else. */
        fun ipv6Loopback(url: String): String? = LOCALHOST.find(url)?.let { url.replaceRange(it.range, "${it.groupValues[1]}[::1]") }

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
