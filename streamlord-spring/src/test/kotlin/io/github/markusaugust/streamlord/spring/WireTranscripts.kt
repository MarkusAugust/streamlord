package io.github.markusaugust.streamlord.spring

import io.github.markusaugust.streamlord.core.domain.DatastarEvent
import io.github.markusaugust.streamlord.core.domain.ElementNamespace
import io.github.markusaugust.streamlord.core.domain.ElementPatchMode
import io.github.markusaugust.streamlord.core.domain.ElementsResponse
import io.github.markusaugust.streamlord.core.domain.ExecuteScript
import io.github.markusaugust.streamlord.core.domain.PatchElements
import io.github.markusaugust.streamlord.core.domain.PatchSignals
import io.github.markusaugust.streamlord.core.domain.ScriptResponse
import io.github.markusaugust.streamlord.core.domain.SignalsResponse
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPInputStream
import kotlin.test.assertEquals
import kotlin.test.fail
import kotlin.time.Duration.Companion.milliseconds

/*
 * The approved wire transcripts under conformance/wire: for a fixed set of requests, the exact
 * HTTP answer each adapter gives, committed as text and compared on every test run. The same file
 * is in streamlord-ktor's tests, so the four transcripts of a case can be read side by side.
 */

/** One request of the fixed set every adapter answers, named as its transcript file is. */
data class WireCase(
    val name: String,
    val method: String,
    val path: String,
    val headers: List<Pair<String, String>> = emptyList(),
    val body: String? = null,
    /** Sent to the instance with `compress = true`. */
    val compressed: Boolean = false,
)

/** The requests, and the events and responses their handlers answer with. */
object WireCases {
    /** Small, so that a request over it stays readable in its transcript. */
    const val MAX_SIGNALS_SIZE = 256

    const val ALIASED_PREFIX = "data-star-"

    const val COMMENT = "the gate holds"

    private const val JSON = "application/json"

    private fun signals(
        name: String,
        method: String,
        path: String = "/signals",
        contentType: String = JSON,
        body: String? = null,
    ) = WireCase(name, method, path, listOfNotNull(body?.let { "Content-Type" to contentType }), body)

    val all: List<WireCase> =
        listOf(
            WireCase("stream-elements", "GET", "/stream/elements"),
            WireCase("stream-signals", "GET", "/stream/signals"),
            WireCase("stream-scripts", "GET", "/stream/scripts"),
            WireCase("stream-sse-fields", "GET", "/stream/sse-fields"),
            WireCase("response-elements", "GET", "/response/elements"),
            WireCase("response-signals", "GET", "/response/signals"),
            WireCase("response-script", "GET", "/response/script"),
            WireCase("response-no-content", "GET", "/response/no-content"),
            // URLSearchParams, which Datastar's client builds the query with, writes a space as + and a + as %2B.
            signals("signals-get-plus", "GET", "/signals?datastar=%7B%22name%22%3A%22Gorvek+the+Grim%22%2C%22sum%22%3A%221%2B1%22%7D"),
            signals("signals-get-none", "GET"),
            signals("signals-delete", "DELETE", "/signals?datastar=%7B%22hp%22%3A100%7D"),
            signals("signals-post", "POST", body = """{"hp":100}"""),
            signals("signals-put", "PUT", body = """{"hp":100}"""),
            signals("signals-patch", "PATCH", body = """{"hp":100}"""),
            signals("signals-query", "QUERY", body = """{"hp":100}"""),
            signals("signals-post-empty", "POST", body = ""),
            signals("signals-post-form", "POST", contentType = "application/x-www-form-urlencoded", body = "hp=100"),
            signals("signals-post-too-large", "POST", body = """{"saga":"${"x".repeat(MAX_SIGNALS_SIZE)}"}"""),
            WireCase("gzip-stream", "GET", "/gzip/stream", listOf("Accept-Encoding" to "gzip"), compressed = true),
            WireCase("csp-page", "GET", "/csp/page"),
            WireCase("script-aliased", "GET", "/stream/script-aliased"),
        )

    val elements: List<DatastarEvent> =
        listOf(
            PatchElements("""<div id="hall">The hall</div>"""),
            PatchElements("""<div id="hall">The hall, morphed</div>""", selector = "#hall", mode = ElementPatchMode.OUTER),
            PatchElements("<p>Inside</p>", selector = "#hall", mode = ElementPatchMode.INNER),
            PatchElements("""<div id="hall">Replaced</div>""", selector = "#hall", mode = ElementPatchMode.REPLACE),
            PatchElements("<li>First</li>", selector = "#list", mode = ElementPatchMode.PREPEND),
            PatchElements("<li>Last</li>", selector = "#list", mode = ElementPatchMode.APPEND),
            PatchElements("<hr>", selector = "#hall", mode = ElementPatchMode.BEFORE),
            PatchElements("<hr>", selector = "#hall", mode = ElementPatchMode.AFTER),
            PatchElements.remove("#gone"),
            PatchElements(
                """<circle id="moon" r="4"/>""",
                selector = "#sky",
                mode = ElementPatchMode.APPEND,
                namespace = ElementNamespace.SVG,
            ),
            PatchElements("""<div id="hall">In transition</div>""", useViewTransition = true),
            PatchElements("""<div id="hall">In transition</div>""", useViewTransition = true, viewTransitionSelector = "#hall"),
            PatchElements("<ul id=\"list\">\n  <li>One</li>\n  <li>Two</li>\n</ul>"),
        )

