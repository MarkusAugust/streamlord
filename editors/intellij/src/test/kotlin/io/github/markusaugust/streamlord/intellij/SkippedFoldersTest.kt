package io.github.markusaugust.streamlord.intellij

import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PsiTestUtil
import io.github.markusaugust.streamlord.intellij.inspections.UnknownSignalInspection

/** Which folders the project's index leaves unread: the analysis's rule, counted from the root that holds the file. */
class SkippedFoldersTest : StreamlordTestCase() {
    override fun setUp() {
        super.setUp()
        myFixture.enableInspections(UnknownSignalInspection())
    }

    private fun unknown() = myFixture.doHighlighting().filter { it.description?.contains("defines the signal") == true }.map { it.text }

    fun `test a signal defined only in a hidden folder is unknown`() {
        myFixture.addFileToProject(".cache/page.html", "<div data-signals:total=\"0\"></div>")
        myFixture.configureByText("cart.html", "<p data-text=\"\$total\"></p>")
        assertEquals(listOf("\$total"), unknown())
    }

    fun `test a project that lies under a folder named build is read`() {
        val dir = FileUtil.createTempDirectory("streamlord", null).resolve("build/app").apply { mkdirs() }
        dir.resolve("page.html").writeText("<div data-signals:total=\"0\"></div>")
        val root = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(dir)!!
        PsiTestUtil.addSourceRoot(module, root)
        try {
            IndexingTestUtil.waitUntilIndexesAreReady(project)
            myFixture.configureByText("cart.html", "<p data-text=\"\$total\"></p>")
            assertEquals(emptyList<String>(), unknown())
        } finally {
            PsiTestUtil.removeContentEntry(module, root)
        }
    }
}
