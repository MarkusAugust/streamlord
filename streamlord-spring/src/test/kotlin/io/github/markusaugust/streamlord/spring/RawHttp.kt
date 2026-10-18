package io.github.markusaugust.streamlord.spring

import java.io.ByteArrayOutputStream
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * The raw bytes of a GET of [path] over HTTP/1.1, read until the answer is whole by its own framing
 * (its Content-Length, or the last chunk), or until the server closes the connection. A server that
 * does neither within [limitMillis] fails the test: the limit stands for "never", not for how long
 * an answer may take, so a slow machine does not cut an answer short.
 */
fun rawGet(
    port: Int,
    path: String,
    headers: String = "",
    limitMillis: Int = 30_000,
): String =
    Socket("localhost", port).use { socket ->
        socket.soTimeout = limitMillis
        socket.getOutputStream().write("GET $path HTTP/1.1\r\nHost: x\r\n$headers\r\n".toByteArray())
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        try {
            while (!whole(out.toString(Charsets.ISO_8859_1))) {
                val n = socket.getInputStream().read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
            }
        } catch (_: SocketTimeoutException) {
            throw AssertionError("$path neither ended nor closed in $limitMillis ms:\n${out.toString(Charsets.ISO_8859_1)}")
        }
        out.toString(Charsets.ISO_8859_1)
    }

private fun whole(answer: String): Boolean {
    val end = answer.indexOf("\r\n\r\n")
    if (end < 0) return false
    val head = answer.substring(0, end).lowercase()
    val body = answer.substring(end + 4)
    val length =
        Regex("""(?m)^content-length:\s*(\d+)""")
            .find(head)
            ?.groupValues
            ?.get(1)
            ?.toInt()
    return when {
        length != null -> body.length >= length
        "transfer-encoding: chunked" in head -> body == "0\r\n\r\n" || body.endsWith("\r\n0\r\n\r\n")
        else -> false
    }
}