    val signals: List<DatastarEvent> =
        listOf(
            PatchSignals("""{"hp":100,"name":"Gorvek"}"""),
            PatchSignals("""{"hp":100}""", onlyIfMissing = true),
            PatchSignals("{\n  \"quest\": {\n    \"stage\": 2\n  }\n}"),
        )

    val scripts: List<DatastarEvent> =
        listOf(
            ExecuteScript("console.log('Gorvek enters')"),
            ExecuteScript("console.log('attributed')", autoRemove = false, attributes = mapOf("type" to "module", "id" to "entry")),
        )

    val withEventId: DatastarEvent = PatchSignals("""{"hp":99}""", eventId = "42")

    val withRetry: DatastarEvent = PatchSignals("""{"hp":98}""", retry = 1001.milliseconds)

    val aliasedScript: DatastarEvent = ExecuteScript("console.log('aliased')")

    val elementsResponse =
        ElementsResponse(
            """<circle id="moon" r="4"/>""",
            selector = "#sky",
            mode = ElementPatchMode.APPEND,
            namespace = ElementNamespace.SVG,
            useViewTransition = true,
        )

    val signalsResponse = SignalsResponse("""{"hp":100}""", onlyIfMissing = true)

    val scriptResponse = ScriptResponse("console.log('one shot')", attributes = mapOf("type" to "module"))

    /** The echo of what a handler read: the raw JSON text, or null for a request without signals. */
    fun echo(
        method: String,
        read: String?,
    ): String = """{"method":"$method","read":${read ?: "null"}}"""

    /** The page the CSP case serves, with the nonce where Datastar reads it. */
    fun page(nonce: String): String =
        """<!DOCTYPE html><html data-nonce="$nonce"><head><title>Gate</title></head><body><h1>The gate</h1></body></html>"""
}

/**
 * Sends a case over a raw HTTP/1.1 socket, so the bytes are what a browser gets, and writes down
 * what came back; then compares the transcript with the approved file, or rewrites the file under
 * `-Dstreamlord.updateTranscripts=true`.
 */
object WireTranscript {
    // Gradle passes the directory; from an IDE the module's working directory is the module, as the other fixture tests assume.
    private val directory: Path = Path.of(System.getProperty("streamlord.wireDir") ?: "../conformance/wire")
    private val updating = System.getProperty("streamlord.updateTranscripts") == "true"

    private const val ACCEPT = "text/event-stream, text/html, application/json"

    fun verify(
        adapter: String,
        case: WireCase,
        port: Int,
        module: String,
        testClass: String,
    ) {
        val (exchange, bodyNote) = exchange(case, port)
        val transcript =
            buildString {
                append("# Wire transcript: $adapter, ${case.name}.\n")
                append(
                    "# Written by $testClass under ./gradlew :$module:updateTranscripts, compared with the live answer on every test run.\n",
                )
                append("# Normalised: CRLF written as LF; the port as {port}; the values of Date, Server and Keep-Alive as {date}, ")
                append(
                    "{server} and {keep-alive};\n# a CSP nonce as {nonce}; a line feed after a body that ends without one. Every other byte is as the client got it.\n",
                )
                append("# Body: $bodyNote.\n")
                append(exchange)
            }
        val file = directory.resolve(adapter).resolve("${case.name}.txt")
        if (updating) {
            Files.createDirectories(file.parent)
            Files.writeString(file, transcript)
            return
        }
        if (!Files.exists(file)) fail("No approved transcript at $file; run ./gradlew :$module:updateTranscripts")
        val approved = Files.readString(file)
        assertEquals(
            approved,
            transcript,
            "$file differs from what $adapter answers; review the diff, then ./gradlew :$module:updateTranscripts if the change is wanted",
        )
    }

