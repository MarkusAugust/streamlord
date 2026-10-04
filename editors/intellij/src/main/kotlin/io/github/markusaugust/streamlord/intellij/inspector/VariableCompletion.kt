package io.github.markusaugust.streamlord.intellij.inspector

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.PopupChooserBuilder
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBList
import com.intellij.util.Consumer
import io.github.markusaugust.streamlord.analysis.Requests
import java.awt.Point
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.JList
import javax.swing.event.DocumentEvent
import javax.swing.text.JTextComponent

/**
 * Offers the variables after `{{` in an inspector field. What fits is decided by
 * `Requests.variableCompletions`, the rule the VS Code extension runs too; this only shows the
 * list and puts the choice in. The list never takes the focus: typing goes on in the field and
 * narrows the list, and the arrows, Enter, Tab and Escape reach it through the field.
 */
internal class VariableCompletion(
    private val field: JTextComponent,
    private val kind: Requests.Field,
    private val variables: () -> List<Requests.Variable>,
    /** False while the panel fills the field itself, so loading a request opens no list. */
    private val enabled: () -> Boolean,
) {
    private var popup: JBPopup? = null
    private var list: JBList<Requests.Variable>? = null
    private var offered: Requests.Completion? = null

    private val renderer =
        object : SimpleListCellRenderer<Requests.Variable>() {
            override fun customize(
                list: JList<out Requests.Variable>,
                value: Requests.Variable?,
                index: Int,
                selected: Boolean,
                hasFocus: Boolean,
            ) {
                text = value?.let { describe(it) } ?: ""
            }
        }

    init {
        field.document.addDocumentListener(
            object : DocumentAdapter() {
                override fun textChanged(e: DocumentEvent) {
                    if (enabled()) ApplicationManager.getApplication().invokeLater { offer() }
                }
            },
        )
        field.addKeyListener(
            object : KeyAdapter() {
                override fun keyPressed(e: KeyEvent) {
                    if (handle(e.keyCode)) e.consume()
                }
            },
        )
    }

    /** A key typed in the field while the list is open; true when the list took it. */
    private fun handle(key: Int): Boolean {
        val shown = popup?.takeIf { it.isVisible } ?: return false
        val items = list ?: return false
        val size = items.model.size
        when (key) {
            KeyEvent.VK_DOWN -> items.selectedIndex = (items.selectedIndex + 1) % size
            KeyEvent.VK_UP -> items.selectedIndex = (items.selectedIndex + size - 1) % size
            KeyEvent.VK_ENTER, KeyEvent.VK_TAB -> choose(items.selectedValue)
            KeyEvent.VK_ESCAPE -> shown.cancel()
            else -> return false
        }
        return true
    }

    private fun offer() {
        close()
        if (!field.isShowing) return
        val found = Requests.variableCompletions(field.text, field.caretPosition, kind, variables()) ?: return
        val items = JBList(found.items).apply { cellRenderer = renderer }
        items.selectedIndex = 0
        val shown =
            PopupChooserBuilder(items)
                .setRequestFocus(false)
                .setItemChosenCallback(Consumer<Requests.Variable> { choose(it) })
                .createPopup()
        popup = shown
        list = items
        offered = found
        val at = field.modelToView2D(field.caretPosition)
        shown.show(RelativePoint(field, Point(at.x.toInt(), (at.y + at.height).toInt())))
    }

    private fun close() {
        popup?.cancel()
        popup = null
        list = null
        offered = null
    }

    private fun choose(v: Requests.Variable?) {
        val found = offered
        close()
        if (v == null || found == null) return
        val text = "{{${v.name}}}"
        field.document.remove(found.from, found.to - found.from)
        field.document.insertString(found.from, text, null)
        field.caretPosition = found.from + text.length
        field.requestFocusInWindow()
    }

    private fun describe(v: Requests.Variable): String {
        val default = if (v.source == Requests.VariableSource.DEFAULT) "   (default)" else ""
        return "{{${v.name}}}   ${v.value.replace("\n", "; ")}$default"
    }
}
