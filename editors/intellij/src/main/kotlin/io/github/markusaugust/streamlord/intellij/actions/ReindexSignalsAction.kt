package io.github.markusaugust.streamlord.intellij.actions

import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.progress.currentThreadCoroutineScope
import com.intellij.platform.ide.progress.withBackgroundProgress
import io.github.markusaugust.streamlord.intellij.Streamlord
import io.github.markusaugust.streamlord.intellij.index.SignalIndex
import kotlinx.coroutines.launch

/** Tools | Streamlord | Re-index Signals: read every Kotlin and HTML file again. */
class ReindexSignalsAction : AnAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        currentThreadCoroutineScope().launch {
            val index = SignalIndex.getInstance(project)
            val count =
                withBackgroundProgress(project, "Streamlord: indexing signals") {
                    // Forgotten once: the read gives way to a write and starts over after it, and keeps what it had read.
                    index.forget()
                    smartReadAction(project) { index.all().size }
                }
            Notification(Streamlord.NOTIFICATION_GROUP, "Streamlord: indexed $count signal names.", NotificationType.INFORMATION)
                .notify(project)
        }
    }
}
