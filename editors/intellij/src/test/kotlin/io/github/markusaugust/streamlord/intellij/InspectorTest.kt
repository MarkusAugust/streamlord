package io.github.markusaugust.streamlord.intellij

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.sun.net.httpserver.HttpServer
import io.github.markusaugust.streamlord.analysis.DatastarFrame
import io.github.markusaugust.streamlord.analysis.Requests
import io.github.markusaugust.streamlord.analysis.SavedRequest
import io.github.markusaugust.streamlord.intellij.inspector.InspectorState
import io.github.markusaugust.streamlord.intellij.inspector.RequestStore
import io.github.markusaugust.streamlord.intellij.inspector.StreamClient
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class InspectorTest : BasePlatformTestCase() {
    fun `test the client reads a datastar stream as the browser would`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val seen = CopyOnWriteArrayList<String>()
        server.createContext("/api/feed") { exchange ->
            seen +=
                exchange.requestMethod + " " + exchange.requestURI.toString() + " " + exchange.requestHeaders.getFirst("Datastar-Request")
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.use { out ->
                out.write(
                    "event: datastar-patch-elements\nid: 7\ndata: selector #a\ndata: elements <div>\ndata: elements </div>\n\n"
                        .toByteArray(),
                )
                out.write(": ping\n\n".toByteArray())
                out.write("event: datastar-patch-signals\ndata: signals {\"n\":1}\n\n".toByteArray())
            }
        }
        server.createContext("/plain") { exchange ->
            exchange.responseHeaders.add("Content-Type", "text/plain")
            exchange.responseHeaders.add("datastar-selector", "#x")
            val body = "hello".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            val frames = CopyOnWriteArrayList<DatastarFrame>()
            val comments = CopyOnWriteArrayList<String>()
            val statuses = CopyOnWriteArrayList<StreamClient.Status>()
            val done = CountDownLatch(1)
            val client = StreamClient()
            client.open(
                "$base/api/feed",
                "GET",
                "{\"q\":\"a b\"}",
                mapOf("X-A" to "1"),
                object : StreamClient.Handlers {
                    override fun onStatus(
                        status: StreamClient.Status,
                        http: String?,
                        contentType: String?,
                    ) {
                        statuses += status
                        if (status == StreamClient.Status.CLOSED) done.countDown()
                    }

                    override fun onFrame(frame: DatastarFrame) {
                        frames += frame
                    }

                    override fun onComment(text: String) {
                        comments += text
                    }

                    override fun onError(message: String) = fail(message)
                },
            )
            assertTrue(done.await(10, TimeUnit.SECONDS))
            assertEquals(listOf(StreamClient.Status.CONNECTING, StreamClient.Status.OPEN, StreamClient.Status.CLOSED), statuses)
            assertEquals("GET /api/feed?datastar=%7B%22q%22%3A%22a+b%22%7D true", seen.single())
            assertEquals(2, frames.size)
            assertEquals(mapOf("selector" to "#a", "elements" to "<div>\n</div>"), frames[0].args)
            assertEquals("7", frames[0].id)
            assertEquals(listOf("ping"), comments)
            assertEquals("{\"n\":1}", frames[1].args["signals"])

            val nonSse = CountDownLatch(1)
            val plain =
                java.util.concurrent.atomic
                    .AtomicReference<StreamClient.NonSseResponse>()
            client.open(
                "$base/plain",
                "POST",
                "{}",
                emptyMap(),
                object : StreamClient.Handlers {
                    override fun onNonSse(response: StreamClient.NonSseResponse) {
                        plain.set(response)
                        nonSse.countDown()
                    }
                },
            )
            assertTrue(nonSse.await(10, TimeUnit.SECONDS))
            assertEquals("hello", plain.get().body)
            assertEquals("200 OK", plain.get().http)
            assertEquals(mapOf("datastar-selector" to "#x"), plain.get().headers)

            val failed = CountDownLatch(1)
            var error: String? = null
            client.open(
                "http://127.0.0.1:1/x",
                "GET",
                "{}",
                emptyMap(),
                object : StreamClient.Handlers {
                    override fun onError(message: String) {
                        error = message
                        failed.countDown()
                    }
                },
            )
            assertTrue(failed.await(10, TimeUnit.SECONDS))
            assertEquals("Connection refused at 127.0.0.1:1. Is the server running?", error)
        } finally {
            server.stop(0)
        }
    }

    /** The cases of the VS Code extension's `streamClient.test.ts`: what arrives is what the curl line shows. */
    fun `test the client sends what the curl line shows`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val seen = CopyOnWriteArrayList<Pair<String, String>>()
        server.createContext("/") { exchange ->
            val uri = exchange.requestURI
            val body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
            seen += (uri.rawPath + (uri.rawQuery?.let { "?$it" } ?: "")) to body
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.use { it.write("event: datastar-patch-signals\ndata: signals {}\n\n".toByteArray()) }
        }
        server.start()
        try {
            val base = "http://127.0.0.1:${server.address.port}"

            fun send(r: SavedRequest): Pair<Pair<String, String>?, String?> {
                val before = seen.size
                val done = CountDownLatch(1)
                var error: String? = null
                StreamClient().open(
                    r.url,
                    r.method,
                    r.signals,
                    Requests.parseHeaderLines(r.headers),
                    object : StreamClient.Handlers {
                        override fun onStatus(
                            status: StreamClient.Status,
                            http: String?,
                            contentType: String?,
                        ) {
                            if (status == StreamClient.Status.CLOSED) done.countDown()
                        }

                        override fun onError(message: String) {
                            error = message
                            done.countDown()
                        }
                    },
                )
                assertTrue(done.await(10, TimeUnit.SECONDS))
                return seen.getOrNull(before) to error
            }

            fun curlParts(line: String): Pair<String, String> {
                val url = Regex("""^curl -N -X \S+ '([^']*)'""").find(line)!!.groupValues[1]
                val data = Regex("""--data '((?:[^']|'\\'')*)'$""").find(line)?.groupValues?.get(1) ?: ""
                return url.removePrefix(base) to data.replace("'\\''", "'")
            }

            val signals = " { \"n\" : 1.0, \"s\": \"\\u00e9 x\" } "
            for (r in listOf(
                SavedRequest("", "$base/api/feed?flag&q=a%20b+c&datastar=old", "GET", signals, "X-A: 1"),
                SavedRequest("", "$base/api/feed?q=1", "POST", signals, ""),
                SavedRequest("", "$base/api/feed", "DELETE", "", ""),
            )) {
                val (got, error) = send(r)
                assertNull(error)
                assertEquals(r.method, curlParts(Requests.toCurl(r)), got)
            }

            assertEquals("/s%C3%B8k", send(SavedRequest("", "$base/s\u00f8k", "POST", "", "")).first?.first)
            assertEquals(
                null to "Not an absolute URL: /api/feed. Start it with http:// or https://, or with {{baseUrl}}.",
                send(SavedRequest("", "/api/feed", "GET", "", "")),
            )
            assertEquals(
                null to "The URL still holds {id}. Fill in the path parameter before sending.",
                send(SavedRequest("", "$base/users/{id}", "GET", "", "")),
            )
            val (_, restricted) = send(SavedRequest("", "$base/api/feed", "GET", "", "Host: elsewhere"))
            assertTrue(restricted, restricted!!.startsWith("Cannot send the request: restricted header name"))
        } finally {
            server.stop(0)
        }
    }

    fun `test saved requests live in the project and recent ones in the workspace`() {
        val store = RequestStore.getInstance(project)
        assertEquals(emptyList<SavedRequest>(), store.saved())
        store.save(SavedRequest("counter", "{{baseUrl}}/api/counter", "GET", "", ""))
        store.save(SavedRequest("Add", "{{baseUrl}}/api/add", "POST", "{\"n\": 1}", "X-A: 1"))
        assertEquals(listOf("Add", "counter"), store.saved().map { it.name })
        val file = store.requestsFile()!!
        assertTrue(file.path.endsWith("/.streamlord/inspector.json"))
        val text = String(file.contentsToByteArray())
        assertTrue(text, text.contains("\"version\": 1"))
        store.delete("COUNTER")
        assertEquals(listOf("Add"), store.saved().map { it.name })

        val state = InspectorState.getInstance(project)
        state.addRecent(SavedRequest("", "/a", "GET", "", ""))
        state.addRecent(SavedRequest("", "/b", "GET", "", ""))
        assertEquals(listOf("GET /b", "GET /a"), state.recent().map { it.name })
        assertEquals("/b", state.lastUsed()?.url)

        assertEquals("http://localhost:8080", store.variables()["baseUrl"])
    }

    fun `test routes get a gutter marker`() {
        myFixture.configureByText(
            "Api.kt",
            """
            fun Route.api() {
              route("/api") {
                get("/counter") { }
              }
            }
            @RequestMapping("/x")
            class C {
              @PostMapping("/y")
              fun y() = 1
            }
            """.trimIndent(),
        )
        myFixture.doHighlighting()
        val markers =
            com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerImpl
                .getLineMarkers(myFixture.editor.document, project)
        val texts = markers.mapNotNull { it.lineMarkerTooltip }.sorted()
        assertEquals(listOf("Open in Stream Inspector · GET /api/counter", "Open in Stream Inspector · POST /x/y"), texts)
    }
}
