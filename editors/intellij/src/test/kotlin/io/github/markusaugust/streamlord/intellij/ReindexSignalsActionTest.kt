package io.github.markusaugust.streamlord.intellij

import com.intellij.notification.Notification
import com.intellij.notification.Notifications
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.testFramework.PlatformTestUtil
import java.util.concurrent.CopyOnWriteArrayList

/** Tools | Streamlord | Re-index Signals reads the project in the background and says what it found. */
class ReindexSignalsActionTest : StreamlordTestCase() {
    fun `test the action reads the project again and reports the names it found`() {
        myFixture.addFileToProject("a.html", """<div data-signals="{count: 0, total: 0}"></div>""")
        val shown = CopyOnWriteArrayList<String>()
        project.messageBus.connect(testRootDisposable).subscribe(
            Notifications.TOPIC,
            object : Notifications {
                override fun notify(notification: Notification) {
                    shown += notification.content
                }
            },
        )

        myFixture.testAction(ActionManager.getInstance().getAction("Streamlord.ReindexSignals"))

        val deadline = System.currentTimeMillis() + 10_000
        while (shown.isEmpty() && System.currentTimeMillis() < deadline) {
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            Thread.sleep(10)
        }
        assertEquals(listOf("Streamlord: indexed 2 signal names."), shown)
    }
}
