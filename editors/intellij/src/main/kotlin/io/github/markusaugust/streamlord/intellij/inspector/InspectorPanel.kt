package io.github.markusaugust.streamlord.intellij.inspector

import com.intellij.icons.AllIcons
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.application.readAction
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListenerBackgroundable
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.JBColor
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import io.github.markusaugust.streamlord.analysis.DatastarFrame
import io.github.markusaugust.streamlord.analysis.Requests
import io.github.markusaugust.streamlord.analysis.RequestsFile
import io.github.markusaugust.streamlord.analysis.SavedRequest
import io.github.markusaugust.streamlord.core.json.JsonNull
import io.github.markusaugust.streamlord.core.json.JsonObject
import io.github.markusaugust.streamlord.core.json.JsonParser
import io.github.markusaugust.streamlord.core.json.JsonValue
import io.github.markusaugust.streamlord.core.json.SignalPatchException
import io.github.markusaugust.streamlord.core.json.mergePatchAsClient
import io.github.markusaugust.streamlord.intellij.Streamlord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Font
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.BoxLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.DefaultListCellRenderer
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.SwingConstants
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * The Stream Inspector: opens a Datastar request against your running server and shows every
 * event as it arrives, decoded, with the signal store as the client would see it. Requests can
 * be saved to the project, recalled from the recent list, filled from a route in the gutter,
 * parameterised with `{{variables}}` and exported as curl.
 */
