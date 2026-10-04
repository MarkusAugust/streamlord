package io.github.markusaugust.streamlord.intellij.inspector

import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
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
import io.github.markusaugust.streamlord.analysis.SavedRequest
import io.github.markusaugust.streamlord.core.json.mergePatch
import io.github.markusaugust.streamlord.core.json.JsonNull
import io.github.markusaugust.streamlord.core.json.JsonObject
import io.github.markusaugust.streamlord.core.json.JsonParser
import io.github.markusaugust.streamlord.core.json.JsonValue
import io.github.markusaugust.streamlord.intellij.Streamlord
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
    private val client = StreamClient()
    private var connection: StreamClient.Connection? = null
    private var signals: JsonValue = JsonObject.EMPTY
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
    private val resolved = JBLabel().apply { foreground = JBColor.GRAY }
    private val dirty = JBLabel("● modified").apply { foreground = JBColor.namedColor("Label.warningForeground", JBColor.ORANGE) }
    private val status = JBLabel("idle")
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
            com.intellij.openapi.vfs.VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    if (events.any { it.path.contains("/.streamlord/") && it.path.endsWith(".json") }) {
                        ApplicationManager.getApplication().invokeLater { pushRequests(null) }
                    }
                }
            },
        )
        com.intellij.openapi.util.Disposer
            .register(parentDisposable) { stop() }
        val vars = { store.environment().vars }
        val typing = { !filling }
        VariableCompletion(url, Requests.Field.URL, vars, typing)
        VariableCompletion(signalsField, Requests.Field.SIGNALS, vars, typing)
        VariableCompletion(headersField, Requests.Field.HEADERS, vars, typing)
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
        val vars = Requests.variableValues(store.environment().vars)
        resolved.text = if ("{{" in url.text) Requests.substitute(url.text, vars).text else ""
        val f = fields()
        val l = loaded
        dirty.isVisible = l != null && (f.url != l.url || f.method != l.method || f.signals != l.signals || f.headers != l.headers)
        deleteButton.isEnabled = (choices.selectedItem as? Choice)?.saved == true
    }

    /** Fill the list from the file and the workspace state, and the form from [current] when given. */
    fun pushRequests(current: SavedRequest?) {
        val saved = store.saved()
        val recent = store.recent()
        val env = store.environment()
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
        if (current != null) {
            fill(current)
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
            loaded = if (known) fields() else null
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
        try {
            store.save(current.copy(name = name))
            pushRequests(current.copy(name = name))
        } catch (e: Exception) {
            error(e.message ?: "Could not save the request.")
        }
    }

    private fun delete() {
        val choice = choices.selectedItem as? Choice ?: return
        val name = choice.request?.name ?: return
        if (!choice.saved) return
        val ok = Messages.showYesNoDialog(project, "Delete saved request “$name”?", "Delete Request", "Delete", "Cancel", null)
        if (ok != Messages.YES) return
        store.delete(name)
        pushRequests(SavedRequest("", url.text, method.selectedItem as String, signalsField.text, headersField.text))
    }

    /**
     * The request with its variables filled in, or null after the reason is shown: an invalid env
     * file, or a variable that is unknown, misplaced or not set.
     */
    private fun resolve(raw: SavedRequest): Pair<SavedRequest, List<Requests.Variable>>? {
        val env = store.environment()
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
        val (request, _) = resolve(fields()) ?: return
        try {
            CopyPasteManager.getInstance().setContents(java.awt.datatransfer.StringSelection(Requests.toCurl(request)))
            notify("Streamlord: curl command copied.")
        } catch (e: Exception) {
            error(e.message ?: "Could not build the curl command.")
        }
    }

    /** Add [names] to the env file, creating it when it is missing, and open it. */
    private fun editVariables(names: List<String>) {
        try {
            val (file, extended) = store.defineVariables(names)
            FileEditorManager.getInstance(project).openFile(file, true)
            if (!extended) error("${Requests.ENV_FILE} is not a JSON object, so nothing was added to it.")
        } catch (e: Exception) {
            error(e.message ?: "Could not open ${Requests.ENV_FILE}.")
        }
    }

    private fun connect() {
        stop()
        val raw = fields()
        val (request, vars) = resolve(raw) ?: return
        try {
            JsonParser.parse(Requests.compactSignals(request.signals))
        } catch (e: Exception) {
            error("Signals are not valid JSON: ${e.message}")
            return
        }
        store.addRecent(raw)
        pushRequests(null)
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

                    override fun onNonSse(response: StreamClient.NonSseResponse) = later { addNonSse(response) }

                    override fun onError(message: String) =
                        later {
                            val unreachable = message.startsWith("Connection refused") || message.startsWith("Host not found")
                            val hint = if (unreachable) Requests.unreachableHint(raw.url, vars) else null
                            if (hint != null) error("$message $hint", emptyList()) else error(message)
                        }
                },
            )
    }

    private fun stop() {
        connection?.close()
        connection = null
    }

    private fun later(block: () -> Unit) {
        ApplicationManager.getApplication().invokeLater(block, project.disposed)
    }

    private fun applyFrame(frame: DatastarFrame) {
        if (frame.event != "datastar-patch-signals") return
        val text = frame.args["signals"] ?: return
        try {
            var patch = JsonParser.parse(text)
            if (frame.args["onlyIfMissing"] == "true" && patch is JsonObject) {
                val existing = signals as? JsonObject ?: JsonObject.EMPTY
                patch = JsonObject(patch.filterKeys { !existing.containsKey(it) })
            }
            signals = mergePatch(signals, patch)
            storeView.text = pretty(signals)
        } catch (_: Exception) {
            // invalid JSON in the stream is shown raw in the frame itself
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
            JBLabel(message).apply {
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
            JBLabel("${time(System.currentTimeMillis())}  : $text").apply {
                foreground = JBColor.GRAY
                font = font.deriveFont(Font.ITALIC)
                border = JBUI.Borders.empty(2, 4)
            },
        )
    }

    private fun addNonSse(r: StreamClient.NonSseResponse) {
        val rows = LinkedHashMap<String, String>()
        rows.putAll(r.headers)
        rows["body"] = r.body
        prepend(card(listOf(r.http, r.contentType.ifEmpty { "no content-type" }), JBColor.foreground(), "non-SSE response", rows))
    }

    /** An error line; with [define], a link that adds those names to the env file, or opens it when there are none. */
    private fun error(
        message: String,
        define: List<String>? = null,
    ) {
        val label =
            JBLabel(message).apply {
                foreground = JBColor.RED
                border = JBUI.Borders.empty(2, 4)
            }
        if (define == null) {
            prepend(label)
        } else {
            val line =
                JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0)).apply {
                    isOpaque = false
                    add(label)
                    add(ActionLink(if (define.isEmpty()) "Edit variables" else "Add to ${Requests.ENV_FILE}") { editVariables(define) })
                }
            prepend(line)
        }
        setStatus("error", null)
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
                JBLabel(h).apply {
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
            JBLabel(right).apply {
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

    private fun notify(message: String) {
        NotificationGroupManager
            .getInstance()
            .getNotificationGroup(Streamlord.NOTIFICATION_GROUP)
            .createNotification(message, NotificationType.INFORMATION)
            .notify(project)
    }

    companion object {
        private const val MAX_FRAMES = 500
        private val TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

        fun time(at: Long): String = TIME.format(Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()))

        fun suggestName(r: SavedRequest): String =
            runCatching {
                "${r.method} ${java.net.URI(r.url.replace(Regex("""\{\{[^}]+\}\}"""), "http://x")).path}"
            }.getOrDefault("${r.method} ${r.url}")

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
