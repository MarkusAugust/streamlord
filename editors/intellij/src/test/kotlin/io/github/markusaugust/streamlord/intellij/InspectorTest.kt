package io.github.markusaugust.streamlord.intellij

import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.UIUtil
import com.sun.net.httpserver.HttpServer
import io.github.markusaugust.streamlord.analysis.DatastarFrame
import io.github.markusaugust.streamlord.analysis.Requests
import io.github.markusaugust.streamlord.analysis.RunningServer
import io.github.markusaugust.streamlord.analysis.SavedRequest
import io.github.markusaugust.streamlord.core.json.JsonParser
import io.github.markusaugust.streamlord.intellij.inspector.InspectorPanel
import io.github.markusaugust.streamlord.intellij.inspector.InspectorState
import io.github.markusaugust.streamlord.intellij.inspector.RequestStore
import io.github.markusaugust.streamlord.intellij.inspector.RunningServers
import io.github.markusaugust.streamlord.intellij.inspector.StreamClient
import io.github.markusaugust.streamlord.intellij.settings.StreamlordSettings
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.ListCellRenderer
import kotlin.test.assertFailsWith

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
            seen += "accept " + exchange.requestHeaders.getFirst("Accept")
            exchange.responseHeaders.add("Content-Type", "text/html")
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
            assertEquals("accept text/event-stream, text/html, application/json", seen.last())

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
                val data = Regex("""--data-raw '((?:[^']|'\\'')*)'$""").find(line)?.groupValues?.get(1) ?: ""
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
            val (blank, notJson) = send(SavedRequest("", "$base/api/feed", "POST", "\u00a0", ""))
            assertNull(blank)
            assertTrue(notJson, notJson!!.startsWith("Signals are not valid JSON: "))
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

        val baseUrl = store.environment().vars.single()
        assertEquals("http://localhost:8080", baseUrl.value)
    }

    fun `test variables come from the env file, which is judged and extended`() {
        val store = RequestStore.getInstance(project)
        assertEquals(
            RequestStore.Environment(
                listOf(Requests.Variable("baseUrl", "http://localhost:8080", Requests.VariableSource.DEFAULT)),
                emptyList(),
            ),
            store.environment(),
        )
        val (file, extended) = store.defineVariables(listOf("signals"))
        try {
            assertTrue(extended)
            assertTrue(file.path.endsWith("/.streamlord/env.json"))
            assertEquals("{\n  \"baseUrl\": \"http://localhost:8080\",\n  \"signals\": {}\n}\n", String(file.contentsToByteArray()))
            assertEquals(
                listOf(
                    Requests.Variable("baseUrl", "http://localhost:8080", Requests.VariableSource.ENV),
                    Requests.Variable("signals", "{}", Requests.VariableSource.ENV),
                ),
                store.environment().vars,
            )
            WriteCommandAction.runWriteCommandAction(project) { VfsUtil.saveText(file, "{\"csrf\": \"x\"}") }
            assertEquals(
                RequestStore.Environment(
                    listOf(Requests.Variable("baseUrl", "http://localhost:8080", Requests.VariableSource.DEFAULT)),
                    listOf(".streamlord/env.json is not valid: \"csrf\" is not a known key. Use baseUrl, signals, headers or params."),
                ),
                store.environment(),
            )
        } finally {
            WriteCommandAction.runWriteCommandAction(project) { file.delete(this) }
        }
    }

    fun `test variables follow what an open editor holds, saved or not`() {
        val store = RequestStore.getInstance(project)
        val (file, _) = store.defineVariables(emptyList())
        val documents = FileDocumentManager.getInstance()
        try {
            val document = documents.getDocument(file)!!
            WriteCommandAction.runWriteCommandAction(project) { document.setText("{\"baseUrl\": \"http://unsaved:2\"}") }
            val baseUrl = store.environment().vars.single()
            assertEquals("http://unsaved:2", baseUrl.value)
            store.defineVariables(listOf("signals"))
            assertEquals("{\"baseUrl\": \"http://unsaved:2\",\n  \"signals\": {}\n}", document.text)
            assertEquals("{\n  \"baseUrl\": \"http://localhost:8080\"\n}\n", String(file.contentsToByteArray()))
        } finally {
            documents.saveAllDocuments()
            WriteCommandAction.runWriteCommandAction(project) { file.delete(this) }
        }
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

    /** A process the test writes the output of, as a run configuration's would arrive. */
    private class FakeProcess : ProcessHandler() {
        override fun destroyProcessImpl() = notifyProcessTerminated(0)

        override fun detachProcessImpl() = notifyProcessDetached()

        override fun detachIsDefault() = false

        override fun getProcessInput(): java.io.OutputStream? = null

        fun print(text: String) = notifyTextAvailable(text, ProcessOutputTypes.STDOUT)
    }

    fun `test a server the project starts says where it listens and what went wrong`() {
        val servers = RunningServers()
        var changes = 0
        servers.onChange(testRootDisposable) { changes++ }
        val process = FakeProcess()
        servers.track("Motregning back (dev)", process)
        process.startNotify()
        process.print("\u001B[32m INFO\u001B[0;39m Starting Application\n2026-10-06 INFO Tomcat started on port 91")
        assertEquals(null, servers.current())
        process.print("02 (http) with context path '/'\n")
        assertEquals(RunningServer("http://localhost:9102", "Motregning back (dev)"), servers.current())
        val since = System.currentTimeMillis()
        process.print("INFO all is well\n\u001B[33m WARN\u001B[0;39m Resolved [MissingServletRequestParameterException]\n")
        assertEquals(listOf(" WARN Resolved [MissingServletRequestParameterException]"), servers.problemsSince(since))
        process.destroyProcess()
        process.waitFor()
        assertEquals(null, servers.current())
        assertEquals(2, changes)
    }

    fun `test a running server is the baseUrl unless the env file sets one`() {
        val process = FakeProcess()
        RunningServers.getInstance(project).track("Motregning back (dev)", process)
        process.startNotify()
        process.print("Tomcat started on port 9102 (http) with context path '/'\n")
        val store = RequestStore.getInstance(project)
        try {
            assertEquals(
                Requests.Variable("baseUrl", "http://localhost:9102", Requests.VariableSource.RUNNING, "Motregning back (dev)"),
                store.environment().vars.single(),
            )
            val (file, changed) = store.useBaseUrl("http://localhost:9103")
            assertTrue(changed)
            FileDocumentManager.getInstance().saveAllDocuments()
            assertEquals("{\n  \"baseUrl\": \"http://localhost:9103\"\n}\n", String(file.contentsToByteArray()))
            assertEquals(
                Requests.VariableSource.ENV,
                store
                    .environment()
                    .vars
                    .single()
                    .source,
            )
            WriteCommandAction.runWriteCommandAction(project) { file.delete(this) }
        } finally {
            process.destroyProcess()
            process.waitFor()
        }
    }

    fun `test the client tries the IPv6 loopback when localhost refuses`() {
        assertEquals("http://[::1]:8080/x", StreamClient.ipv6Loopback("http://localhost:8080/x"))
        assertEquals("https://u:p@[::1]/x?localhost", StreamClient.ipv6Loopback("https://u:p@LOCALHOST/x?localhost"))
        assertEquals(null, StreamClient.ipv6Loopback("http://localhost.example.com/x"))
        assertEquals(null, StreamClient.ipv6Loopback("http://127.0.0.1:8080/x"))

        val server =
            try {
                HttpServer.create(InetSocketAddress(java.net.InetAddress.getByName("::1"), 0), 0)
            } catch (_: java.io.IOException) {
                return // No IPv6 loopback on this machine, so nothing to fall back to.
            }
        server.createContext("/v6") { exchange ->
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.use { it.write("event: datastar-patch-signals\ndata: signals {\"v6\":true}\n\n".toByteArray()) }
        }
        server.start()
        try {
            val frames = CopyOnWriteArrayList<DatastarFrame>()
            val errors = CopyOnWriteArrayList<String>()
            val done = CountDownLatch(1)
            StreamClient().open(
                "http://localhost:${server.address.port}/v6",
                "GET",
                "{}",
                emptyMap(),
                object : StreamClient.Handlers {
                    override fun onFrame(frame: DatastarFrame) {
                        frames += frame
                    }

                    override fun onError(message: String) {
                        errors += message
                    }

                    override fun onStatus(
                        status: StreamClient.Status,
                        http: String?,
                        contentType: String?,
                    ) {
                        if (status == StreamClient.Status.CLOSED) done.countDown()
                    }
                },
            )
            assertTrue(done.await(10, TimeUnit.SECONDS))
            assertEquals(emptyList<String>(), errors)
            assertEquals(listOf("{\"v6\":true}"), frames.map { it.args["signals"] })
        } finally {
            server.stop(0)
        }
    }

    /**
     * A connection hands over bytes in whatever pieces it likes, so a character can arrive in two
     * reads, and the server may send a byte that is not UTF-8 at all. The browser's decoder keeps
     * the one and replaces the other; the stream carries on either way.
     */
    fun `test a character split between reads, and a byte that is not UTF-8, reach the frame`() {
        val bytes =
            "event: datastar-patch-elements\ndata: elements <p>blåbær \uD83E\uDED0</p>\n\n".toByteArray() +
                "event: datastar-patch-signals\ndata: signals {\"s\":\"".toByteArray() +
                byteArrayOf(0xFF.toByte()) +
                "\"}\n\n".toByteArray()
        val frames = pumped(bytes)
        assertEquals(listOf("datastar-patch-elements", "datastar-patch-signals"), frames.map { it.event })
        assertEquals("<p>blåbær \uD83E\uDED0</p>", frames[0].args["elements"])
        assertEquals("{\"s\":\"\uFFFD\"}", frames[1].args["signals"])
    }

    /** A byte order mark opens the body, not the first field's name. */
    fun `test a byte order mark at the start of the stream is not part of the event name`() {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val bytes = bom + "event: datastar-patch-signals\ndata: signals {}\n\n".toByteArray()
        assertEquals(listOf("datastar-patch-signals"), pumped(bytes).map { it.event })
    }

    /** By status and Content-Type, as the client reads a response, not by what the body looks like. */
    fun `test a response is read as a stream as the client reads it`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/no-type") { exchange ->
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.use { it.write("data: x\n\n".toByteArray()) }
        }
        server.createContext("/missing") { exchange ->
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(404, 0)
            exchange.responseBody.use { it.write("data: x\n\n".toByteArray()) }
        }
        server.createContext("/big") { exchange ->
            exchange.responseHeaders.add("Content-Type", "text/html")
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.use { out -> repeat(2048) { out.write(ByteArray(1024) { 'x'.code.toByte() }) } }
        }
        server.createContext("/exact") { exchange ->
            exchange.responseHeaders.add("Content-Type", "text/html")
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.use { out -> repeat(1024) { out.write(ByteArray(1024) { 'x'.code.toByte() }) } }
        }
        // Two values: fetch joins them, so the second makes this HTML and not a stream.
        server.createContext("/two-types") { exchange ->
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.responseHeaders.add("Content-Type", "text/html")
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.use { it.write("data: x\n\n".toByteArray()) }
        }
        server.start()
        try {
            val base = "http://127.0.0.1:${server.address.port}"

            fun open(path: String): Pair<List<DatastarFrame>, StreamClient.NonSseResponse?> {
                val frames = CopyOnWriteArrayList<DatastarFrame>()
                val nonSse =
                    java.util.concurrent.atomic
                        .AtomicReference<StreamClient.NonSseResponse>()
                val done = CountDownLatch(1)
                StreamClient().open(
                    "$base$path",
                    "GET",
                    "{}",
                    emptyMap(),
                    object : StreamClient.Handlers {
                        override fun onStatus(
                            status: StreamClient.Status,
                            http: String?,
                            contentType: String?,
                        ) {
                            if (status == StreamClient.Status.CLOSED) done.countDown()
                        }

                        override fun onFrame(frame: DatastarFrame) {
                            frames += frame
                        }

                        override fun onNonSse(response: StreamClient.NonSseResponse) = nonSse.set(response)
                    },
                )
                assertTrue(done.await(10, TimeUnit.SECONDS))
                return frames to nonSse.get()
            }
            assertEquals(listOf("message"), open("/no-type").first.map { it.event })
            val (missingFrames, missing) = open("/missing")
            assertEquals(emptyList<DatastarFrame>(), missingFrames)
            assertTrue(missing!!.http.startsWith("404"))
            assertEquals("Not read: the client reads no body with a 404, and a stream may never end.", missing.body)
            assertEquals("x".repeat(Requests.NON_STREAM_LIMIT) + Requests.NON_STREAM_CUT, open("/big").second!!.body)
            assertEquals("x".repeat(Requests.NON_STREAM_LIMIT), open("/exact").second!!.body)
            val (twoFrames, two) = open("/two-types")
            assertEquals(emptyList<DatastarFrame>(), twoFrames)
            assertEquals("text/event-stream, text/html", two!!.contentType)
        } finally {
            server.stop(0)
        }
    }

    /** Comments after the last message come back when the stream ends, through the parser's finish. */
    fun `test the comments that end a stream after its last message are shown`() {
        val comments = CopyOnWriteArrayList<String>()
        StreamClient.pump(
            "data: x\n\n: bye\n".byteInputStream(),
            object : StreamClient.Handlers {
                override fun onComment(text: String) {
                    comments += text
                }
            },
            java.util.concurrent.atomic
                .AtomicBoolean(false),
        )
        assertEquals(listOf("bye"), comments)
    }

    /** Every frame [StreamClient.pump] reads from [bytes], handed over one byte per read. */
    private fun pumped(bytes: ByteArray): List<DatastarFrame> {
        val trickle =
            object : java.io.InputStream() {
                private var next = 0

                override fun read(): Int = if (next < bytes.size) bytes[next++].toInt() and 0xFF else -1

                override fun read(
                    b: ByteArray,
                    off: Int,
                    len: Int,
                ): Int {
                    if (len == 0) return 0
                    val c = read()
                    if (c < 0) return -1
                    b[off] = c.toByte()
                    return 1
                }
            }
        val frames = CopyOnWriteArrayList<DatastarFrame>()
        StreamClient.pump(
            trickle,
            object : StreamClient.Handlers {
                override fun onFrame(frame: DatastarFrame) {
                    frames += frame
                }
            },
            java.util.concurrent.atomic
                .AtomicBoolean(false),
        )
        return frames
    }

    fun `test a save over a requests file that is not JSON is refused, so nothing in it is lost`() {
        val store = RequestStore.getInstance(project)
        store.save(SavedRequest("a", "/a", "GET", "", ""))
        val file = store.requestsFile()!!
        // A byte order mark and a trailing comma: the file is not JSON, and would read as empty.
        val broken = "\uFEFF" + String(file.contentsToByteArray()).replace("}\n  ]", "},\n  ]")
        WriteCommandAction.runWriteCommandAction(project) { VfsUtil.saveText(file, broken) }
        try {
            val read = store.read()
            assertEquals(emptyList<SavedRequest>(), read.requests)
            assertTrue(read.error!!, read.error!!.startsWith(".streamlord/inspector.json is not valid JSON"))
            val e = assertFailsWith<IllegalStateException> { store.save(SavedRequest("b", "/b", "GET", "", "")) }
            assertTrue(e.message!!, "not lost" in e.message!!)
            assertEquals(broken, String(file.contentsToByteArray()))
        } finally {
            // The project is shared with the other tests, which read this file.
            WriteCommandAction.runWriteCommandAction(project) { VfsUtil.saveText(file, "{\"version\": 1, \"requests\": []}\n") }
        }
    }

    fun `test a requests file path that leaves the project falls back to the default, and a save says so`() {
        val store = RequestStore.getInstance(project)
        val settings = StreamlordSettings.getInstance(project)
        settings.state.requestsFile = "../elsewhere/inspector.json"
        try {
            assertEquals(Requests.REQUESTS_FILE, store.requestsPath())
            val e = assertFailsWith<IllegalStateException> { store.save(SavedRequest("a", "/a", "GET", "", "")) }
            assertTrue(e.message!!, "leaves the project" in e.message!!)
        } finally {
            settings.state.requestsFile = Requests.REQUESTS_FILE
        }
    }

    // Swing renders a label whose text starts with <html> as HTML and fetches its images; an event name or a saved name must not.
    fun `test the panel shows server text and shared names as text, never as Swing HTML`() {
        val store = RequestStore.getInstance(project)
        store.save(SavedRequest("<html><b>x</b><img src='http://127.0.0.1:1/beacon'>", "/a", "GET", "", ""))
        val panel = InspectorPanel(project, testRootDisposable)
        panel.pushRequests(null)
        val frame = DatastarFrame("<html><b>forged</b><img src='http://127.0.0.1:1/b'>", "1", null, mapOf("elements" to "<p>x</p>"), "", 0L)
        panel.javaClass
            .getDeclaredMethod("addFrame", DatastarFrame::class.java)
            .apply { isAccessible = true }
            .invoke(panel, frame)
        panel.javaClass
            .getDeclaredMethod(
                "addComment",
                String::class.java,
            ).apply { isAccessible = true }
            .invoke(panel, "<html><img src='x'>")
        // The request list, not the method list beside it.
        val list =
            UIUtil.findComponentsOfType(panel, JComboBox::class.java).first { combo ->
                (0 until combo.itemCount).any { combo.getItemAt(it).toString().startsWith("<html>") }
            }

        @Suppress("UNCHECKED_CAST")
        val renderer = list.renderer as ListCellRenderer<Any>
        // One component renders every entry in turn, so what it shows is read as each is rendered.
        val rendered =
            (0 until list.itemCount).map {
                val c = renderer.getListCellRendererComponent(JList<Any>(), list.getItemAt(it), it, false, false) as JLabel
                c.text to c.getClientProperty("html")
            }
        assertTrue(rendered.any { it.first.startsWith("<html>") })
        for ((text, html) in rendered) assertNull(text, html)
        val labels = UIUtil.findComponentsOfType(panel, JLabel::class.java)
        assertTrue(labels.any { it.text.startsWith("<html>") })
        for (c in labels) assertNull(c.text, c.getClientProperty("html"))
    }

    fun `test the signal store follows the client, leaf by leaf and reading the flag as the client reads it`() {
        val panel = InspectorPanel(project, testRootDisposable)
        val apply = panel.javaClass.getDeclaredMethod("applyFrame", DatastarFrame::class.java).apply { isAccessible = true }

        fun patch(
            signals: String,
            onlyIfMissing: String? = null,
        ) {
            val args = listOfNotNull("signals" to signals, onlyIfMissing?.let { "onlyIfMissing" to it }).toMap()
            apply.invoke(panel, DatastarFrame("datastar-patch-signals", null, null, args, "", 0L))
        }
        patch("""{"user":{"name":"a"},"list":[1,2]}""")
        patch("""{"user":{"name":"b","age":1},"list":{"0":9,"2":3}}""", "true ")
        val store = UIUtil.findComponentsOfType(panel, JBTextArea::class.java).single { it.text.startsWith("{\n") }
        assertEquals("""{"user":{"name":"a","age":1},"list":[1,2,3]}""", JsonParser.parse(store.text).toJson())
    }

    fun `test a patch that fails partway leaves the keys before it in the store, as the client does`() {
        val panel = InspectorPanel(project, testRootDisposable)
        val apply = panel.javaClass.getDeclaredMethod("applyFrame", DatastarFrame::class.java).apply { isAccessible = true }

        fun patch(signals: String) =
            apply.invoke(panel, DatastarFrame("datastar-patch-signals", null, null, mapOf("signals" to signals), "", 0L))
        patch("""{"a":[1,2]}""")
        val store = UIUtil.findComponentsOfType(panel, JBTextArea::class.java).single { it.text.startsWith("{\n") }
        patch("""{"b":1,"a":{"0":7,"length":-1},"c":2}""")
        assertEquals("""{"a":[7,2],"b":1}""", JsonParser.parse(store.text).toJson())
        val labels = UIUtil.findComponentsOfType(panel, JLabel::class.java).map { it.text }
        // The client leaves its batch open after a throw, so the bindings on the page stop
        // redrawing (measured in Chrome).
        val expected =
            "The signal store cannot take this patch: Invalid array length -1 in a signal patch. " +
                "Datastar's client throws on it too, and the bindings already on the page then stop redrawing until it is reloaded, " +
                "though the signals still change, element patches still land and new bindings show the current value."
        assertTrue(labels.toString(), expected in labels)
        patch("""{"d":3}""")
        assertEquals("""{"a":[7,2],"b":1,"d":3}""", JsonParser.parse(store.text).toJson())
    }

    // A patch Streamlord refuses on purpose says so, and nothing about the page standing still.
    fun `test a patch Streamlord refuses on purpose says so`() {
        val panel = InspectorPanel(project, testRootDisposable)
        val apply = panel.javaClass.getDeclaredMethod("applyFrame", DatastarFrame::class.java).apply { isAccessible = true }

        fun patch(signals: String) =
            apply.invoke(panel, DatastarFrame("datastar-patch-signals", null, null, mapOf("signals" to signals), "", 0L))

        fun labels() = UIUtil.findComponentsOfType(panel, JLabel::class.java).map { it.text }
        patch("""{"a":[1]}""")
        val store = UIUtil.findComponentsOfType(panel, JBTextArea::class.java).single { it.text.startsWith("{\n") }
        patch("""{"b":1,"o":{"__proto__":5},"c":2}""")
        assertEquals("""{"a":[1],"b":1,"o":{}}""", JsonParser.parse(store.text).toJson())
        val proto =
            "Streamlord refuses this signal patch on purpose: A key named __proto__ gives the object holding it a foreign prototype " +
                "in Datastar's client, which then throws on some later keys, replaces the object on a later patch, or with a second " +
                "__proto__ writes into String.prototype; Streamlord does not model it and refuses the patch. " +
                "The store shown keeps the keys before it."
        assertTrue(labels().toString(), proto in labels())
        patch("""{"a":{"20000":1}}""")
        val holes =
            "Streamlord refuses this signal patch on purpose: Signal patch index 20000 is more than 10000 past the end of an array " +
                "of 1: the client takes it, and Streamlord does not fill memory with the holes. The store shown keeps the keys before it."
        assertTrue(labels().toString(), holes in labels())
        assertEquals("""{"a":[1],"b":1,"o":{}}""", JsonParser.parse(store.text).toJson())
    }

    // Measured in headless Chrome with the 1.0.4 bundle: the characters that may stand on both sides
    // of `true` in onlyIfMissing and still make it true.
    fun `test onlyIfMissing is trimmed as JavaScript trims it`() {
        val measured =
            "0009 000a 000b 000c 000d 0020 00a0 1680 2000 2001 2002 2003 2004 2005 2006 2007 2008 2009 200a 2028 2029 202f 205f 3000 feff"
        val kept = (0..0xFFFF).map { it.toChar() }.filter { InspectorPanel.isStringTrue("${it}true$it") }
        assertEquals(measured, kept.joinToString(" ") { "%04x".format(it.code) })
        assertFalse(InspectorPanel.isStringTrue("TRUE"))
        assertFalse(InspectorPanel.isStringTrue(null))

        val panel = InspectorPanel(project, testRootDisposable)
        val apply = panel.javaClass.getDeclaredMethod("applyFrame", DatastarFrame::class.java).apply { isAccessible = true }

        fun patch(
            signals: String,
            onlyIfMissing: String,
        ) {
            val args = mapOf("signals" to signals, "onlyIfMissing" to onlyIfMissing)
            apply.invoke(panel, DatastarFrame("datastar-patch-signals", null, null, args, "", 0L))
        }
        patch("""{"a":1}""", "")
        patch("""{"a":2,"b":2}""", "\uFEFFtrue\u2028")
        patch("""{"b":3,"c":3}""", "\u001Ctrue")
        val store = UIUtil.findComponentsOfType(panel, JBTextArea::class.java).single { it.text.startsWith("{\n") }
        assertEquals("""{"a":1,"b":3,"c":3}""", JsonParser.parse(store.text).toJson())
    }
}
