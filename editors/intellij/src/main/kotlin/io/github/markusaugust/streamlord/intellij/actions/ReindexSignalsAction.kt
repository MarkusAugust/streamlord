package io.github.markusaugust.streamlord.intellij.actions

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import io.github.markusaugust.streamlord.intellij.Streamlord
import io.github.markusaugust.streamlord.intellij.index.SignalIndex

/** Tools | Streamlord | Re-index Signals: read every Kotlin and HTML file again. */
class ReindexSignalsAction : AnAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, "Streamlord: indexing signals", false) {
                override fun run(indicator: ProgressIndicator) {
                    val count = ReadAction.nonBlocking<Int> { SignalIndex.getInstance(project).rebuild() }.executeSynchronously()
                    NotificationGroupManager
                        .getInstance()
                        .getNotificationGroup(Streamlord.NOTIFICATION_GROUP)
                        .createNotification("Streamlord: indexed $count signal names.", NotificationType.INFORMATION)
                        .notify(project)
                }
            },
        )
    }
}
