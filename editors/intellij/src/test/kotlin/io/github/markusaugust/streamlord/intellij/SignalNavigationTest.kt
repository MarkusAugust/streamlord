package io.github.markusaugust.streamlord.intellij

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.markusaugust.streamlord.intellij.index.SignalIndex

class SignalNavigationTest : BasePlatformTestCase() {
    fun `test the index keeps where each signal is defined and read`() {
        val page =
            myFixture.addFileToProject(
                "page.html",
                "<div data-signals:count=\"0\"><span data-text=\"\$count + \$user.name\"></span></div>",
            )
        val view =
            myFixture.addFileToProject(
                "View.kt",
                "import io.github.markusaugust.streamlord.html.*\nfun f() = div { dataSignals(\"user\" to 1); dataText(\"\${'\$'}count\") }",
            )
        val index = SignalIndex.getInstance(project)

        val declared = index.declarationsOf("count").map { it.file.name to page.text.substring(it.start, it.end) }
        assertEquals(listOf("page.html" to "count"), declared)
        val reads = index.readsOf("count").map { it.file.name }.sorted()
        assertEquals(listOf("View.kt", "page.html"), reads)
        // A read into a signal is a read of it.
        val user = index.readsOf("user").single()
        assertEquals("\$user.name", page.text.substring(user.start, user.end))
        assertEquals("user", view.text.substring(index.declarationsOf("user").single().let { it.start until it.end }))
    }
}
