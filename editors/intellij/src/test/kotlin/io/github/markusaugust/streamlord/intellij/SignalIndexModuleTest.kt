package io.github.markusaugust.streamlord.intellij

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.module.Module
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.testFramework.DumbModeTestUtils
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.PsiTestUtil
import io.github.markusaugust.streamlord.intellij.index.SignalIndex
import io.github.markusaugust.streamlord.intellij.navigation.SignalSites
import io.github.markusaugust.streamlord.intellij.navigation.SignalTarget
import org.jetbrains.kotlin.psi.KtFile
import java.io.File

/**
 * In a module without streamlord-html the DSL's short helpers, `set` and `toggle`, are somebody else's, and the project's
 * signals leave out what they define, while a `data*` helper defines as ever. Which module a file is in is asked each time,
 * so a move or a library added counts at once.
 */
class SignalIndexModuleTest : HeavyPlatformTestCase() {
    fun `test a short helper defines no signal in a module without the dsl`() {
        val base = getTempDir().createVirtualDir()
        val (webRoot, plainRoot) =
            WriteAction.computeAndWait<Pair<VirtualFile, VirtualFile>, Throwable> {
                VfsUtil.createDirectoryIfMissing(base, "web") to VfsUtil.createDirectoryIfMissing(base, "plain")
            }
        withLibraries(createModule("web"), webRoot, StreamlordProject.libraries)
        val plain = createModule("plain")
        withLibraries(plain, plainRoot, StreamlordProject.libraries.filter { it.first != "streamlord-html" })
        val page =
            psi(
                file(
                    webRoot,
                    "Page.kt",
                    "import io.github.markusaugust.streamlord.html.*\nimport kotlinx.html.*\n" +
                        "fun FlowContent.f() { button { dataText(\"\\\$ghost + \\\$sdk + \\\$own\") } }",
                ),
            )
        // The DSL being absent, `set` resolves to nothing here; a helper of the project's own writes data-bind as the DSL's
        // does, and the official SDK's patchSignals defines signals all the same.
        val other =
            file(
                plainRoot,
                "Other.kt",
                "import kotlinx.html.*\nfun HTMLTag.dataBind(name: String) { attributes[\"data-bind\"] = name }\n" +
                    "fun FlowContent.x() {\n    set(\"ghost\", 1)\n    input { dataBind(\"own\") }\n    patchSignals(\"sdk\" to 1)\n}",
            )
        val index = SignalIndex.getInstance(project)

        assertFalse(index.definitionsSeenFrom(page).contains("ghost"))
        // The page reads it, so completion offers it from there; the file that sets it offers it no more.
        assertFalse(index.forFile(psi(other)).contains("ghost"))
        assertEmpty(index.declarationsOf("ghost"))
        assertEmpty(SignalTarget.of(page, "ghost"))
        assertEmpty(index.declarationsIn(psi(other)).filter { it.name == "ghost" })
        assertTrue("a core call defines its signal in every module", index.definitionsSeenFrom(page).contains("sdk"))
        assertTrue("the project's own data* helper defines its signal", index.definitionsSeenFrom(page).contains("own"))
        // Go to declaration lands on it.
        assertNotEmpty(SignalTarget.of(page, "own"))

        WriteAction.runAndWait<Throwable> { other.move(this, webRoot) }
        assertTrue("moved to the module with the DSL", index.definitionsSeenFrom(page).contains("ghost"))
        assertTrue(index.forFile(psi(other)).contains("ghost"))
        assertNotEmpty(index.declarationsOf("ghost"))

        WriteAction.runAndWait<Throwable> { other.move(this, plainRoot) }
        assertFalse("moved back", index.definitionsSeenFrom(page).contains("ghost"))

        withLibraries(plain, null, StreamlordProject.libraries.filter { it.first == "streamlord-html" })
        assertTrue("the DSL added to the module", index.definitionsSeenFrom(page).contains("ghost"))
    }

    fun `test while the ide indexes a short helper counts`() {
        val root =
            WriteAction.computeAndWait<VirtualFile, Throwable> {
                VfsUtil.createDirectoryIfMissing(
                    getTempDir().createVirtualDir(),
                    "plain",
                )
            }
        withLibraries(createModule("plain"), root, StreamlordProject.libraries.filter { it.first != "streamlord-html" })
        val page = psi(file(root, "Page.kt", "fun x() = set(\"ghost\", 1)"))
        val index = SignalIndex.getInstance(project)
        assertFalse(index.definitionsSeenFrom(page).contains("ghost"))
        // The module cannot tell then, and an unknown-signal warning is worse than a name too many.
        DumbModeTestUtils.runInDumbModeSynchronously(project) {
            assertTrue(index.definitionsSeenFrom(page).contains("ghost"))
        }
    }