class InspectorPanel(
    private val project: Project,
    parentDisposable: Disposable,
) : JBPanel<InspectorPanel>(BorderLayout()) {
    private val store = RequestStore.getInstance(project)
    private val running = RunningServers.getInstance(project)
    private val scope = InspectorService.getInstance(project).scopeFor(parentDisposable)
    private val client = StreamClient(scope)

    /** The variables as last read, off the UI thread; what the form shows. Connect and the curl export read them again first. */
    private var environment = RequestStore.Environment(emptyList(), emptyList())

    /** The request to select once the list is read again, and the read under way. */
    private var selecting: SavedRequest? = null
    private var reading: Job? = null

    /** A Connect that is reading the variables before it sends. */
    private var connecting: Job? = null
    private var connection: StreamClient.Connection? = null
    private var signals: JsonObject = JsonObject.EMPTY
    private var count = 0

    /** An entry of the request list: a saved request, a recent one, or the blank one at the top. */
    private class Choice(
        val label: String,
        val request: SavedRequest?,
        val saved: Boolean,
    ) {
        override fun toString(): String = label
    }

    private val choices = ComboBox(DefaultComboBoxModel<Choice>())
    private val url = JBTextField()
    private val method = ComboBox(Requests.METHODS.toTypedArray())
    private val signalsField = JBTextArea(3, 40).mono()
    private val headersField = JBTextArea(2, 40).mono()
    private val resolved = plainLabel().apply { foreground = JBColor.GRAY }
    private val dirty = JBLabel("● modified").apply { foreground = JBColor.namedColor("Label.warningForeground", JBColor.ORANGE) }
    private val status = plainLabel("idle")
    private val frames = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
    private val storeView = JBTextArea("{}").mono().apply { isEditable = false }
    private val variablesView = JBTextArea("").mono().apply { isEditable = false }
    private val variableErrors =
        JBTextArea("").apply {
            isEditable = false
            isOpaque = false
            lineWrap = true
            wrapStyleWord = true
            foreground = JBColor.RED
        }

    /** What is wrong with the requests file: why it is not read, or which of its entries are not. */
    private val requestProblems =
        JBTextArea("").apply {
            isEditable = false
            isOpaque = false
            lineWrap = true
            wrapStyleWord = true
            foreground = JBColor.RED
            isVisible = false
        }
    private val deleteButton = JButton("Delete")
    private val stopButton = JButton("Stop")

    /** The request as loaded from the list, to detect edits. */
    private var loaded: SavedRequest? = null
    private var filling = false

    init {
        border = JBUI.Borders.empty(8)
        add(form(), BorderLayout.NORTH)
        val split = OnePixelSplitter(false, 0.65f)
        split.firstComponent = JBScrollPane(framesHolder())
        split.secondComponent = aside()
        add(split, BorderLayout.CENTER)
        choices.renderer =
            object : DefaultListCellRenderer() {
                // A saved name comes from a file the project shares: text, never Swing HTML. Set before any text is.
                init {
                    putClientProperty("html.disable", true)
                }

                override fun getListCellRendererComponent(
                    list: JList<*>?,
                    value: Any?,
                    index: Int,
                    isSelected: Boolean,
                    cellHasFocus: Boolean,
                ): Component {
                    val c = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                    val choice = value as? Choice
                    icon =
                        if (choice?.request == null) {
                            null
                        } else if (choice.saved) {
                            AllIcons.Nodes.Favorite
                        } else {
                            AllIcons.Vcs.History
                        }
                    return c
                }
            }
        choices.addActionListener {
            if (filling) return@addActionListener
            val choice = choices.selectedItem as? Choice ?: return@addActionListener
            val r = choice.request
            if (r != null) fill(r)
            loaded = if (choice.saved) fields() else null
            refresh()
        }
        val watch =
            object : DocumentListener {
                override fun insertUpdate(e: DocumentEvent) = refresh()

                override fun removeUpdate(e: DocumentEvent) = refresh()

                override fun changedUpdate(e: DocumentEvent) = refresh()
            }
        url.document.addDocumentListener(watch)
        signalsField.document.addDocumentListener(watch)
        headersField.document.addDocumentListener(watch)
        method.addActionListener { refresh() }
        project.messageBus.connect(parentDisposable).subscribe(
            // Off the UI thread: it looks at paths and nothing else.
            VirtualFileManager.VFS_CHANGES_BG,
            object : BulkFileListenerBackgroundable {
                override fun after(events: List<VFileEvent>) {
                    if (events.any { it.path.contains("/.streamlord/") && it.path.endsWith(".json") }) {
                        later { pushRequests(null) }
                    }
                }
            },
        )
        // The Variables box follows the env file as it is typed, as Connect reads it.
        EditorFactory.getInstance().eventMulticaster.addDocumentListener(
            object : com.intellij.openapi.editor.event.DocumentListener {
                override fun documentChanged(event: com.intellij.openapi.editor.event.DocumentEvent) {
                    val file = FileDocumentManager.getInstance().getFile(event.document) ?: return
                    if (file.name == "env.json" && file.parent?.name == ".streamlord") {
                        later { pushRequests(null) }
                    }
                }
            },
            parentDisposable,
        )
        // The Variables box follows a server the project starts or stops, as Connect reads it.
        running.onChange(parentDisposable) { later { pushRequests(null) } }
        com.intellij.openapi.util.Disposer
            .register(parentDisposable) { stop() }
        val vars = { environment.vars }
        val typing = { !filling }
        VariableCompletion(url, Requests.Field.URL, vars, typing, ::later)
        VariableCompletion(signalsField, Requests.Field.SIGNALS, vars, typing, ::later)
        VariableCompletion(headersField, Requests.Field.HEADERS, vars, typing, ::later)
        pushRequests(store.lastUsed() ?: SavedRequest("", "{{baseUrl}}/"))
    }

    private fun JBTextArea.mono(): JBTextArea =
        apply {
            font = JBFont.create(Font(Font.MONOSPACED, Font.PLAIN, JBFont.label().size))
            lineWrap = true
            wrapStyleWord = false
        }

    private fun form(): JComponent =
        panel {
            row("Request:") {
                cell(choices).align(AlignX.FILL).resizableColumn()
                button("Save") { save() }.applyToComponent { toolTipText = "Save the current request to ${store.requestsPath()}" }
                cell(deleteButton).applyToComponent {
                    toolTipText = "Delete the selected saved request"
                    addActionListener { delete() }
                }
                cell(dirty)
            }
            row("") { cell(requestProblems).align(AlignX.FILL) }
            row("URL:") {
                cell(url).align(AlignX.FILL).applyToComponent { emptyText.text = "{{baseUrl}}/api/feed" }
            }
            row("") { cell(resolved) }
            row("Method:") { cell(method) }
            row("Signals:") {
                scrollCell(signalsField).align(AlignX.FILL).applyToComponent { emptyText.text = "{\"search\": \"ash\"}" }
            }
            row("Headers:") {
                scrollCell(headersField).align(AlignX.FILL).applyToComponent { emptyText.text = "Authorization: Bearer token" }
            }
            row {
                button("Connect") { connect() }.applyToComponent { icon = AllIcons.Actions.Execute }
                cell(stopButton).applyToComponent {
                    icon = AllIcons.Actions.Suspend
                    addActionListener {
                        stop()
                        setStatus("idle", null)
                    }
                }
                button("Clear") { clearFrames("Cleared.") }
                button("Reset signals") {
                    signals = JsonObject.EMPTY
                    storeView.text = "{}"
                }
                button("Copy as curl") { copyCurl() }.applyToComponent { toolTipText = "Copy an equivalent curl command" }
                cell(status).align(AlignX.RIGHT).resizableColumn()
            }
        }

    private fun framesHolder(): JComponent {
        val holder = JPanel(BorderLayout())
        holder.add(frames, BorderLayout.NORTH)
        clearFrames("No events yet. The realm is quiet.")
        return holder
    }

    private fun aside(): JComponent =
        panel {
            row { label("Signal store").bold() }
            row { scrollCell(storeView).align(com.intellij.ui.dsl.builder.Align.FILL).resizableColumn() }.resizableRow()
            collapsibleGroup("Variables") {
                row { scrollCell(variablesView).align(AlignX.FILL) }
                row { cell(variableErrors).align(AlignX.FILL) }
                row {
                    link("Edit variables") { editVariables(emptyList()) }
                        .applyToComponent { toolTipText = "Open ${Requests.ENV_FILE}, creating it with baseUrl" }
                }
                row {
                    comment(
                        "Type {{ in a field to pick one. {{baseUrl}} goes in the URL, {{signals}} in the signals and " +
                            "{{headers}} in the headers. ${Requests.ENV_FILE} holds them, and is meant for local hosts and tokens: " +
                            "keep it out of version control.",
                    )
                }
            }.expanded = true
        }

    // ---- state ---------------------------------------------------------------------------------

    private fun fields(): SavedRequest =
        SavedRequest(
            name = (choices.selectedItem as? Choice)?.takeIf { it.saved }?.request?.name ?: "",
            url = url.text,
            method = method.selectedItem as? String ?: "GET",
            signals = signalsField.text,
            headers = headersField.text,
        )

    private fun fill(r: SavedRequest) {
        filling = true
        url.text = r.url
        method.selectedItem = r.method.ifEmpty { "GET" }
        signalsField.text = r.signals
        headersField.text = r.headers
        filling = false
    }

    private fun refresh() {
        if (filling) return
        val vars = Requests.variableValues(environment.vars)
        resolved.text = if ("{{" in url.text) Requests.substitute(url.text, vars).text else ""
        val f = fields()
        val l = loaded
        dirty.isVisible = l != null && (f.url != l.url || f.method != l.method || f.signals != l.signals || f.headers != l.headers)
        deleteButton.isEnabled = (choices.selectedItem as? Choice)?.saved == true
    }

    /**
     * Fill the form from [current] when given, and the list and the variables from the files and the workspace state.
     * The files are read off the UI thread, so the list follows when the returned job is done; a newer call takes over
     * from one still reading.
     */
    fun pushRequests(current: SavedRequest?): Job {
        if (current != null) {
            fill(current)
            selecting = current
        }
        reading?.cancel()
        return scope
            .launch {
                val read = readAction { Triple(store.read(), store.environment(), store.requestsPathProblem()) }
                withContext(Dispatchers.EDT) { show(read.first, read.second, read.third) }
            }.also { reading = it }
    }

    private fun show(
        file: RequestsFile,
        env: RequestStore.Environment,
        pathProblem: String?,
    ) {
        val saved = file.requests
        val problems = listOfNotNull(pathProblem?.let { "$it The default file is shown." }) + (file.error?.let(::listOf) ?: file.skipped)
        requestProblems.text = problems.joinToString("\n")
        requestProblems.isVisible = requestProblems.text.isNotEmpty()
        environment = env
        val recent = store.recent()
        variablesView.text = Requests.describeVariables(env.vars)
        variableErrors.text = env.errors.joinToString("\n")
        variableErrors.isVisible = env.errors.isNotEmpty()
        filling = true
        val model = choices.model as DefaultComboBoxModel<Choice>
        val previous = (choices.selectedItem as? Choice)?.label
        model.removeAllElements()
        model.addElement(Choice(Requests.newRequestLabel(saved.size, recent.size), null, false))
        for (r in saved) model.addElement(Choice(r.name, r, true))
        for (r in recent) model.addElement(Choice("recent: ${r.name}", r, false))
        val current = selecting
        selecting = null
        if (current != null) {
            val known = current.name.isNotEmpty() && saved.any { it.name == current.name }
            choices.selectedIndex =
                if (known) {
                    (0 until model.size).first {
                        model
                            .getElementAt(
                                it,
                            ).saved && model.getElementAt(it).label == current.name
                    }
                } else {
                    0
                }
            filling = false
            // As it was loaded, whatever has been typed since: an edit made while the files were read is an edit.
            loaded = if (known) current else null
        } else {
            val idx = (0 until model.size).firstOrNull { model.getElementAt(it).label == previous } ?: 0
            choices.selectedIndex = idx
            filling = false
        }
        refresh()
    }

    /** Prefill from a route in the gutter, or from anywhere else. */
    fun prefill(request: SavedRequest) {
        pushRequests(request)
    }

    // ---- actions -------------------------------------------------------------------------------

    private fun save() {
        val current = fields()
        val name =
            Messages.showInputDialog(
                project,
                "Name for this request",
                "Save Request",
                null,
                current.name.ifEmpty { suggestName(current) },
                null,
            )
                ?: return
        if (name.isBlank()) return
        change("Could not save the request.", { store.saving(current.copy(name = name)) }) { _, _ ->
            pushRequests(current.copy(name = name))
        }
    }

    private fun delete() {
        val choice = choices.selectedItem as? Choice ?: return
        val name = choice.request?.name ?: return
        if (!choice.saved) return
        val ok = Messages.showYesNoDialog(project, "Delete saved request “$name”?", "Delete Request", "Delete", "Cancel", null)
        if (ok != Messages.YES) return
        change("Could not delete the request.", { store.deleting(name) }) { _, _ ->
            pushRequests(SavedRequest("", url.text, method.selectedItem as String, signalsField.text, headersField.text))
        }
    }

    /**
     * The request with its variables filled in, or null after the reason is shown: an invalid env
     * file, or a variable that is unknown, misplaced or not set.
     */
    private fun resolve(raw: SavedRequest): Pair<SavedRequest, List<Requests.Variable>>? {
        val env = environment
        if (env.errors.isNotEmpty()) {
            error(env.errors.joinToString(" "), emptyList())
            return null
        }
        val resolved = Requests.resolveRequest(raw, env.vars)
        if (resolved.errors.isNotEmpty()) {
            error(resolved.errors.joinToString(" "), resolved.unset)
            return null
        }
        return resolved.request to env.vars
    }

    private fun copyCurl() {
        val raw = fields()
        withVariables {
            val (request, _) = resolve(raw) ?: return@withVariables
            try {
                CopyPasteManager.getInstance().setContents(java.awt.datatransfer.StringSelection(Requests.toCurl(request)))
                notify("Streamlord: curl command copied.")
            } catch (e: Exception) {
                error(e.message ?: "Could not build the curl command.")
            }
        }
    }

    /** Add [names] to the env file, creating it when it is missing, and open it. */
    private fun editVariables(names: List<String>) {
        change("Could not open ${Requests.ENV_FILE}.", { store.definingVariables(names) }) { file, extended ->
            file?.let { OpenFileDescriptor(project, it).navigate(true) }
            if (!extended) error("${Requests.ENV_FILE} is not a JSON object, so nothing was added to it.")
        }
    }

    private fun connect() {
        stop()
        val raw = fields()
        connecting = withVariables { send(raw) }
    }

    private fun send(raw: SavedRequest) {
        val (request, vars) = resolve(raw) ?: return
        try {
            JsonParser.parse(Requests.compactSignals(request.signals))
        } catch (e: Exception) {
            error("Signals are not valid JSON: ${e.message}")
            return
        }
        store.addRecent(raw)
        pushRequests(null)
        val since = System.currentTimeMillis()
        connection =
            client.open(
                request.url,
                request.method,
                request.signals,
                Requests.parseHeaderLines(request.headers),
                object : StreamClient.Handlers {
                    override fun onStatus(
                        status: StreamClient.Status,
                        http: String?,
                        contentType: String?,
                    ) = later {
                        setStatus(status.name.lowercase() + (http?.let { " · $it" } ?: "") + (contentType?.let { " · $it" } ?: ""), status)
                        this@InspectorPanel.status.toolTipText = request.url
                    }

                    override fun onFrame(frame: DatastarFrame) =
                        later {
                            applyFrame(frame)
                            addFrame(frame)
                        }

                    override fun onComment(text: String) = later { addComment(text) }

                    override fun onNonSse(response: StreamClient.NonSseResponse) {
                        if ((response.http.substringBefore(' ').toIntOrNull() ?: 0) < 400) return later { addNonSse(response, emptyList()) }
                        // The server logs the reason as it answers, and a little after; wait for it before drawing the card.
                        scope.launch {
                            delay(LOG_GRACE_MS)
                            later { addNonSse(response, running.problemsSince(since)) }
                        }
                    }

                    override fun onError(message: String) =
                        later {
                            val unreachable = message.startsWith("Connection refused") || message.startsWith("Host not found")
                            val server = running.current()
                            val hint = if (unreachable) Requests.unreachableHint(raw.url, vars, server) else null
                            val elsewhere = if (unreachable) Requests.baseUrlSuggestion(vars, server) else null
                            when {
                                elsewhere != null -> error("$message $hint", useBaseUrl = elsewhere)
                                hint != null -> error("$message $hint", emptyList())
                                else -> error(message)
                            }
                        }
                },
            )
    }

    private fun stop() {
        connecting?.cancel()
        connecting = null
        connection?.close()
        connection = null
    }

    /**
     * Change a file of the project: [plan] reads it under a read action in the background, the UI thread
     * writes what was planned, and [done] gets the file and whether it holds the change. Shows
     * [failure], or the reason given, when it cannot be done.
     */
    private fun change(
        failure: String,
        plan: () -> RequestStore.Change,
        done: (VirtualFile?, Boolean) -> Unit,
    ): Job =
        scope.launch(ModalityState.current().asContextElement()) {
            val planned =
                try {
                    readAction(plan)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    withContext(Dispatchers.EDT) { error(e.message ?: failure) }
                    return@launch
                }
            withContext(Dispatchers.EDT) {
                val written =
                    try {
                        store.apply(planned)
                    } catch (e: Exception) {
                        error(e.message ?: failure)
                        return@withContext
                    }
                done(written.first, written.second)
            }
        }

    /** Read the variables again, as the files and the settings have them now, and then do [use] with them on the UI thread. */
    private fun withVariables(use: () -> Unit): Job =
        scope.launch(ModalityState.current().asContextElement()) {
            val env = readAction { store.environment() }
            withContext(Dispatchers.EDT) {
                environment = env
                use()
            }
        }

    private fun later(block: () -> Unit) {
        ToolWindowManager.getInstance(project).invokeLater(block)
    }

    /** The store as the client would hold it after this frame, see [mergePatchAsClient]; `onlyIfMissing` is read as the client reads it (patchSignals.ts:16, text.ts:24 in 1.0.4). */
    private fun applyFrame(frame: DatastarFrame) {
        if (frame.event != "datastar-patch-signals") return
        val text = frame.args["signals"] ?: return
        val patch =
            try {
                JsonParser.parse(text)
            } catch (_: Exception) {
                // invalid JSON in the stream is shown raw in the frame itself
                return
            }
        try {
            signals = mergePatchAsClient(signals, patch, isStringTrue(frame.args["onlyIfMissing"]))
            storeView.text = pretty(signals)
        } catch (e: SignalPatchException) {
            // The client keeps the keys it set before the one it failed on, and so does the store
            // shown. A throw also leaves its update batch open (signals.ts:695-708), so the bindings
            // on the page stop redrawing.
            signals = e.store
            storeView.text = pretty(signals)
            if (e.refused) {
                error("Streamlord refuses this signal patch on purpose: ${e.message}. $REFUSED")
            } else {
                error("The signal store cannot take this patch: ${e.message}. $CLIENT_THROWS")
            }
        }
    }

    // ---- rendering -----------------------------------------------------------------------------

    private fun setStatus(
        text: String,
        s: StreamClient.Status?,
    ) {
        status.text = text
        status.foreground =
            when (s) {
                StreamClient.Status.OPEN -> JBColor.namedColor("Label.successForeground", JBColor(0x368746, 0x50A661))
                else -> JBColor.foreground()
            }
        stopButton.isEnabled = s == StreamClient.Status.OPEN || s == StreamClient.Status.CONNECTING
    }

    private fun clearFrames(message: String) {
        frames.removeAll()
        count = 0
        frames.add(
            plainLabel(message).apply {
                foreground = JBColor.GRAY
                border = JBUI.Borders.empty(8)
            },
        )
        frames.revalidate()
        frames.repaint()
    }

    private fun clearEmpty() {
        if (frames.componentCount == 1 && frames.getComponent(0) is JBLabel) frames.removeAll()
    }

    private fun prepend(c: JComponent) {
        clearEmpty()
        frames.add(c, 0)
        while (frames.componentCount > MAX_FRAMES) frames.remove(frames.componentCount - 1)
        frames.revalidate()
        frames.repaint()
    }

    private fun addFrame(frame: DatastarFrame) {
        count++
        val kindColor =
            when {
                frame.event.endsWith("elements") -> JBColor(0x1A6EB5, 0x4FA3E0)
                frame.event.endsWith("signals") -> JBColor(0x7B3FA0, 0xB98BE0)
                else -> JBColor.foreground()
            }
        val header = mutableListOf("#$count", frame.event)
        frame.id?.let { header += "id $it" }
        frame.retry?.let { header += "retry ${it}ms" }
        prepend(card(header, kindColor, time(frame.receivedAt), frame.args))
    }

    private fun addComment(text: String) {
        prepend(
            plainLabel("${time(System.currentTimeMillis())}  : $text").apply {
                foreground = JBColor.GRAY
                font = font.deriveFont(Font.ITALIC)
                border = JBUI.Borders.empty(2, 4)
            },
        )
    }

    private fun addNonSse(
        r: StreamClient.NonSseResponse,
        problems: List<String>,
    ) {
        val rows = LinkedHashMap<String, String>()
        rows.putAll(r.headers)
        rows["body"] = r.body
        // The server's own words come before the note that would send the reader to look for them.
        if (problems.isNotEmpty()) {
            rows["server log"] = problems.joinToString("\n")
        } else {
            Requests.emptyBodyHint(r.http.substringBefore(' ').toIntOrNull() ?: 0, r.body)?.let { rows["note"] = it }
        }
        prepend(card(listOf(r.http, r.contentType.ifEmpty { "no content-type" }), JBColor.foreground(), "non-SSE response", rows))
    }

    /**
     * An error line; with [define], a link that adds those names to the env file, or opens it when
     * there are none; with [useBaseUrl], a link that sets the file's `baseUrl` to it.
     */
    private fun error(
        message: String,
        define: List<String>? = null,
        useBaseUrl: String? = null,
    ) {
        val label =
            plainLabel(message).apply {
                foreground = JBColor.RED
                border = JBUI.Borders.empty(2, 4)
            }
        if (define == null && useBaseUrl == null) {
            prepend(label)
        } else {
            val line =
                JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0)).apply {
                    isOpaque = false
                    add(label)
                    if (define != null) {
                        add(ActionLink(if (define.isEmpty()) "Edit variables" else "Add to ${Requests.ENV_FILE}") { editVariables(define) })
                    }
                    if (useBaseUrl != null) add(ActionLink("Use $useBaseUrl in ${Requests.ENV_FILE}") { useBaseUrl(useBaseUrl) })
                }
            prepend(line)
        }
        setStatus("error", null)
    }

    /** Set the env file's `baseUrl` to [url] and open the file, so the change is seen where it was made. */
    private fun useBaseUrl(url: String) {
        change("Could not open ${Requests.ENV_FILE}.", { store.usingBaseUrl(url) }) { file, changed ->
            file?.let { OpenFileDescriptor(project, it).navigate(true) }
            if (!changed) error("${Requests.ENV_FILE} is not a JSON object, so baseUrl was not changed.")
        }
    }

    private fun card(
        header: List<String>,
        color: java.awt.Color,
        right: String,
        rows: Map<String, String>,
    ): JComponent {
        val card = JPanel(BorderLayout())
        card.border = JBUI.Borders.compound(JBUI.Borders.emptyBottom(6), JBUI.Borders.customLine(JBColor.border(), 1))
        val head = JPanel(BorderLayout())
        head.border = JBUI.Borders.empty(3, 6)
        head.background = JBColor.namedColor("Editor.background", JBColor.PanelBackground)
        val left =
            JPanel().apply {
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                isOpaque = false
            }
        header.forEachIndexed { i, h ->
            left.add(
                plainLabel(h).apply {
                    if (i == 1) {
                        foreground = color
                        font = font.deriveFont(Font.BOLD)
                    }
                    border = JBUI.Borders.emptyRight(10)
                },
            )
        }
        head.add(left, BorderLayout.WEST)
        head.add(
            plainLabel(right).apply {
                foreground = JBColor.GRAY
                horizontalAlignment = SwingConstants.RIGHT
            },
            BorderLayout.EAST,
        )
        card.add(head, BorderLayout.NORTH)
        val body =
            panel {
                for ((k, v) in rows) {
                    row(k) {
                        cell(
                            JBTextArea(v).mono().apply {
                                isEditable = false
                                isOpaque = false
                                border = JBUI.Borders.empty()
                            },
                        ).align(AlignX.FILL)
                            .resizableColumn()
                    }
                }
            }
        body.border = JBUI.Borders.empty(4, 6)
        card.add(body, BorderLayout.CENTER)
        return card
    }

    /**
     * Swing renders a label whose text starts with `<html>` as HTML, images and all. Event names,
     * content types and saved names come from a server or a shared file, so every label here is
     * text. The flag has to be there before the text is set: Swing reads it when the text changes.
     */
    private fun plainLabel(text: String = ""): JBLabel =
        JBLabel().apply {
            putClientProperty("html.disable", true)
            this.text = text
        }

    private fun notify(message: String) {
        Notification(Streamlord.NOTIFICATION_GROUP, message, NotificationType.INFORMATION).notify(project)
    }

    companion object {
        private const val MAX_FRAMES = 500

        /** How long a failed request waits for the server to finish logging why. */
        private const val LOG_GRACE_MS = 400L
        private val TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

        private const val CLIENT_THROWS =
            "Datastar's client throws on it too, and the bindings already on the page then stop redrawing until it is " +
                "reloaded, though the signals still change, element patches still land and new bindings show the current value."
        private const val REFUSED = "The store shown keeps the keys before it."

        fun time(at: Long): String = TIME.format(Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()))

        fun suggestName(r: SavedRequest): String =
            runCatching {
                "${r.method} ${java.net.URI(r.url.replace(Regex("""\{\{[^}]+\}\}"""), "http://x")).path}"
            }.getOrDefault("${r.method} ${r.url}")

        /**
         * `isStringTrue` in the client's utils/text.ts: `"true"` after JavaScript's `trim()`, which
         * also takes U+FEFF and, unlike Kotlin's, leaves U+001C to U+001F.
         */
        fun isStringTrue(value: String?): Boolean = value != null && value.trim(::isJsSpace) == "true"

        private fun isJsSpace(c: Char): Boolean =
            c in "\t\n\u000B\u000C\r\uFEFF\u2028\u2029" || Character.getType(c) == Character.SPACE_SEPARATOR.toInt()

        /** Two-space indented JSON, as the VS Code inspector shows it. */
        fun pretty(
            v: JsonValue,
            indent: String = "",
        ): String =
            when (v) {
                is JsonObject -> {
                    if (v.isEmpty()) {
                        "{}"
                    } else {
                        v.entries.joinToString(",\n", "{\n", "\n$indent}") { (k, x) ->
                            "$indent  ${io.github.markusaugust.streamlord.core.json.JsonString(k).toJson()}: ${pretty(x, "$indent  ")}"
                        }
                    }
                }

                is io.github.markusaugust.streamlord.core.json.JsonArray -> {
                    if (v.isEmpty()) "[]" else v.joinToString(",\n", "[\n", "\n$indent]") { "$indent  ${pretty(it, "$indent  ")}" }
                }

                is JsonNull -> {
                    "null"
                }

                else -> {
                    v.toJson()
                }
            }
    }
}
