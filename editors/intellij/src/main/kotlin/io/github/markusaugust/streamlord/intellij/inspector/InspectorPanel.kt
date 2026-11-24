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
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.util.text.StringUtil
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
import io.github.markusaugust.streamlord.core.json.JsonObject
import io.github.markusaugust.streamlord.intellij.Streamlord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Component.BaselineResizeBehavior
import java.awt.Dimension
import java.awt.Font
import java.awt.Rectangle
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
import javax.swing.Scrollable
import javax.swing.SwingUtilities
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
    private var environment = Requests.Environment(emptyList(), emptyList())

    /** The request to select once the list is read again, and the read under way. */
    private var selecting: SavedRequest? = null
    private var reading: Job? = null

    /** A Connect that is reading the variables before it sends. */
    private var connecting: Job? = null
    private var connection: StreamClient.Connection? = null

    /** Counts the requests stopped, so one stopped or followed by another no longer speaks for the status. */
    private var stopped = 0
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

    private val choices = ComboBox(DefaultComboBoxModel<Choice>(), JBUI.scale(FIELD_WIDTH))
    private val url = JBTextField()
    private val method = ComboBox(Requests.METHODS.toTypedArray())
    private val signalsField = Mono(rows = 3, columns = FIELD_COLUMNS, padded = true)
    private val headersField = Mono(rows = 2, columns = FIELD_COLUMNS, padded = true)

    /** The URL as it is sent, read whole: a long one goes on to more lines. */
    private val resolved =
        Wrapped("").apply {
            columns = FIELD_COLUMNS
            // A URL has no spaces to break at.
            wrapStyleWord = false
            foreground = JBColor.GRAY
            isVisible = false
        }

    /** Says a loaded request is edited. It holds its place whether it says so or not, so Save and Delete do not move under the pointer. */
    private val dirty =
        object : JBLabel() {
            /** The room of the marker in the font of the moment, so it is whole after a zoom as well. */
            private fun place(): Dimension {
                val metrics = getFontMetrics(font)
                return Dimension(metrics.stringWidth(MODIFIED) + insets.left + insets.right, metrics.height + insets.top + insets.bottom)
            }

            override fun getPreferredSize(): Dimension = place()

            override fun getMinimumSize(): Dimension = place()
        }.apply { foreground = JBColor.namedColor("Label.warningForeground", JBColor.ORANGE) }

    private val status = Status()
    private val frames = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
    private val storeView = Mono("{}", padded = true).apply { isEditable = false }
    private val variablesView =
        Mono(padded = true).apply {
            isEditable = false
            // A name, its value and where it comes from: broken between words, not inside a URL's host.
            wrapStyleWord = true
        }
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
        Wrapped("").apply {
            foreground = JBColor.RED
            isVisible = false
        }
    private val deleteButton = JButton("Delete")
    private val stopButton = JButton("Stop").apply { isEnabled = false }

    /** The request as loaded from the list, to detect edits. */
    private var loaded: SavedRequest? = null
    private var filling = false

    init {
        border = JBUI.Borders.empty(8)
        add(form(), BorderLayout.NORTH)
        // The store and the variables stand beside the events in a wide panel, and under them in a narrow one.
        val split =
            object : OnePixelSplitter(false, 0.65f) {
                override fun doLayout() {
                    val stacked = width in 1 until JBUI.scale(STACK_WIDTH)
                    if (orientation != stacked) orientation = stacked
                    // Stacked, the store would take the height it asks for from the events; the proportion shares it instead, and the store scrolls.
                    setHonorComponentsMinimumSize(!stacked)
                    super.doLayout()
                }
            }
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
                    val requests = store.requestsFilePath()
                    if (events.any {
                            FileUtil.pathsEqual(it.path, requests) ||
                                (it.path.contains("/.streamlord/") && it.path.endsWith(".json"))
                        }
                    ) {
                        later { pushRequests(null) }
                    }
                }
            },
        )
        // The Variables box and the Request list follow their files as they are typed, as Connect and Save read them.
        EditorFactory.getInstance().eventMulticaster.addDocumentListener(
            object : com.intellij.openapi.editor.event.DocumentListener {
                override fun documentChanged(event: com.intellij.openapi.editor.event.DocumentEvent) {
                    val file = FileDocumentManager.getInstance().getFile(event.document) ?: return
                    if ((file.name == "env.json" && file.parent?.name == ".streamlord") ||
                        FileUtil.pathsEqual(file.path, store.requestsFilePath())
                    ) {
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

    /**
     * Text in the monospaced face at the size of the theme's labels, with room inside its frame when
     * [padded]. Both are set again when the IDE is zoomed or the theme changes, which installs the
     * theme's own font and margin over them.
     */
    private class Mono(
        text: String = "",
        rows: Int = 0,
        columns: Int = 0,
        private val padded: Boolean = false,
    ) : JBTextArea(text, rows, columns) {
        init {
            lineWrap = true
            wrapStyleWord = false
            restyle()
        }

        override fun updateUI() {
            super.updateUI()
            restyle()
        }

        private fun restyle() {
            font = monoFont()
            if (padded) margin = JBUI.insets(PAD_V, PAD_H)
        }
    }

    /**
     * A label in [style], set again when the IDE is zoomed or the theme changes. Swing renders a label
     * whose text starts with `<html>` as HTML, images and all. Event names, content types and saved
     * names come from a server or a shared file, so every label here is text. The flag has to be there
     * before the text is set: Swing reads it when the text changes.
     */
    private class Plain(
        text: String,
        private val style: Int,
    ) : JBLabel() {
        init {
            putClientProperty("html.disable", true)
            this.text = text
            restyle()
        }

        override fun updateUI() {
            super.updateUI()
            restyle()
        }

        private fun restyle() {
            if (style != Font.PLAIN) font = JBFont.label().deriveFont(style)
        }
    }

    /** A scroll pane that keeps room for [lines] lines of [text] in the font it has at the moment. */
    private class Lines(
        private val text: JBTextArea,
        private val lines: Int,
    ) : JBScrollPane(text) {
        override fun getMinimumSize(): Dimension = Dimension(super.getMinimumSize().width, room())

        override fun getPreferredSize(): Dimension = super.getPreferredSize().let { Dimension(it.width, maxOf(it.height, room())) }

        private fun room(): Int {
            val outer = insets
            val inner = text.insets
            return lines * text.getFontMetrics(text.font).height + outer.top + outer.bottom + inner.top + inner.bottom
        }
    }

    /**
     * The fields over the action buttons. Each field gives way when the tool window is narrow, and the
     * buttons go on to a second line, so nothing is laid out past the edge where it cannot be reached.
     */
    private fun form(): JComponent {
        val fields =
            panel {
                row("Request:") {
                    // Save and Delete go under the list where it would be too narrow to read beside them.
                    val save =
                        JButton("Save").apply {
                            // A tooltip is HTML, and the path is the project's shared settings: text there.
                            toolTipText = "Save the current request to ${StringUtil.escapeXmlEntities(store.requestsPath())}"
                            addActionListener { save() }
                        }
                    deleteButton.apply {
                        toolTipText = "Delete the selected saved request"
                        addActionListener { delete() }
                    }
                    cell(
                        Line(listOf(choices), Line(listOf(save, deleteButton, dirty)), stretch = true),
                    ).align(AlignX.FILL).resizableColumn()
                }
                row("") { cell(requestProblems).align(AlignX.FILL).resizableColumn() }
                row("URL:") {
                    cell(url).align(AlignX.FILL).resizableColumn().applyToComponent {
                        emptyText.text = "{{baseUrl}}/api/feed"
                        narrows()
                    }
                }
                row("") { cell(resolved).align(AlignX.FILL).resizableColumn() }
                row("Method:") { cell(method) }
                row("Signals:") {
                    scrollCell(signalsField).align(AlignX.FILL).resizableColumn().applyToComponent {
                        emptyText.text =
                            "{\"search\": \"ash\"}"
                    }
                }
                row("Headers:") {
                    scrollCell(headersField).align(AlignX.FILL).resizableColumn().applyToComponent {
                        emptyText.text =
                            "Authorization: Bearer token"
                    }
                }
            }
        val actions =
            listOf(
                JButton("Connect", AllIcons.Actions.Execute).apply { addActionListener { connect() } },
                stopButton.apply {
                    icon = AllIcons.Actions.Suspend
                    addActionListener {
                        stop()
                        setStatus("idle", null)
                    }
                },
                JButton("Clear").apply { addActionListener { clearFrames("Cleared.") } },
                JButton("Reset signals").apply {
                    addActionListener {
                        signals = JsonObject.EMPTY
                        storeView.text = "{}"
                    }
                },
                JButton("Copy as curl").apply {
                    toolTipText = "Copy an equivalent curl command"
                    addActionListener { copyCurl() }
                },
            )
        // The status keeps to the right end of the buttons' last line, or of a line of its own, and is cut only where a line is too short for it.
        val row = Line(actions, status).apply { border = JBUI.Borders.empty(GAP, 0) }
        return JPanel(BorderLayout()).apply {
            add(fields, BorderLayout.CENTER)
            add(row, BorderLayout.SOUTH)
        }
    }

    /** The status of the request; its tooltip has it whole where a line is too short for it. */
    private class Status : JBLabel() {
        init {
            putClientProperty("html.disable", true)
            text = "idle"
        }
    }

    /**
     * Text to read and not to write, on as many lines as its width takes: a message from a server or a
     * URL from a shared file is shown whole, and as text. Its height follows from its width, so a
     * new width is laid out again.
     */
    private class Wrapped(
        text: String,
        private val mono: Boolean = false,
    ) : JBTextArea(text) {
        init {
            isEditable = false
            isOpaque = false
            lineWrap = true
            // Code and markup break where the line ends, words where a word does.
            wrapStyleWord = !mono
            restyle()
        }

        override fun updateUI() {
            super.updateUI()
            restyle()
        }

        private fun restyle() {
            font = if (mono) monoFont() else JBFont.label()
        }

        /** As high as its text is at the width it has, and asking for little width itself: a text area asks for the width it was last given, and would hold a form at it. */
        override fun getPreferredSize(): Dimension =
            super.getPreferredSize().let { Dimension(minOf(it.width, JBUI.scale(FIELD_WIDTH)), it.height) }

        override fun getMinimumSize(): Dimension = preferredSize

        override fun setBounds(
            x: Int,
            y: Int,
            width: Int,
            height: Int,
        ) {
            val wider = width != this.width
            super.setBounds(x, y, width, height)
            // After the layout under way: one asked for in the middle of it is forgotten when it ends.
            if (wider) SwingUtilities.invokeLater { revalidate() }
        }
    }

    /** Lets a field be laid out narrower than it would like, down to a few characters. */
    private fun JComponent.narrows() {
        minimumSize = Dimension(JBUI.scale(40), minimumSize.height)
    }

    /**
     * The [lead] components from the left, going on to a new line where the panel ends, and [trail], when
     * given, at the right end of the last line, or of a line of its own where it has no room. With [stretch]
     * the one lead component takes the room the trail leaves, and the trail goes under it, from the left,
     * once the lead would be narrower than a few words. Its height follows from its width, so a new width is
     * laid out again; it asks for little width itself, so a narrow panel can hold it.
     */
    private class Line(
        private val lead: List<JComponent>,
        private val trail: JComponent? = null,
        private val stretch: Boolean = false,
    ) : JPanel(null) {
        init {
            isOpaque = false
            lead.forEach(::add)
            trail?.let(::add)
        }

        override fun doLayout() {
            place(width, true)
        }

        override fun getPreferredSize(): Dimension {
            val whole =
                lead.sumOf { it.preferredSize.width + JBUI.scale(GAP) } + (trail?.preferredSize?.width ?: -JBUI.scale(GAP)) +
                    insets.left + insets.right
            val w = if (width > 0) width else whole
            return Dimension(whole, place(w, false))
        }

        override fun getMinimumSize(): Dimension = Dimension(JBUI.scale(40), preferredSize.height)

        /** The baseline of the first lead component, so the label of a row stands by it and not halfway down the lines. */
        override fun getBaseline(
            width: Int,
            height: Int,
        ): Int {
            val first = lead.firstOrNull() ?: return -1
            val p = first.preferredSize
            val own = first.getBaseline(p.width, p.height)
            if (own < 0) return -1
            val h = place(width, false) - insets.top - insets.bottom
            // On one line the components are centred on it; on more, the first stands at the top.
            val oneLine = h <= maxOf(p.height, trail?.preferredSize?.height ?: 0)
            return insets.top + (if (stretch && oneLine) (h - p.height) / 2 else 0) + own
        }

        override fun getBaselineResizeBehavior(): BaselineResizeBehavior = BaselineResizeBehavior.CONSTANT_ASCENT

        override fun setBounds(
            x: Int,
            y: Int,
            width: Int,
            height: Int,
        ) {
            val wider = width != this.width
            super.setBounds(x, y, width, height)
            // After the layout under way: one asked for in the middle of it is forgotten when it ends.
            if (wider) SwingUtilities.invokeLater { revalidate() }
        }

        /** The height of what [c] shows at [width]: a line goes on to more lines in a narrow one. */
        private fun heightAt(
            c: JComponent,
            width: Int,
        ): Int = if (c is Line) c.place(width, false) else c.preferredSize.height

        /** Lay the components out in [w], when [apply], and say how high they are then. */
        fun place(
            w: Int,
            apply: Boolean,
        ): Int {
            val gap = JBUI.scale(GAP)
            val between = JBUI.scale(4)
            val left = insets.left
            val top = insets.top
            val room = maxOf(0, w - left - insets.right)
            val t = trail?.preferredSize
            if (stretch) {
                val l = lead.single()
                val lh = l.preferredSize.height
                val tw = minOf(t?.width ?: 0, room)
                val beside = room - (if (t == null) 0 else gap + t.width)
                if (beside >= JBUI.scale(STRETCH_MIN) || t == null) {
                    val th = t?.height ?: 0
                    val h = maxOf(lh, th)
                    if (apply) {
                        l.setBounds(left, top + (h - lh) / 2, maxOf(0, beside), lh)
                        trail?.setBounds(left + room - tw, top + (h - th) / 2, tw, th)
                    }
                    return h + top + insets.bottom
                }
                val th = heightAt(trail, tw)
                if (apply) {
                    l.setBounds(left, top, room, lh)
                    trail.setBounds(left, top + lh + between, tw, th)
                }
                return lh + between + th + top + insets.bottom
            }
            // Each component on its line and at its x, then each line as high as its highest, the others centred on it.
            val lines = ArrayList<MutableList<Pair<JComponent, Int>>>().apply { add(ArrayList()) }
            var x = 0
            for (c in lead) {
                val cw = minOf(c.preferredSize.width, room)
                if (x > 0 && x + cw > room) {
                    lines.add(ArrayList())
                    x = 0
                }
                lines.last() += c to x
                x += cw + gap
            }
            if (trail != null && t != null) {
                val tw = minOf(t.width, room)
                if (x > 0 && x + tw > room) lines.add(ArrayList())
                lines.last() += trail to room - tw
            }
            var y = 0
            for ((i, line) in lines.withIndex()) {
                val h = line.maxOfOrNull { it.first.preferredSize.height } ?: 0
                if (apply) {
                    for ((c, cx) in line) {
                        val p = c.preferredSize
                        c.setBounds(left + cx, top + y + (h - p.height) / 2, minOf(p.width, room), p.height)
                    }
                }
                y += h + if (i < lines.lastIndex) between else 0
            }
            return y + top + insets.bottom
        }
    }

    private fun framesHolder(): JComponent {
        val holder = EventList()
        holder.add(frames, BorderLayout.NORTH)
        clearFrames("No events yet. The realm is quiet.")
        return holder
    }

    /** The list of events, as wide as the pane that scrolls it: a long line breaks in it, and is not cut at the edge. */
    private class EventList :
        JPanel(BorderLayout()),
        Scrollable {
        override fun getPreferredScrollableViewportSize(): Dimension = preferredSize

        override fun getScrollableUnitIncrement(
            visibleRect: Rectangle,
            orientation: Int,
            direction: Int,
        ): Int = JBUI.scale(16)

        override fun getScrollableBlockIncrement(
            visibleRect: Rectangle,
            orientation: Int,
            direction: Int,
        ): Int = visibleRect.height

        override fun getScrollableTracksViewportWidth(): Boolean = true

        override fun getScrollableTracksViewportHeight(): Boolean = false
    }

    private fun aside(): JComponent =
        panel {
            row { label("Signal store").bold() }
            row { cell(Lines(storeView, STORE_LINES)).align(com.intellij.ui.dsl.builder.Align.FILL).resizableColumn() }.resizableRow()
            // Not indented, so its box lines up with the store's.
            collapsibleGroup("Variables", indent = false) {
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
        resolved.text = Requests.previewUrl(url.text, environment.vars)
        resolved.isVisible = resolved.text.isNotEmpty()
        val f = fields()
        val l = loaded
        val edited = l != null && (f.url != l.url || f.method != l.method || f.signals != l.signals || f.headers != l.headers)
        dirty.text = if (edited) MODIFIED else ""
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
        env: Requests.Environment,
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
        // IntelliJ's message alert, its default on every OS, reads a message as HTML, and the dialog it falls back to one that
        // opens with <html>: so it is HTML on both, and the name, which comes from a file the project shares, is escaped.
        val message = "<html>Delete saved request “${StringUtil.escapeXmlEntities(name)}”?</html>"
        val ok = Messages.showYesNoDialog(project, message, "Delete Request", "Delete", "Cancel", null)
        if (ok != Messages.YES) return
        change("Could not delete the request.", { store.deleting(name) }) { _, _ ->
            pushRequests(SavedRequest("", url.text, method.selectedItem as String, signalsField.text, headersField.text))
        }
    }

    /**
     * The request with its variables filled in, or null after the reason is shown: an invalid env
     * file, or a variable that is unknown, misplaced or not set. With [sending], the reason is the
     * status of the request, whose URL is as written since it could not be filled in.
     */
    private fun resolve(
        raw: SavedRequest,
        sending: Boolean = false,
    ): Pair<SavedRequest, List<Requests.Variable>>? {
        val show = { message: String, define: List<String> -> if (sending) failed(raw.url, message, define) else error(message, define) }
        val env = environment
        if (env.errors.isNotEmpty()) {
            show(env.errors.joinToString(" "), emptyList())
            return null
        }
        val resolved = Requests.resolveRequest(raw, env.vars)
        if (resolved.errors.isNotEmpty()) {
            show(resolved.errors.joinToString(" "), resolved.unset)
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
        val (request, vars) = resolve(raw, sending = true) ?: return
        Requests.signalsProblem(request.signals)?.let {
            failed(request.url, it)
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
                    /** Set on the UI thread, where the statuses are shown in turn: the close after an error does not hide it. */
                    private var failed = false

                    /** Whether this is still the request of the panel, on the UI thread: a close can come after Stop or the next Connect. */
                    private val current = stopped.let { at -> { at == stopped } }

                    override fun onStatus(
                        status: StreamClient.Status,
                        http: String?,
                        contentType: String?,
                    ) = later {
                        if (!current() || (failed && status == StreamClient.Status.CLOSED)) return@later
                        setStatus(
                            status.name.lowercase() + (http?.let { " · $it" } ?: "") + (contentType?.let { " · $it" } ?: ""),
                            status,
                            request.url,
                        )
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
                            if (!current()) return@later
                            val unreachable = message.startsWith("Connection refused") || message.startsWith("Host not found")
                            val server = running.current()
                            val hint = if (unreachable) Requests.unreachableHint(raw.url, vars, server) else null
                            val elsewhere = if (unreachable) Requests.baseUrlSuggestion(vars, server) else null
                            failed = true
                            when {
                                elsewhere != null -> failed(request.url, "$message $hint", useBaseUrl = elsewhere)
                                hint != null -> failed(request.url, "$message $hint", emptyList())
                                else -> failed(request.url, message)
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
        stopped++
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

    /** The store as the client would hold it after this frame, see [Requests.patchedStore]; a patch that is no JSON is shown raw in the frame itself. */
    private fun applyFrame(frame: DatastarFrame) {
        if (frame.event != "datastar-patch-signals") return
        val text = frame.args["signals"] ?: return
        val patched = Requests.patchedStore(signals, text, frame.args["onlyIfMissing"]) ?: return
        signals = patched.store
        storeView.text = cut(signals.toPrettyJson())
        patched.message?.let(::error)
    }

    // ---- rendering -----------------------------------------------------------------------------

    /** Show the status; its tooltip has it whole, with [url] when it is the status of a request. */
    private fun setStatus(
        text: String,
        s: StreamClient.Status?,
        url: String? = null,
    ) {
        status.text = text
        // The IDE renders a tooltip as HTML, whatever it starts with, and a URL comes from a shared file.
        status.toolTipText = listOfNotNull(text, url).joinToString("<br>") { StringUtil.escapeXmlEntities(it) }
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
        val note =
            plainLabel(message).apply {
                foreground = JBColor.GRAY
                border = JBUI.Borders.empty(8)
                alignmentX = LEFT_ALIGNMENT
            }
        emptyNote = note
        frames.add(note)
        frames.revalidate()
        frames.repaint()
    }

    /** The note of a list without events, which the first event takes the place of. A comment is a label as well, and stays. */
    private var emptyNote: JComponent? = null

    private fun clearEmpty() {
        emptyNote?.let(frames::remove)
        emptyNote = null
    }

    private fun prepend(c: JComponent) {
        clearEmpty()
        // One left edge for every kind of line: the layout sets a label and a panel apart when they differ in this.
        c.alignmentX = LEFT_ALIGNMENT
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
            Plain("${time(System.currentTimeMillis())}  : $text", Font.ITALIC).apply {
                foreground = JBColor.GRAY
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
        val head = listOf(r.http, r.contentType.ifEmpty { "no content-type" })
        prepend(card(head, JBColor.foreground(), "non-SSE response", rows, prose = setOf("note")))
    }

    /**
     * An error line; with [define], a link that adds those names to the env file, or opens it when
     * there are none; with [useBaseUrl], a link that sets the file's `baseUrl` to it. It leaves the
     * status of the stream as it is: an error of the request sent is [failed].
     */
    private fun error(
        message: String,
        define: List<String>? = null,
        useBaseUrl: String? = null,
    ) {
        val text =
            Wrapped(message).apply {
                foreground = JBColor.RED
                border = JBUI.Borders.empty(2, 4)
            }
        if (define == null && useBaseUrl == null) {
            prepend(text)
        } else {
            val links =
                Line(
                    listOfNotNull(
                        define?.let { names ->
                            ActionLink(if (names.isEmpty()) "Edit variables" else "Add to ${Requests.ENV_FILE}") { editVariables(names) }
                        },
                        useBaseUrl?.let { url -> ActionLink("Use $url in ${Requests.ENV_FILE}") { useBaseUrl(url) } },
                    ),
                ).apply { border = JBUI.Borders.emptyLeft(4) }
            prepend(
                JPanel(BorderLayout()).apply {
                    isOpaque = false
                    add(text, BorderLayout.CENTER)
                    add(links, BorderLayout.SOUTH)
                },
            )
        }
    }

    /** An error that keeps the request to [url] from being sent or ends it, and so is its status. */
    private fun failed(
        url: String,
        message: String,
        define: List<String>? = null,
        useBaseUrl: String? = null,
    ) {
        error(message, define, useBaseUrl)
        setStatus("error", null, url)
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
        prose: Set<String> = emptySet(),
    ): JComponent {
        val card = JPanel(BorderLayout())
        card.border = JBUI.Borders.compound(JBUI.Borders.emptyBottom(6), JBUI.Borders.customLine(JBColor.border(), 1))
        // The time keeps to the right end, and goes on to a line of its own where the names leave it no room.
        val head =
            Line(
                header.mapIndexed { i, h -> if (i == 1) Plain(h, Font.BOLD).apply { foreground = color } else plainLabel(h) },
                plainLabel(right).apply { foreground = JBColor.GRAY },
            )
        head.border = JBUI.Borders.empty(3, 6)
        head.isOpaque = true
        head.background = JBColor.namedColor("Editor.background", JBColor.PanelBackground)
        card.add(head, BorderLayout.NORTH)
        val body =
            panel {
                for ((k, v) in rows) {
                    // A row's name is the server's, a data line's first word: text, never Swing HTML.
                    row(plainLabel(k)) {
                        // The server's text breaks where the line ends; a note of ours, in [prose], between words.
                        cell(Wrapped(cut(v), mono = k !in prose).apply { border = JBUI.Borders.empty() })
                            .align(AlignX.FILL)
                            .resizableColumn()
                    }
                }
            }
        body.border = JBUI.Borders.empty(4, 6)
        card.add(body, BorderLayout.CENTER)
        return card
    }

    /** [value] with each line cut at [MAX_SHOWN] characters: Swing wraps a line by measuring it, and a line of 1.2 MB held the IDE for 45 seconds. */
    private fun cut(value: String): String =
        if (value.length <= MAX_SHOWN) {
            value
        } else {
            value.split('\n').joinToString("\n") { if (it.length <= MAX_SHOWN) it else it.take(MAX_SHOWN) + CUT }
        }

    private fun plainLabel(text: String = ""): JBLabel = Plain(text, Font.PLAIN)

    private fun notify(message: String) {
        Notification(Streamlord.NOTIFICATION_GROUP, message, NotificationType.INFORMATION).notify(project)
    }

    companion object {
        private const val MAX_FRAMES = 500
        private const val MAX_SHOWN = 10_000
        private const val CUT = "\n\n(cut at 10,000 characters)"
        private const val MODIFIED = "● modified"

        /** What a field of the form asks for; each is given the width there is, and asks for little so a narrow panel can hold it. */
        private const val GAP = 6
        private const val FIELD_COLUMNS = 12
        private const val FIELD_WIDTH = 60

        /** The room inside a text field, around its text, as the URL field has it. */
        private const val PAD_V = 4
        private const val PAD_H = 6

        /** The lines the signal store keeps room for, however little the panel has. */
        private const val STORE_LINES = 6

        /** How narrow the panel may get before the store and the variables go under the events. */
        private const val STACK_WIDTH = 560

        /** How narrow a field may get beside its buttons before they go under it. */
        private const val STRETCH_MIN = 120

        /** The monospaced face at the size of the theme's labels, which is scaled already: scaled again, the text would grow by a zoom twice. */
        fun monoFont(): Font = JBFont.create(Font(Font.MONOSPACED, Font.PLAIN, JBFont.label().size), false)

        /** How long a failed request waits for the server to finish logging why. */
        private const val LOG_GRACE_MS = 400L
        private val TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

        fun time(at: Long): String = TIME.format(Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()))

        fun suggestName(r: SavedRequest): String =
            runCatching {
                "${r.method} ${java.net.URI(r.url.replace(Regex("""\{\{[^}]+\}\}"""), "http://x")).path}"
            }.getOrDefault("${r.method} ${r.url}")
    }
}
