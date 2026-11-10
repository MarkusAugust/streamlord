package io.github.markusaugust.streamlord.intellij

import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Container
import java.awt.KeyboardFocusManager
import java.awt.Rectangle
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.ListCellRenderer
import javax.swing.Scrollable
import javax.swing.SwingUtilities
import javax.swing.text.JTextComponent
import kotlin.test.assertFailsWith

class InspectorTest : BasePlatformTestCase() {
    /** Where the requests of a test run; ended with the test. */
    private val background = CoroutineScope(SupervisorJob())

    override fun tearDown() {
        try {
            background.cancel()
        } finally {
            super.tearDown()
        }
    }

    private fun RequestStore.save(request: SavedRequest) = apply(saving(request))

    private fun RequestStore.delete(name: String) = apply(deleting(name))

    private fun RequestStore.defineVariables(keys: List<String>) = apply(definingVariables(keys)).let { it.first!! to it.second }

    private fun RequestStore.useBaseUrl(url: String) = apply(usingBaseUrl(url)).let { it.first!! to it.second }

    /** Write [text] to [path] in the project, as a person or another tool would have. */
    private fun write(
        path: String,
        text: String,
    ): VirtualFile =
        WriteCommandAction.writeCommandAction(project).compute<VirtualFile, Throwable> {
            val base = RequestStore.getInstance(project).baseDir()!!
            val dir = VfsUtil.createDirectoryIfMissing(base, path.substringBeforeLast('/'))!!
            val name = path.substringAfterLast('/')
            (dir.findChild(name) ?: dir.createChildData(this, name)).also { VfsUtil.saveText(it, text) }
        }

    // The project is shared with the other tests, which read these files.
    private fun remove(path: String) =
        WriteCommandAction.runWriteCommandAction(project) {
            RequestStore
                .getInstance(project)
                .baseDir()
                ?.findFileByRelativePath(path)
                ?.delete(this)
        }

    private fun entry(
        name: String,
        url: String,
    ) =
        "{\n      \"name\": \"$name\",\n      \"url\": \"$url\",\n      \"method\": \"GET\",\n      \"signals\": \"\",\n      \"headers\": \"\"\n    }"

    /** What the panel tells the user in text that cannot be edited. */
    private fun notes(panel: InspectorPanel) =
        UIUtil.findComponentsOfType(panel, JBTextArea::class.java).filter { !it.isEditable }.map { it.text }

    private fun areas(panel: InspectorPanel) = UIUtil.findComponentsOfType(panel, JBTextArea::class.java).filter { !it.isEditable }

    /** The one area that tells the user exactly [text]. */
    private fun telling(
        panel: InspectorPanel,
        text: String,
    ): JBTextArea {
        val found = areas(panel).filter { it.text == text }
        assertEquals(notes(panel).toString(), 1, found.size)
        return found.single()
    }

    // An area that holds nothing is not shown, so it takes no room in the form.
    private fun assertNoEmptyAreaShows(panel: InspectorPanel) =
        assertEquals(emptyList<String>(), areas(panel).filter { it.text.isEmpty() && it.isVisible }.map { it.toString() })

    private fun aboutTheRequestsFile(text: String) =
        "is not read" in text || "is not a requests file" in text || "is not valid JSON" in text

    /** What the panel says in text components that cannot be edited: an error line is one. */
    private fun texts(panel: InspectorPanel) =
        UIUtil.findComponentsOfType(panel, JTextComponent::class.java).filter { !it.isEditable }.map { it.text }

    /** Give [panel] a size and lay out everything in it, as a window would have. */
    private fun layOut(
        panel: InspectorPanel,
        width: Int,
    ) {
        fun all(c: Container) {
            // A window marks what it lays out valid, and a size that changes then invalidates the layouts above it; here each pass asks anew.
            c.invalidate()
            c.doLayout()
            for (child in c.components) if (child is Container) all(child)
        }
        panel.setSize(width, 700)
        // Wrapped text knows its height only once it has its width, so one pass is not enough.
        repeat(4) { all(panel) }
    }

    private fun boundsIn(
        panel: InspectorPanel,
        c: java.awt.Component,
    ): Rectangle = SwingUtilities.convertRectangle(c.parent, c.bounds, panel)

    /** Whether the panel shows the marker of an edited request: the label and everything it stands in is visible. */
    private fun modifiedShows(panel: InspectorPanel) =
        UIUtil.findComponentsOfType(panel, JBLabel::class.java).any { label ->
            label.text == "● modified" &&
                generateSequence<java.awt.Component>(label) { it.parent }.takeWhile { it !== panel }.all { it.isVisible }
        }

