package io.github.markusaugust.streamlord.intellij

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.PsiTestUtil
import io.github.markusaugust.streamlord.intellij.analysis.ProjectLibraries
import io.github.markusaugust.streamlord.intellij.analysis.StreamlordAnalysis
import io.github.markusaugust.streamlord.intellij.analysis.StreamlordLibrary
import io.github.markusaugust.streamlord.intellij.spelling.DatastarSpellcheckingStrategy
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import java.io.File

/**
 * A file moved to another module is judged by that module's libraries at once: a move changes no library and no text, so
 * nothing kept for the file may outlive it. Two modules on disk, as only a heavy project can hold them.
 */
class ModuleMoveTest : HeavyPlatformTestCase() {
    fun `test a file moved to a module without the dsl is judged by its new module`() {
        val base = getTempDir().createVirtualDir()
        val (webRoot, plainRoot) =
            WriteAction.computeAndWait<Pair<VirtualFile, VirtualFile>, Throwable> {
                VfsUtil.createDirectoryIfMissing(base, "web") to VfsUtil.createDirectoryIfMissing(base, "plain")
            }
        // Both have core, so both have call sites to resolve; only web has the DSL.
        withLibraries(createModule("web"), webRoot, StreamlordProject.libraries)
        withLibraries(createModule("plain"), plainRoot, StreamlordProject.libraries.filter { it.first != "streamlord-html" })
        val text =
            "import kotlinx.html.*\n" +
                "fun FlowContent.f() { button { dataText(\"\\\$count + ' Lagree'\") } }\n" +
                "fun g() = patchElements(\"<div>no id</div>\")"
        val vf =
            WriteAction.computeAndWait<VirtualFile, Throwable> {
                webRoot.createChildData(this, "Page.kt").also { VfsUtil.saveText(it, text) }
            }
        val file = PsiManager.getInstance(project).findFile(vf) as KtFile
        val analysis = StreamlordAnalysis.getInstance(project)

        assertEquals("web", ModuleUtilCore.findModuleForPsiElement(file)?.name)
        assertEquals(true, ProjectLibraries.known(file, StreamlordLibrary.HTML))
        assertEquals(true, analysis.options(file).htmlDsl)
        assertEquals(1, claimed(file))
        val before = analysis.resolves.get()
        analysis.kotlinIssues(file)
        val resolvedInWeb = analysis.resolves.get() - before
        assertTrue("the calls with an issue are resolved", resolvedInWeb > 0)

        WriteAction.runAndWait<Throwable> { vf.move(this, plainRoot) }

        assertEquals("plain", ModuleUtilCore.findModuleForPsiElement(file)?.name)
        assertEquals(false, ProjectLibraries.known(file, StreamlordLibrary.HTML))
        assertEquals(true, ProjectLibraries.known(file, StreamlordLibrary.CORE))
        assertEquals(false, analysis.options(file).htmlDsl)
        assertEquals("the DSL-named call's string is prose here", 0, claimed(file))
        // What a call resolves to is the module's classpath's: the verdicts are found again there.
        val moved = analysis.resolves.get()
        analysis.kotlinIssues(file)
        assertTrue("resolved again in the new module", analysis.resolves.get() > moved)
    }

    private fun withLibraries(
        module: Module,
        root: VirtualFile,
        libraries: List<Pair<String, () -> File>>,
    ) {
        PsiTestUtil.addSourceRoot(module, root)
        for ((name, jar) in libraries) jar().let { PsiTestUtil.addLibrary(module, name, it.parent, it.name) }
    }

    private fun claimed(file: KtFile): Int {
        val strategy = DatastarSpellcheckingStrategy()
        var n = 0
        PsiTreeUtil.processElements(file) {
            if (strategy.isMyContext(it) && it is KtStringTemplateExpression) n++
            true
        }
        return n
    }
}
