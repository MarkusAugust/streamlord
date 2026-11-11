package io.github.markusaugust.streamlord.intellij

import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import io.github.markusaugust.streamlord.intellij.index.SignalIndex

/** The index reads the whole project under a read action, so the platform must be able to stop it for a write. */
class SignalIndexCancellationTest : StreamlordTestCase() {
    fun `test reading the project again stops when it is cancelled and is done when asked next`() {
        myFixture.addFileToProject("a.html", """<div data-signals="{count: 0}"></div>""")
        myFixture.configureByText("b.html", """<div data-signals="{total: 0}"></div>""")
        val index = SignalIndex.getInstance(project)
        assertEquals(setOf("count", "total"), index.all())

        // An edit in the editor: the files are the same, so the index goes through them without asking the platform anything.
        myFixture.type("<p data-signals=\"{sum: 0}\"></p>")
        val indicator = EmptyProgressIndicator()
        try {
            ProgressManager.getInstance().runProcess({
                indicator.cancel()
                index.all()
            }, indicator)
            fail("a cancelled read of the project went on")
        } catch (_: ProcessCanceledException) {
        }

        assertEquals(setOf("count", "total", "sum"), index.all())
    }
}