    /**
     * What is out of place in the form of [panel] as it is laid out: a button, a field or the request list that does
     * not lie wholly inside the panel's width, an action button outside the form, and a field under an action button.
     */
    private fun outside(panel: InspectorPanel): List<String> {
        val buttons = UIUtil.findComponentsOfType(panel, JButton::class.java).filter { it.text in FORM_BUTTONS }
        assertEquals(FORM_BUTTONS, buttons.map { it.text }.sorted())
        val url = UIUtil.findComponentsOfType(panel, JBTextField::class.java).single()
        val list = UIUtil.findComponentsOfType(panel, JComboBox::class.java).single { it.getItemAt(0) !is String }
        val areas = UIUtil.findComponentsOfType(form(panel), JBTextArea::class.java).filter { it.isEditable }
        assertEquals(2, areas.size)
        val fields =
            listOf("the URL field" to url, "the signals field" to areas[0], "the headers field" to areas[1])
                .map { (name, c) -> name to (SwingUtilities.getAncestorOfClass(JScrollPane::class.java, c) ?: c) }
        val form = form(panel).bounds
        val actions = buttons.filter { it.text in ACTION_BUTTONS }
        return (buttons.map { it.text to it } + fields + ("the request list" to list)).mapNotNull { (name, c) ->
            val b = boundsIn(panel, c)
            if (b.width > 0 && b.x >= 0 && b.x + b.width <= panel.width) null else "$name at x ${b.x} to ${b.x + b.width}"
        } +
            actions.mapNotNull { c ->
                val b = boundsIn(panel, c)
                if (b.height > 0 && form.contains(b)) null else "${c.text} at $b outside the form at $form"
            } +
            fields.flatMap { (name, c) ->
                actions.filter { boundsIn(panel, it).intersects(boundsIn(panel, c)) }.map { "$name under ${it.text}" }
            }
    }

    /** The scroll pane of the event list; asked for before the first event, while the list holds its note. */
    private fun events(panel: InspectorPanel): JScrollPane =
        UIUtil.findComponentsOfType(panel, JScrollPane::class.java).single { pane ->
            UIUtil.findComponentsOfType(pane, JLabel::class.java).any { it.text.startsWith("No events yet") }
        }

    private fun showError(
        panel: InspectorPanel,
        message: String,
        define: List<String>? = null,
        useBaseUrl: String? = null,
    ) {
        panel.javaClass
            .getDeclaredMethod("error", String::class.java, List::class.java, String::class.java)
            .apply { isAccessible = true }
            .invoke(panel, message, define, useBaseUrl)
    }

    /** The line of the error [message] in the event list. */
    private fun errorLine(
        events: JScrollPane,
        message: String,
    ): JTextArea {
        val found = UIUtil.findComponentsOfType(events, JTextArea::class.java).filter { !it.isEditable && it.text == message }
        assertEquals("no error line that is text: $message", 1, found.size)
        return found.single()
    }

    private fun links(events: JScrollPane) = UIUtil.findComponentsOfType(events, ActionLink::class.java).map { it.text }

    private fun pump(
        body: java.io.InputStream,
        handlers: StreamClient.Handlers,
        aborted: java.util.concurrent.atomic.AtomicBoolean,
    ) = runBlocking { StreamClient.pump(body, handlers, aborted) }

