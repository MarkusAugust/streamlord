package io.github.markusaugust.streamlord.intellij

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.UIUtil
import io.github.markusaugust.streamlord.analysis.Requests
import io.github.markusaugust.streamlord.intellij.inspector.InspectorPanel
import io.github.markusaugust.streamlord.intellij.inspector.InspectorService
import io.github.markusaugust.streamlord.intellij.inspector.RequestStore
import io.github.markusaugust.streamlord.intellij.settings.StreamlordConfigurable
import io.github.markusaugust.streamlord.intellij.settings.StreamlordSettings
import javax.swing.JComboBox
import kotlin.test.assertFailsWith

/** Settings | Tools | Streamlord, driven as the Settings dialog drives it. */
class SettingsPageTest : BasePlatformTestCase() {
    private val settings get() = StreamlordSettings.getInstance(project)

    /** The page with its fields on screen; [use] gets the field that holds [text], and the page is closed after it. */
    private fun page(
        text: String,
        use: (page: StreamlordConfigurable, field: JBTextField) -> Unit,
    ) {
        val page = StreamlordConfigurable(project)
        try {
            val fields = UIUtil.findComponentsOfType(page.createComponent(), JBTextField::class.java)
            page.reset()
            use(page, fields.single { it.text == text })
        } finally {
            page.disposeUIResources()
        }
    }

    private fun write(
        path: String,
        text: String,
    ) = WriteCommandAction.runWriteCommandAction(project) {
        val base = RequestStore.getInstance(project).baseDir()!!
        val dir = VfsUtil.createDirectoryIfMissing(base, path.substringBeforeLast('/'))!!
        val name = path.substringAfterLast('/')
        VfsUtil.saveText(dir.findChild(name) ?: dir.createChildData(this, name), text)
    }

    // The project is shared with the other tests, which read these files.
    private fun remove(path: String) =
        WriteCommandAction.runWriteCommandAction(project) {
            RequestStore
                .getInstance(project)
                .baseDir()
                ?.findFileByRelativePath(path)
                ?.delete(this)
        }

    /** Let the UI thread take what the panel reads in the background, until [done] or ten seconds have passed. */
    private fun pumpUntil(done: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!done() && System.currentTimeMillis() < deadline) {
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            Thread.sleep(5)
        }
    }

    private fun names(panel: InspectorPanel) =
        UIUtil
            .findComponentsOfType(panel, JComboBox::class.java)
            .flatMap { combo -> (0 until combo.itemCount).map { combo.getItemAt(it).toString() } }

    private fun notes(panel: InspectorPanel) =
        UIUtil.findComponentsOfType(panel, JBTextArea::class.java).filter { !it.isEditable }.map { it.text }

    fun `test a requests file path with a problem is refused when the page is applied`() {
        val before = settings.state.requestsFile
        val problems =
            mapOf(
                "../elsewhere/inspector.json" to
                    "The requests file ../elsewhere/inspector.json leaves the project: use a path without .. in it.",
                "tools\\requests.json" to "The requests file tools\\requests.json holds a backslash: write the path with /.",
                ".streamlord/./env.json" to "The requests file cannot be .streamlord/env.json: that file holds the variables.",
            )
        try {
            for ((path, problem) in problems) {
                page(before) { page, field ->
                    field.text = path
                    assertTrue(page.isModified)
                    val refused = assertFailsWith<ConfigurationException> { page.apply() }
                    assertEquals(problem, refused.localizedMessage)
                    assertEquals(before, settings.state.requestsFile)
                }
            }
        } finally {
            settings.state.requestsFile = before
        }
    }

    fun `test a requests file path without a problem is stored when the page is applied`() {
        val before = settings.state.requestsFile
        try {
            page(before) { page, field ->
                assertFalse(page.isModified)
                field.text = "tools/requests.json"
                assertTrue(page.isModified)
                page.apply()
                assertEquals("tools/requests.json", settings.state.requestsFile)
                assertFalse(page.isModified)

                field.text = "../elsewhere/inspector.json"
                page.reset()
                assertEquals("tools/requests.json", field.text)
            }
        } finally {
            settings.state.requestsFile = before
        }
    }

    fun `test an open inspector shows the requests of the file the page was applied with`() {
        val before = settings.state.requestsFile
        val service = InspectorService.getInstance(project)
        val shown = service.panel
        write(Requests.REQUESTS_FILE, "{\"requests\": [{\"name\": \"in the default file\", \"url\": \"/a\"}]}")
        write("tools/requests.json", "{\"requests\": [{\"name\": \"in the other file\", \"url\": \"/b\"}]}")
        try {
            val panel = InspectorPanel(project, testRootDisposable)
            service.panel = panel
            pumpUntil { "in the default file" in names(panel) }
            assertTrue(names(panel).toString(), "in the default file" in names(panel))

            page(before) { page, field ->
                field.text = "tools/requests.json"
                page.apply()
            }
            pumpUntil { "in the other file" in names(panel) }
            assertTrue(names(panel).toString(), "in the other file" in names(panel))
            assertFalse(names(panel).toString(), "in the default file" in names(panel))
        } finally {
            service.panel = shown
            settings.state.requestsFile = before
            remove(Requests.REQUESTS_FILE)
            remove("tools/requests.json")
        }
    }

    fun `test an open inspector shows the default base URL the page was applied with`() {
        val before = settings.state.inspectorDefaultUrl
        val service = InspectorService.getInstance(project)
        val shown = service.panel
        remove(Requests.ENV_FILE)
        try {
            val panel = InspectorPanel(project, testRootDisposable)
            service.panel = panel
            val was = "baseUrl = ${before.trimEnd('/')}   (default)"
            pumpUntil { was in notes(panel) }
            assertTrue(notes(panel).toString(), was in notes(panel))

            page(before) { page, field ->
                field.text = "http://localhost:9321/"
                page.apply()
            }
            val now = "baseUrl = http://localhost:9321   (default)"
            pumpUntil { now in notes(panel) }
            assertTrue(notes(panel).toString(), now in notes(panel))
        } finally {
            service.panel = shown
            settings.state.inspectorDefaultUrl = before
        }
    }
}
