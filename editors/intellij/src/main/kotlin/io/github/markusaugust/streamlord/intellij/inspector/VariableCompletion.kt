package io.github.markusaugust.streamlord.intellij.inspector

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.awt.RelativePoint
import io.github.markusaugust.streamlord.analysis.Requests
import java.awt.Point
import javax.swing.JList
import javax.swing.event.DocumentEvent
import javax.swing.text.JTextComponent

/**
 * Offers the variables after `{{` in an inspector field. What fits is decided by
 * `Requests.variableCompletions`, the rule the VS Code extension runs too; this only shows the
 * list and puts the choice in.
 */
internal class VariableCompletion(
    private val field: JTextComponent,
    private val kind: Requests.Field,
    private val variables: () -> List<Requests.Variable>,
    /** False while the panel fills the field itself, so loading a request opens no list. */
    private val enabled: () -> Boolean,
) {
    private var popup: JBPopup? = null

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
                    if (e.type == DocumentEvent.EventType.INSERT && enabled()) ApplicationManager.getApplication().invokeLater { offer() }
                }
            },
        )
    }

    private fun offer() {
        popup?.cancel()
        popup = null
        if (!field.isShowing) return
        val found = Requests.variableCompletions(field.text, field.caretPosition, kind, variables()) ?: return
        val shown =
            JBPopupFactory
                .getInstance()
                .createPopupChooserBuilder(found.items)
                .setRenderer(renderer)
                .setItemChosenCallback { insert(found, it) }
                .createPopup()
        popup = shown
        val at = field.modelToView2D(field.caretPosition)
        shown.show(RelativePoint(field, Point(at.x.toInt(), (at.y + at.height).toInt())))
    }

    private fun describe(v: Requests.Variable): String {
        val default = if (v.source == Requests.VariableSource.DEFAULT) "   (default)" else ""
        return "{{${v.name}}}   ${v.value.replace("\n", "; ")}$default"
    }

    private fun insert(
        found: Requests.Completion,
        v: Requests.Variable,
    ) {
        val text = "{{${v.name}}}"
        field.document.remove(found.from, found.to - found.from)
        field.document.insertString(found.from, text, null)
        field.caretPosition = found.from + text.length
        field.requestFocusInWindow()
    }
}