    /** Wait for what the panel reads off the UI thread, letting the UI thread take the result. */
    private fun await(job: Job) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!job.isCompleted && System.currentTimeMillis() < deadline) {
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            Thread.sleep(5)
        }
        assertTrue("the panel did not finish reading", job.isCompleted && !job.isCancelled)
    }

    /** The form above the event list: the fields and the row of action buttons. */
    private fun form(panel: InspectorPanel) = (panel.layout as BorderLayout).getLayoutComponent(BorderLayout.NORTH) as JComponent

    private fun button(
        panel: InspectorPanel,
        text: String,
    ) = UIUtil.findComponentsOfType(panel, JButton::class.java).single { it.text == text }

    /** The line under the URL field that shows the URL as it is sent, [text] being what it shows. */
    private fun preview(
        panel: InspectorPanel,
        text: String,
    ): JTextArea {
        val found =
            UIUtil
                .findComponentsOfType(
                    form(panel),
                    JTextArea::class.java,
                ).filter { !it.isEditable && it.text == text }
        assertEquals("no preview that is text: $text in ${texts(panel)}", 1, found.size)
        return found.single()
    }

    private fun tabLeaves(text: JTextComponent) {
        for (keys in listOf(KeyboardFocusManager.FORWARD_TRAVERSAL_KEYS, KeyboardFocusManager.BACKWARD_TRAVERSAL_KEYS)) {
            assertEquals(text.parent.getFocusTraversalKeys(keys), text.getFocusTraversalKeys(keys))
        }
    }

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
            val client = StreamClient(background)
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
                StreamClient(background).open(
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
        val texts = myFixture.findAllGutters().mapNotNull { it.tooltipText }.sorted()
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
            StreamClient(background).open(
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
                StreamClient(background).open(
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
        pump(
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
        pump(
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

    fun `test a save and a delete keep what else a hand-written requests file holds`() {
        val store = RequestStore.getInstance(project)
        val file = write(Requests.REQUESTS_FILE, HAND_WRITTEN)
        try {
            assertEquals(listOf("feed", "plain"), store.saved().map { it.name })
            assertEquals(listOf("Request 2 in .streamlord/inspector.json is not read: it has no \"url\"."), store.read().skipped)

            store.save(SavedRequest("alpha", "/a", "GET", "", ""))
            val withAlpha =
                HAND_WRITTEN.replace(
                    "    {\n      \"name\": \"feed\"",
                    "    ${entry("alpha", "/a")},\n    {\n      \"name\": \"feed\"",
                )
            assertEquals(withAlpha, String(file.contentsToByteArray()))

            val feed = store.saved().single { it.name == "feed" }
            assertEquals(SavedRequest("feed", "{{baseUrl}}/feed", "POST", "{ \"search\": \"ash\", \"n\": 1.0 }", "X-Id: 7"), feed)
            store.save(feed.copy(url = "{{baseUrl}}/feed2"))
            val withFeed2 = withAlpha.replace("\"url\": \"{{baseUrl}}/feed\"", "\"url\": \"{{baseUrl}}/feed2\"")
            assertEquals(withFeed2, String(file.contentsToByteArray()))

            store.delete("PLAIN")
            assertEquals(
                withFeed2.replace(",\n    { \"name\": \"plain\", \"url\": \"http://h/feed\" }", ""),
                String(file.contentsToByteArray()),
            )
            store.delete("alpha")
            store.delete("feed")
            val left =
                "{\n  \"version\": 1,\n  \"team\": \"blue\",\n  \"requests\": [\n    { \"name\": \"no url here\" }\n  ]\n}\n"
            assertEquals(left, String(file.contentsToByteArray()))
        } finally {
            remove(Requests.REQUESTS_FILE)
        }
    }

    fun `test a save of an unchanged request leaves the file as it is, bytes and stamp`() {
        val store = RequestStore.getInstance(project)
        val file = write(Requests.REQUESTS_FILE, HAND_WRITTEN)
        try {
            val stamp = file.modificationStamp
            for (request in store.saved()) store.save(request)
            assertEquals(HAND_WRITTEN, String(file.contentsToByteArray()))
            assertEquals(stamp, file.modificationStamp)
        } finally {
            remove(Requests.REQUESTS_FILE)
        }
    }

    fun `test a delete of a name that is not saved leaves the file as it is, and creates none`() {
        val store = RequestStore.getInstance(project)
        remove(Requests.REQUESTS_FILE)
        store.delete("nowhere")
        assertNull(store.requestsFile())

        val file = write(Requests.REQUESTS_FILE, HAND_WRITTEN)
        try {
            val stamp = file.modificationStamp
            store.delete("nowhere")
            assertEquals(HAND_WRITTEN, String(file.contentsToByteArray()))
            assertEquals(stamp, file.modificationStamp)
        } finally {
            remove(Requests.REQUESTS_FILE)
        }
    }

    fun `test a requests file that is a list is not written over`() {
        val store = RequestStore.getInstance(project)
        val list = "[{\"name\": \"a\", \"url\": \"/a\"}]\n"
        val file = write(Requests.REQUESTS_FILE, list)
        val reason = ".streamlord/inspector.json is not a requests file: it must be an object with a \"requests\" list."
        try {
            assertEquals(reason, store.read().error)
            assertEquals(emptyList<SavedRequest>(), store.saved())
            val saved = assertFailsWith<IllegalStateException> { store.save(SavedRequest("b", "/b", "GET", "", "")) }
            assertEquals("$reason Fix the file before saving, so the requests in it are not lost.", saved.message)
            val deleted = assertFailsWith<IllegalStateException> { store.delete("a") }
            assertEquals("$reason Fix the file before saving, so the requests in it are not lost.", deleted.message)
            assertEquals(list, String(file.contentsToByteArray()))
        } finally {
            remove(Requests.REQUESTS_FILE)
        }
    }

    fun `test the variables file cannot be made the requests file`() {
        val store = RequestStore.getInstance(project)
        val settings = StreamlordSettings.getInstance(project)
        val variables = "{\n  \"baseUrl\": \"http://localhost:9102\"\n}\n"
        val env = write(Requests.ENV_FILE, variables)
        remove(Requests.REQUESTS_FILE)
        val refused = "The requests file cannot be .streamlord/env.json: that file holds the variables. Nothing was saved."
        try {
            for (path in listOf(
                ".streamlord/env.json",
                "./.streamlord/env.json",
                ".Streamlord/ENV.json",
                ".streamlord/./env.json",
                ".streamlord//env.json",
            )) {
                settings.state.requestsFile = path
                assertEquals(Requests.REQUESTS_FILE, store.requestsPath())
                val saved = assertFailsWith<IllegalStateException> { store.save(SavedRequest("a", "/a", "GET", "", "")) }
                assertEquals(refused, saved.message)
                val deleted = assertFailsWith<IllegalStateException> { store.delete("a") }
                assertEquals(refused, deleted.message)
                assertEquals(variables, String(env.contentsToByteArray()))
                assertNull(store.requestsFile())
            }
        } finally {
            settings.state.requestsFile = Requests.REQUESTS_FILE
            remove(Requests.ENV_FILE)
            remove(Requests.REQUESTS_FILE)
        }
    }

    fun `test the panel names the entries of the requests file that are not read`() {
        write(Requests.REQUESTS_FILE, "{\"requests\": [7, {\"name\": \"feed\", \"url\": \"/feed\"}, {\"name\": \"no url\"}]}")
        try {
            val panel = InspectorPanel(project, testRootDisposable)
            await(panel.pushRequests(null))
            val skipped =
                "Request 1 in .streamlord/inspector.json is not read: it is not an object.\n" +
                    "Request 3 in .streamlord/inspector.json is not read: it has no \"url\"."
            assertTrue(telling(panel, skipped).isVisible)
        } finally {
            remove(Requests.REQUESTS_FILE)
        }
    }

    fun `test the panel says why a requests file is not read`() {
        write(Requests.REQUESTS_FILE, "[]")
        try {
            val panel = InspectorPanel(project, testRootDisposable)
            await(panel.pushRequests(null))
            val reason = ".streamlord/inspector.json is not a requests file: it must be an object with a \"requests\" list."
            assertTrue(telling(panel, reason).isVisible)
        } finally {
            remove(Requests.REQUESTS_FILE)
        }
    }

    fun `test the panel says nothing about a requests file that reads cleanly`() {
        write(Requests.REQUESTS_FILE, "{\"requests\": [{\"name\": \"feed\", \"url\": \"/feed\"}, {\"name\": \"no url\"}]}")
        try {
            val panel = InspectorPanel(project, testRootDisposable)
            await(panel.pushRequests(null))
            val area = telling(panel, "Request 2 in .streamlord/inspector.json is not read: it has no \"url\".")
            assertTrue(area.isVisible)

            // Once the file is put right, what was said about it goes, and the area with it.
            write(Requests.REQUESTS_FILE, "{\"requests\": [{\"name\": \"feed\", \"url\": \"/feed\"}]}")
            await(panel.pushRequests(null))
            assertFalse(notes(panel).toString(), notes(panel).any(::aboutTheRequestsFile))
            assertEquals("", area.text)
            assertFalse(area.isVisible)
            assertNoEmptyAreaShows(panel)

            remove(Requests.REQUESTS_FILE)
            val fresh = InspectorPanel(project, testRootDisposable)
            await(fresh.pushRequests(null))
            assertFalse(notes(fresh).toString(), notes(fresh).any(::aboutTheRequestsFile))
            assertNoEmptyAreaShows(fresh)
        } finally {
            remove(Requests.REQUESTS_FILE)
        }
    }

    // The default file is what the list then shows, and nothing else said so before a Save was refused.
    fun `test the panel says first when the setting names a path with a problem`() {
        val store = RequestStore.getInstance(project)
        val settings = StreamlordSettings.getInstance(project)
        val problem = "The requests file ../elsewhere/inspector.json leaves the project: use a path without .. in it."
        val skipped = "Request 1 in .streamlord/inspector.json is not read: it is not an object."
        write(Requests.REQUESTS_FILE, "{\"requests\": [7, {\"name\": \"feed\", \"url\": \"/feed\"}]}")
        try {
            assertNull(store.requestsPathProblem())
            settings.state.requestsFile = "../elsewhere/inspector.json"
            assertEquals(problem, store.requestsPathProblem())
            val panel = InspectorPanel(project, testRootDisposable)
            await(panel.pushRequests(null))
            val area = telling(panel, "$problem The default file is shown.\n$skipped")
            assertTrue(area.isVisible)

            write(Requests.REQUESTS_FILE, "[]")
            await(panel.pushRequests(null))
            val reason = ".streamlord/inspector.json is not a requests file: it must be an object with a \"requests\" list."
            assertEquals("$problem The default file is shown.\n$reason", area.text)

            remove(Requests.REQUESTS_FILE)
            await(panel.pushRequests(null))
            assertEquals("$problem The default file is shown.", area.text)
            assertTrue(area.isVisible)

            settings.state.requestsFile = ".streamlord/./env.json"
            assertEquals("The requests file cannot be .streamlord/env.json: that file holds the variables.", store.requestsPathProblem())
            await(panel.pushRequests(null))
            assertEquals(
                "The requests file cannot be .streamlord/env.json: that file holds the variables. The default file is shown.",
                area.text,
            )

            settings.state.requestsFile = ""
            assertNull(store.requestsPathProblem())
            await(panel.pushRequests(null))
            assertEquals("", area.text)
            assertFalse(area.isVisible)
        } finally {
            settings.state.requestsFile = Requests.REQUESTS_FILE
            remove(Requests.REQUESTS_FILE)
        }
    }

    // Swing renders a label whose text starts with <html> as HTML and fetches its images; an event name or a saved name must not.
    fun `test the panel shows server text and shared names as text, never as Swing HTML`() {
        val store = RequestStore.getInstance(project)
        store.save(SavedRequest("<html><b>x</b><img src='http://127.0.0.1:1/beacon'>", "/a", "GET", "", ""))
        val panel = InspectorPanel(project, testRootDisposable)
        await(panel.pushRequests(null))
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
        val labels = texts(panel)
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

        fun labels() = texts(panel)
        patch("""{"a":[1]}""")
        val store = UIUtil.findComponentsOfType(panel, JBTextArea::class.java).single { it.text.startsWith("{\n") }
        patch("""{"b":1,"o":{"__proto__":{"x":1}},"c":2}""")
        assertEquals("""{"a":[1],"b":1,"o":{}}""", JsonParser.parse(store.text).toJson())
        val proto =
            "Streamlord refuses this signal patch on purpose: A key named __proto__ whose value is an object with keys mostly " +
                "makes Datastar's client throw at that key, and the bindings on the page then stop redrawing. Otherwise the client " +
                "gives the object holding the key a foreign prototype, which then throws on some later keys, replaces the object on " +
                "a later patch, or with a second __proto__ writes into String.prototype. Streamlord does not model it and refuses " +
                "the patch. " +
                "The store shown keeps the keys before it."
        assertTrue(labels().toString(), proto in labels())
        patch("""{"a":{"20000":1}}""")
        val holes =
            "Streamlord refuses this signal patch on purpose: Signal patch index 20000 is more than 10000 past the end of an array " +
                "of 1: the client takes it, and Streamlord does not fill memory with the empty slots. The store shown keeps the keys before it."
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

    // The request sends a param encoded, and the line under the URL is there to show what is sent.
    fun `test the line under the URL shows a param as the request sends it`() {
        write(Requests.ENV_FILE, "{\"baseUrl\":\"http://h:1\",\"params\":{\"id\":\"4 2/x\"}}")
        try {
            val panel = InspectorPanel(project, testRootDisposable)
            await(panel.pushRequests(SavedRequest("", "{{baseUrl}}/feed/{{id}}", "GET", "", "")))
            await(panel.pushRequests(null))

            val line = preview(panel, "http://h:1/feed/4%202%2Fx")
            assertTrue(line.lineWrap)
            assertTrue(line.isVisible)
            tabLeaves(line)

            val url = UIUtil.findComponentsOfType(panel, JBTextField::class.java).single { it.text == "{{baseUrl}}/feed/{{id}}" }
            url.text = "{{baseUrl}}/feed/{{nowhere}}/{{signals}}?id={{ id }}"
            assertEquals("http://h:1/feed/{{nowhere}}/{{signals}}?id=4%202%2Fx", line.text)
            assertTrue(line.isVisible)

            // The IDE renders a tooltip as HTML whatever it starts with, and the URL is typed or comes from a shared file.
            url.text = "{{baseUrl}}/feed/<b>bold</b>"
            assertEquals("http://h:1/feed/<b>bold</b>", line.text)
            assertNull(line.toolTipText)
            val tooltips = UIUtil.findComponentsOfType(panel, JComponent::class.java).mapNotNull { it.toolTipText }
            assertEquals(emptyList<String>(), tooltips.filter { "bold" in it || "http://h:1" in it })

            url.text = "http://h:1/feed"
            assertEquals("", line.text)
            assertFalse(line.isVisible)
            assertFalse(texts(panel).toString(), texts(panel).any { it.startsWith("http://h:1/feed") })
        } finally {
            remove(Requests.ENV_FILE)
        }
    }

    fun `test a long preview goes on to more lines inside the panel`() {
        write(Requests.ENV_FILE, "{\"baseUrl\":\"http://h:1\"}")
        try {
            val panel = InspectorPanel(project, testRootDisposable)
            val long = "http://h:1" + "/part".repeat(38)
            assertEquals(200, long.length)
            await(panel.pushRequests(SavedRequest("", "{{baseUrl}}" + "/part".repeat(38), "GET", "", "")))
            layOut(panel, 360)
            val line = preview(panel, long)
            val bounds = boundsIn(panel, line)
            assertTrue("$bounds in 360", bounds.width > 0 && bounds.x >= 0 && bounds.x + bounds.width <= panel.width)
            val oneLine = line.getFontMetrics(line.font).height
            assertTrue("${line.height} high, a line is $oneLine", line.height >= 2 * oneLine)
            assertEquals(emptyList<String>(), outside(panel))
        } finally {
            remove(Requests.ENV_FILE)
        }
    }

    // A URL is typed or comes from a shared file, and the IDE renders every tooltip as HTML.
    fun `test the tooltip of the status shows the URL that was sent as text`() {
        val panel = InspectorPanel(project, testRootDisposable)
        await(panel.pushRequests(null))
        val status = UIUtil.findComponentsOfType(form(panel), JLabel::class.java).single { it.text == "idle" }
        // What an answer does: the status, and the URL it came from. The tool window's queue does not run in a test.
        panel.javaClass
            .getDeclaredMethod("setStatus", String::class.java, StreamClient.Status::class.java, String::class.java)
            .apply { isAccessible = true }
            .invoke(panel, "open · 200 OK", StreamClient.Status.OPEN, "http://h/x?a=1&lt=2<b>\"q\"&amp;")
        assertEquals("open · 200 OK", status.text)
        assertEquals("http://h/x?a=1&amp;lt=2&lt;b&gt;&quot;q&quot;&amp;amp;", status.toolTipText)
    }

    fun `test the modified marker asks for the width of its text in the font it has`() {
        val feed = SavedRequest("feed", "{{baseUrl}}/feed", "GET", "", "")
        RequestStore.getInstance(project).save(feed)
        try {
            val panel = InspectorPanel(project, testRootDisposable)
            await(panel.pushRequests(feed))
            val url = UIUtil.findComponentsOfType(panel, JBTextField::class.java).single { it.text == feed.url }
            url.text = "${feed.url}/2"
            val marker = UIUtil.findComponentsOfType(panel, JBLabel::class.java).single { it.text == "● modified" }
            val shown = marker.preferredSize
            assertTrue("$shown", shown.width >= marker.getFontMetrics(marker.font).stringWidth("● modified"))

            url.text = feed.url
            assertFalse(modifiedShows(panel))
            assertEquals(shown, marker.preferredSize)

            marker.font = marker.font.deriveFont(marker.font.size2D * 2)
            val larger = marker.preferredSize
            assertTrue("$larger after $shown", larger.width > shown.width)
            assertTrue("$larger", larger.width >= marker.getFontMetrics(marker.font).stringWidth("● modified"))
            url.text = "${feed.url}/2"
            assertEquals(larger, marker.preferredSize)
        } finally {
            remove(Requests.REQUESTS_FILE)
        }
    }

    fun `test the status stands at the right end of the action row`() {
        val panel = InspectorPanel(project, testRootDisposable)
        await(panel.pushRequests(null))
        val status = UIUtil.findComponentsOfType(form(panel), JLabel::class.java).single { it.text == "idle" }

        layOut(panel, 900)
        val wide = boundsIn(panel, status)
        val right = form(panel).x + form(panel).width
        assertTrue("$wide, the form ends at $right", right - (wide.x + wide.width) in 0..16)
        val connect = boundsIn(panel, button(panel, "Connect"))
        assertTrue("$wide beside $connect", wide.y + wide.height / 2 in connect.y..connect.y + connect.height)

        layOut(panel, 360)
        val narrow = boundsIn(panel, status)
        assertTrue("$narrow in 360", narrow.width > 0 && narrow.x >= 0 && narrow.x + narrow.width <= panel.width)
    }

    fun `test the form holds its buttons and fields apart whichever width came first`() {
        val panel = InspectorPanel(project, testRootDisposable)
        await(panel.pushRequests(null))
        val found =
            listOf(360, 460, 900, 360, 900, 460, 360).mapIndexedNotNull { step, width ->
                layOut(panel, width)
                outside(panel).takeIf { it.isNotEmpty() }?.let { "step ${step + 1}, $width wide: $it" }
            }
        assertEquals(emptyList<String>(), found)
    }

    // The note stands in an empty list only: the first event takes its place, and no event takes the place of another.
    fun `test the first event takes the place of the note, and no later one takes a line away`() {
        val panel = InspectorPanel(project, testRootDisposable)
        val events = events(panel)
        val list = UIUtil.findComponentsOfType(events, JLabel::class.java).single { it.text.startsWith("No events yet") }.parent

        fun labels() = UIUtil.findComponentsOfType(events, JLabel::class.java).map { it.text }

        fun comment(text: String) {
            panel.javaClass
                .getDeclaredMethod("addComment", String::class.java)
                .apply { isAccessible = true }
                .invoke(panel, text)
        }

        fun frame(event: String) {
            panel.javaClass
                .getDeclaredMethod("addFrame", DatastarFrame::class.java)
                .apply { isAccessible = true }
                .invoke(panel, DatastarFrame(event, null, null, mapOf("elements" to "<p>x</p>"), "", 0L))
        }

        comment("first comment")
        assertEquals(1, list.componentCount)
        assertFalse(labels().toString(), labels().any { it.startsWith("No events yet") })
        frame("first-frame")
        assertEquals(2, list.componentCount)
        assertTrue(labels().toString(), labels().any { it.endsWith(": first comment") } && "first-frame" in labels())

        button(panel, "Clear").doClick()
        assertEquals(listOf("Cleared."), labels())
        showError(panel, "an error")
        assertEquals(1, list.componentCount)
        assertFalse(labels().toString(), "Cleared." in labels())
        comment("after the error")
        assertEquals(2, list.componentCount)
        errorLine(events, "an error")
        assertTrue(labels().toString(), labels().any { it.endsWith(": after the error") })

        button(panel, "Clear").doClick()
        comment("after Clear")
        assertEquals(1, list.componentCount)
        assertEquals(listOf(true), labels().map { it.endsWith(": after Clear") })

        // Each line added is one line more, whatever stood alone before it.
        val adds = listOf<() -> Unit>({ comment("a") }, { comment("b") }, { frame("c") }, { showError(panel, "d") }, { comment("e") })
        for (start in adds) {
            button(panel, "Clear").doClick()
            start()
            for ((n, add) in adds.withIndex()) {
                add()
                assertEquals(n + 2, list.componentCount)
            }
        }
    }

    fun `test every line of the event list starts at its left edge`() {
        val panel = InspectorPanel(project, testRootDisposable)
        val events = events(panel)
        val note = UIUtil.findComponentsOfType(events, JLabel::class.java).single { it.text.startsWith("No events yet") }
        val list = note.parent
        layOut(panel, 460)
        assertEquals(0, SwingUtilities.convertRectangle(list, note.bounds, events.viewport).x)

        // The note gives way to the first event, so it is measured alone and the other four together.
        panel.javaClass
            .getDeclaredMethod("addFrame", DatastarFrame::class.java)
            .apply { isAccessible = true }
            .invoke(panel, DatastarFrame("datastar-patch-elements", "1", null, mapOf("elements" to "<p>x</p>"), "", 0L))
        panel.javaClass
            .getDeclaredMethod("addComment", String::class.java)
            .apply { isAccessible = true }
            .invoke(panel, "a comment")
        showError(panel, "an error without a link")
        showError(panel, "an error with a link", listOf("id"))
        layOut(panel, 460)
        assertEquals(4, list.componentCount)
        assertEquals(listOf(0, 0, 0, 0), list.components.map { SwingUtilities.convertRectangle(list, it.bounds, events.viewport).x })
        val texts = listOf("an error without a link", "an error with a link").map { errorLine(events, it) }
        assertEquals(1, texts.map { SwingUtilities.convertRectangle(it.parent, it.bounds, events.viewport).x }.distinct().size)
    }

    // A tool window is often narrower than the row of action buttons, and the form is no wider than the panel.
    fun `test the form fits a narrow panel, and its action buttons share a line in a wide one`() {
        val panel = InspectorPanel(project, testRootDisposable)
        await(panel.pushRequests(null))
        val found =
            listOf(900, 460, 360).flatMap { width ->
                layOut(panel, width)
                outside(panel).map { "$it of $width" }
            }
        assertEquals(emptyList<String>(), found)
        layOut(panel, 900)
        val lines = ACTION_BUTTONS.map { boundsIn(panel, button(panel, it)).y }
        assertEquals(lines.toString(), 1, lines.distinct().size)
    }

    // The marker holds its place whether it shows or not, so a click meant for Save does not land on Delete.
    fun `test Save and Delete stay where they are when the modified marker comes and goes`() {
        val feed = SavedRequest("feed", "{{baseUrl}}/feed", "GET", "", "")
        RequestStore.getInstance(project).save(feed)
        try {
            val panel = InspectorPanel(project, testRootDisposable)
            await(panel.pushRequests(feed))
            val url = UIUtil.findComponentsOfType(panel, JBTextField::class.java).single { it.text == feed.url }

            fun buttons() =
                listOf("Save", "Delete").map { text ->
                    boundsIn(panel, UIUtil.findComponentsOfType(panel, JButton::class.java).single { it.text == text })
                }
            for (width in listOf(900, 460, 360)) {
                layOut(panel, width)
                assertFalse(modifiedShows(panel))
                val before = buttons()
                assertEquals("at $width", emptyList<String>(), outside(panel))

                url.text = "${feed.url}/2"
                layOut(panel, width)
                assertTrue(modifiedShows(panel))
                assertEquals("edited, at $width", before, buttons())

                url.text = feed.url
                layOut(panel, width)
                assertFalse(modifiedShows(panel))
                assertEquals("put back, at $width", before, buttons())
            }
        } finally {
            remove(Requests.REQUESTS_FILE)
        }
    }

    fun `test an error in the event list is text that wraps, with its link beside it`() {
        val panel = InspectorPanel(project, testRootDisposable)
        val events = events(panel)

        // A message is someone else's text as much as an event name is: never Swing HTML.
        val markup = "<html><b>bold</b><img src='http://127.0.0.1:1/beacon'> could not be reached"
        showError(panel, markup)
        val line = errorLine(events, markup)
        assertTrue(line.lineWrap)
        assertTrue(line.wrapStyleWord)
        tabLeaves(line)
        assertEquals(emptyList<String>(), links(events))

        showError(panel, "{{id}} is not set. Add it to params in .streamlord/env.json.", listOf("id"))
        tabLeaves(errorLine(events, "{{id}} is not set. Add it to params in .streamlord/env.json."))
        assertEquals(listOf("Add to .streamlord/env.json"), links(events))

        showError(panel, "Could not reach http://localhost:8080/feed.", emptyList())
        errorLine(events, "Could not reach http://localhost:8080/feed.")
        assertTrue(links(events).toString(), "Edit variables" in links(events))

        showError(panel, "Could not reach http://localhost:8080/x.", null, "http://localhost:9102")
        errorLine(events, "Could not reach http://localhost:8080/x.")
        assertTrue(links(events).toString(), "Use http://localhost:9102 in .streamlord/env.json" in links(events))
        assertEquals(3, links(events).size)
    }

    // An error is read whole in the list: it goes on to more lines, and the list never scrolls sideways.
    fun `test a long error is laid out no wider than the event list, on more lines than one`() {
        val panel = InspectorPanel(project, testRootDisposable)
        val events = events(panel)
        assertTrue((events.viewport.view as? Scrollable)?.scrollableTracksViewportWidth == true)

        val long = "long ".repeat(59) + "words"
        assertEquals(300, long.length)
        showError(panel, long)
        showError(panel, "$long.", listOf("id"))
        for (width in listOf(460, 360, 900, 360)) {
            layOut(panel, width)
            val viewport = events.viewport
            assertTrue("${viewport.width}", viewport.width in 1..width)
            assertTrue("${viewport.view.width} in ${viewport.width}", viewport.view.width <= viewport.width)
            for (line in listOf(errorLine(events, long), errorLine(events, "$long."))) {
                val bounds = SwingUtilities.convertRectangle(line.parent, line.bounds, viewport)
                assertTrue("$bounds in ${viewport.width}", line.width > 0 && bounds.x >= 0 && bounds.x + bounds.width <= viewport.width)
                val oneLine = line.getFontMetrics(line.font).height
                assertTrue("${line.height} high at $width, a line is $oneLine", line.height >= 2 * oneLine)
            }
        }
    }

    // Reading a file is not work for the UI thread: the form is there at once, and the list follows.
    fun `test the panel fills the form at once and reads the saved requests off the UI thread`() {
        RequestStore.getInstance(project).save(SavedRequest("feed", "{{baseUrl}}/feed", "GET", "", ""))
        val panel = InspectorPanel(project, testRootDisposable)

        fun names() =
            UIUtil
                .findComponentsOfType(panel, JComboBox::class.java)
                .flatMap { combo -> (0 until combo.itemCount).map { combo.getItemAt(it).toString() } }
        val job = panel.pushRequests(SavedRequest("feed", "{{baseUrl}}/feed", "GET", "", ""))
        assertTrue(UIUtil.findComponentsOfType(panel, JBTextField::class.java).any { it.text == "{{baseUrl}}/feed" })
        assertFalse(names().toString(), "feed" in names())

        await(job)
        assertTrue(names().toString(), "feed" in names())
    }

    // A save is planned in the background and written on the UI thread: what changed in between is not written over.
    fun `test a file changed after a save was planned is left as it is`() {
        val store = RequestStore.getInstance(project)
        store.save(SavedRequest("feed", "/feed", "GET", "", ""))
        val planned = store.saving(SavedRequest("count", "/count", "GET", "", ""))
        store.save(SavedRequest("pulled", "/pulled", "GET", "", ""))

        try {
            store.apply(planned)
            fail("the save wrote over a file that had changed")
        } catch (e: IllegalStateException) {
            assertEquals("${Requests.REQUESTS_FILE} changed in the meantime. Try again.", e.message)
        }
        assertEquals(listOf("feed", "pulled"), store.saved().map { it.name })
    }

    fun `test the variables file edited after a change was planned is left as it is`() {
        val store = RequestStore.getInstance(project)
        val (file, _) = store.defineVariables(emptyList())
        try {
            val planned = store.usingBaseUrl("http://localhost:9103")
            val document = FileDocumentManager.getInstance().getDocument(file)!!
            WriteCommandAction.runWriteCommandAction(project) { document.setText("""{"baseUrl": "http://typed"}""") }

            try {
                store.apply(planned)
                fail("the change wrote over text typed since")
            } catch (e: IllegalStateException) {
                assertEquals("${Requests.ENV_FILE} changed in the meantime. Try again.", e.message)
            }
            assertEquals("""{"baseUrl": "http://typed"}""", document.text)
        } finally {
            WriteCommandAction.runWriteCommandAction(project) { file.delete(this) }
        }
    }

    // The stream is open and silent when its scope ends: the read under way is interrupted, which is no failure.
    fun `test an open stream ends quietly when its scope does`() {
        val server = java.net.ServerSocket(0)
        val serving =
            Thread {
                runCatching {
                    server.accept().use { socket ->
                        val input = socket.getInputStream().bufferedReader()
                        var line = input.readLine()
                        while (!line.isNullOrEmpty()) line = input.readLine()
                        socket.getOutputStream().apply {
                            write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\n\r\n".toByteArray())
                            flush()
                        }
                        Thread.sleep(30_000)
                    }
                }
            }.apply {
                isDaemon = true
                start()
            }
        try {
            val open = CountDownLatch(1)
            val closed = CountDownLatch(1)
            val errors = java.util.concurrent.CopyOnWriteArrayList<String>()
            val scope = CoroutineScope(SupervisorJob())
            StreamClient(scope).open(
                "http://127.0.0.1:${server.localPort}/silent",
                "GET",
                "{}",
                emptyMap(),
                object : StreamClient.Handlers {
                    override fun onStatus(
                        status: StreamClient.Status,
                        http: String?,
                        contentType: String?,
                    ) {
                        if (status == StreamClient.Status.OPEN) open.countDown()
                        if (status == StreamClient.Status.CLOSED) closed.countDown()
                    }

                    override fun onError(message: String) {
                        errors += message
                    }
                },
            )
            assertTrue(open.await(10, TimeUnit.SECONDS))
            Thread.sleep(200)
            scope.cancel()
            assertTrue(closed.await(10, TimeUnit.SECONDS))
            assertEquals(emptyList<String>(), errors)
        } finally {
            serving.interrupt()
            server.close()
        }
    }

    // The server takes the connection and never answers, so the request is waiting when its scope ends.
    fun `test a request waiting for the server ends quietly when its scope does`() {
        val server = java.net.ServerSocket(0)
        try {
            val connecting = CountDownLatch(1)
            val closed = CountDownLatch(1)
            val errors = java.util.concurrent.CopyOnWriteArrayList<String>()
            val scope = CoroutineScope(SupervisorJob())
            StreamClient(scope).open(
                "http://127.0.0.1:${server.localPort}/never",
                "GET",
                "{}",
                emptyMap(),
                object : StreamClient.Handlers {
                    override fun onStatus(
                        status: StreamClient.Status,
                        http: String?,
                        contentType: String?,
                    ) {
                        if (status == StreamClient.Status.CONNECTING) connecting.countDown()
                        if (status == StreamClient.Status.CLOSED) closed.countDown()
                    }

                    override fun onError(message: String) {
                        errors += message
                    }
                },
            )
            assertTrue(connecting.await(10, TimeUnit.SECONDS))
            Thread.sleep(300)
            scope.cancel()
            assertTrue(closed.await(10, TimeUnit.SECONDS))
            assertEquals(emptyList<String>(), errors)
        } finally {
            server.close()
        }
    }

    private companion object {
        val FORM_BUTTONS = listOf("Clear", "Connect", "Copy as curl", "Delete", "Reset signals", "Save", "Stop")
        val ACTION_BUTTONS = listOf("Connect", "Stop", "Clear", "Reset signals", "Copy as curl")

        /** A requests file as a person writes one: members and an entry the inspector does not know, and a layout of its own. */
        val HAND_WRITTEN =
            """
            {
              "version": 1,
              "team": "blue",
              "requests": [
                {
                  "name": "feed",
                  "url": "{{baseUrl}}/feed",
                  "method": "POST",
                  "signals": { "search": "ash", "n": 1.0 },
                  "headers": { "X-Id": "7" },
                  "note": "written by hand"
                },
                { "name": "no url here" },
                { "name": "plain", "url": "http://h/feed" }
              ]
            }
            """.trimIndent() + "\n"
    }
}