    fun `test an edit that changes only what the module counts reaches every file`() {
        val root =
            WriteAction.computeAndWait<VirtualFile, Throwable> {
                VfsUtil.createDirectoryIfMissing(
                    getTempDir().createVirtualDir(),
                    "plain",
                )
            }
        withLibraries(createModule("plain"), root, StreamlordProject.libraries.filter { it.first != "streamlord-html" })
        val other = file(root, "Other.kt", "fun x() = set(\"ghost\", 1)")
        val page = PsiManager.getInstance(project).findFile(file(root, "page.html", "<b data-text=\"\$ghost\"></b>"))!!
        val index = SignalIndex.getInstance(project)
        assertFalse(index.definitionsSeenFrom(page).contains("ghost"))
        // What the file defines in every module stays `ghost`; what it defines here goes from nothing to `ghost`.
        WriteAction.runAndWait<Throwable> { VfsUtil.saveText(other, "fun x() {\n    set(\"ghost\", 1)\n    dataBind(\"ghost\")\n}") }
        assertTrue(index.definitionsSeenFrom(page).contains("ghost"))
    }

    fun `test the official sdk's patchSignals is a place go to declaration lands on`() {
        val root =
            WriteAction.computeAndWait<VirtualFile, Throwable> {
                VfsUtil.createDirectoryIfMissing(
                    getTempDir().createVirtualDir(),
                    "sdk",
                )
            }
        withLibraries(createModule("sdk"), root, emptyList())
        file(root, "Sdk.kt", "package dev.datastar.kotlin.sdk\nclass ServerSentEventGenerator { fun patchSignals(signals: String) {} }")
        file(
            root,
            "Handler.kt",
            "import dev.datastar.kotlin.sdk.ServerSentEventGenerator\n" +
                "fun h(g: ServerSentEventGenerator) = g.patchSignals(\"\"\"{\"sdk\": 1}\"\"\")",
        )
        val page = PsiManager.getInstance(project).findFile(file(root, "page.html", "<b data-text=\"\$sdk\"></b>"))!!
        assertNotEmpty(SignalTarget.of(page, "sdk"))
    }

    fun `test a read defined only in somebody else's set is no link`() {
        val root =
            WriteAction.computeAndWait<VirtualFile, Throwable> {
                VfsUtil.createDirectoryIfMissing(
                    getTempDir().createVirtualDir(),
                    "web",
                )
            }
        withLibraries(createModule("web"), root, StreamlordProject.libraries)
        // `set` through an implicit receiver: the text counts it, so `$ghost` gets no warning, but it resolves to Headers.set.
        file(root, "Headers.kt", "class Headers { fun set(name: String, value: Any) {} }\nfun headers(block: Headers.() -> Unit) {}")
        file(root, "Other.kt", "fun x() = headers { set(\"ghost\", 1) }")
        val page =
            psi(
                file(
                    root,
                    "Page.kt",
                    "import io.github.markusaugust.streamlord.html.*\nimport kotlinx.html.*\n" +
                        "fun FlowContent.f() { button { dataText(\"\\\$ghost\") } }",
                ),
            )
        assertTrue(SignalIndex.getInstance(project).definitionsSeenFrom(page).contains("ghost"))
        assertEmpty(SignalTarget.of(page, "ghost"))
        assertEmpty("no reference that resolves to nothing", SignalSites.of(page).filter { it.signal == "ghost" })
    }

    private fun file(
        root: VirtualFile,
        name: String,
        text: String,
    ): VirtualFile =
        WriteAction.computeAndWait<VirtualFile, Throwable> { root.createChildData(this, name).also { VfsUtil.saveText(it, text) } }

    private fun psi(vf: VirtualFile): KtFile = PsiManager.getInstance(project).findFile(vf) as KtFile

    private fun withLibraries(
        module: Module,
        root: VirtualFile?,
        libraries: List<Pair<String, () -> File>>,
    ) {
        root?.let { PsiTestUtil.addSourceRoot(module, it) }
        for ((name, jar) in libraries) jar().let { PsiTestUtil.addLibrary(module, name, it.parent, it.name) }
    }
}