    /** The transcript of one exchange, normalised, and a note on how the body is shown. */
    private fun exchange(
        case: WireCase,
        port: Int,
    ): Pair<String, String> {
        val body = case.body?.toByteArray(Charsets.UTF_8)
        val requestHeaders =
            buildList {
                add("Host" to "127.0.0.1:$port")
                add("Accept" to ACCEPT)
                add("Datastar-Request" to "true")
                addAll(case.headers)
                body?.let { add("Content-Length" to it.size.toString()) }
            }
        val request =
            buildString {
                append("${case.method} ${case.path} HTTP/1.1\r\n")
                requestHeaders.forEach { (name, value) -> append("$name: $value\r\n") }
                append("\r\n")
            }
        val raw =
            Socket("127.0.0.1", port).use { socket ->
                socket.soTimeout = 30_000
                socket.getOutputStream().write(request.toByteArray(Charsets.ISO_8859_1) + (body ?: ByteArray(0)))
                readAnswer(socket)
            }
        val end = indexOf(raw, "\r\n\r\n".toByteArray())
        check(end >= 0) { "No headers in the answer to ${case.name}:\n${String(raw, Charsets.ISO_8859_1)}" }
        val head = String(raw, 0, end, Charsets.ISO_8859_1)
        var responseBody = raw.copyOfRange(end + 4, raw.size)
        val notes = mutableListOf<String>()
        if (head.lines().any { it.equals("Transfer-Encoding: chunked", ignoreCase = true) }) {
            responseBody = dechunked(responseBody)
            notes += "chunked on the wire, shown without the chunk framing"
        }
        if (head.lines().any { it.equals("Content-Encoding: gzip", ignoreCase = true) }) {
            responseBody = GZIPInputStream(ByteArrayInputStream(responseBody)).readBytes()
            notes += "gzip on the wire, shown decompressed"
        }
        val text =
            buildString {
                append(request.replace("\r\n", "\n"))
                case.body?.let { if (it.isNotEmpty()) append(it).append("\n\n") }
                append(head.lines().joinToString("\n", transform = ::normalised)).append("\n\n")
                val shown = String(responseBody, Charsets.UTF_8)
                append(shown)
                if (shown.isNotEmpty() && !shown.endsWith("\n")) append("\n")
            }
        return normalised(text, head, port) to
            (notes.takeIf { it.isNotEmpty() }?.joinToString("; ") ?: if (responseBody.isEmpty()) "none" else "as sent")
    }

    /** The whole answer by its own framing, or what came before the server closed the connection. */
    private fun readAnswer(socket: Socket): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        try {
            while (!whole(out.toByteArray())) {
                val n = socket.getInputStream().read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
            }
        } catch (_: SocketTimeoutException) {
            fail("The answer neither ended nor was the connection closed within 30 s:\n${out.toString(Charsets.ISO_8859_1)}")
        }
        return out.toByteArray()
    }

    private fun whole(answer: ByteArray): Boolean {
        val end = indexOf(answer, "\r\n\r\n".toByteArray())
        if (end < 0) return false
        val head = String(answer, 0, end, Charsets.ISO_8859_1).lines()
        val status = head.first().split(' ').getOrNull(1)
        if (status == "204" || status == "304") return true
        val bodyLength = answer.size - end - 4
        val length =
            head
                .firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
                ?.substringAfter(':')
                ?.trim()
                ?.toInt()
        return when {
            length != null -> bodyLength >= length
            head.any { it.equals("Transfer-Encoding: chunked", ignoreCase = true) } -> endsWith(answer, "\r\n0\r\n\r\n".toByteArray())
            else -> false
        }
    }

    private fun dechunked(body: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        var at = 0
        while (true) {
            val lineEnd = indexOf(body, "\r\n".toByteArray(), at)
            if (lineEnd < 0) break
            val size = String(body, at, lineEnd - at, Charsets.ISO_8859_1).substringBefore(';').trim().toInt(16)
            if (size == 0) break
            out.write(body, lineEnd + 2, size)
            at = lineEnd + 2 + size + 2
        }
        return out.toByteArray()
    }

    private val volatileHeader = Regex("""^(Date|Server|Keep-Alive):\s*.*$""", RegexOption.IGNORE_CASE)

    private fun normalised(headerLine: String): String =
        volatileHeader.matchEntire(headerLine)?.let { "${it.groupValues[1]}: {${it.groupValues[1].lowercase()}}" } ?: headerLine

    private fun normalised(
        text: String,
        head: String,
        port: Int,
    ): String {
        var result = text.replace("127.0.0.1:$port", "127.0.0.1:{port}").replace("localhost:$port", "localhost:{port}")
        Regex("""'nonce-([^']+)'""")
            .find(head)
            ?.groupValues
            ?.get(1)
            ?.let { nonce -> result = result.replace(nonce, "{nonce}") }
        return result
    }

    private fun indexOf(
        bytes: ByteArray,
        pattern: ByteArray,
        from: Int = 0,
    ): Int {
        outer@ for (i in from..bytes.size - pattern.size) {
            for (j in pattern.indices) if (bytes[i + j] != pattern[j]) continue@outer
            return i
        }
        return -1
    }

    private fun endsWith(
        bytes: ByteArray,
        pattern: ByteArray,
    ): Boolean = bytes.size >= pattern.size && indexOf(bytes, pattern, bytes.size - pattern.size) >= 0
}
