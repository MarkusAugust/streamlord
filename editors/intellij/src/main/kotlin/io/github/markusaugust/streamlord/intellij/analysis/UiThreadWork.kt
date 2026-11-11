package io.github.markusaugust.streamlord.intellij.analysis

import com.intellij.openapi.application.ApplicationManager
import java.util.concurrent.atomic.AtomicInteger

/**
 * Counts the questions put to the IDE's indexes and the calls resolved on the UI thread, where the
 * platform wants neither, for a test to see that what the user does there asks none.
 */
internal object UiThreadWork {
    val count = AtomicInteger()

    fun note() {
        if (ApplicationManager.getApplication().isDispatchThread) count.incrementAndGet()
    }
}
