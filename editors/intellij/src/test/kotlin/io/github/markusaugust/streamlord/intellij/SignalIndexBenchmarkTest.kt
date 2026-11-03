package io.github.markusaugust.streamlord.intellij

import com.intellij.ide.highlighter.HtmlFileType
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.markusaugust.streamlord.analysis.SourceLanguage
import io.github.markusaugust.streamlord.analysis.collectSignals
import io.github.markusaugust.streamlord.intellij.index.SignalIndex
import org.jetbrains.kotlin.idea.KotlinFileType

/**
 * Times the signal index on a real project, given as STREAMLORD_BENCH_DIR and skipped without it:
 * `STREAMLORD_BENCH_DIR=/path/to/project ./gradlew test --rerun --tests '*SignalIndexBenchmarkTest*' -i | grep BENCH`.
 * The directory is added as a source root, as an imported Gradle or Maven project has its sources;
 * the type index leaves out a `.kt` file outside source roots. A project with a `package.json` fails
 * the fixture's pointer check after the numbers are printed: the IDE's JavaScript support keeps one.
 */
class SignalIndexBenchmarkTest : BasePlatformTestCase() {
    fun `test signal index on a real project`() {
        val dir = System.getenv("STREAMLORD_BENCH_DIR") ?: return
        val root = LocalFileSystem.getInstance().refreshAndFindFileByPath(dir)!!
        VfsUtil.markDirtyAndRefresh(false, true, true, root)
        val start = System.nanoTime()
        PsiTestUtil.addSourceRoot(module, root)
        try {
            IndexingTestUtil.waitUntilIndexesAreReady(project)
            println("BENCH ide indexing (not ours): ${ms(start)} ms")
            measure()
        } finally {
            PsiTestUtil.removeContentEntry(module, root)
        }
    }

    private fun measure() {
        val scope = GlobalSearchScope.projectScope(project)
        val (kt, html) =
            runReadActionBlocking {
                FileTypeIndex.getFiles(KotlinFileType.INSTANCE, scope).size to FileTypeIndex.getFiles(HtmlFileType.INSTANCE, scope).size
            }
        println("BENCH files: $kt kt, $html html")

        val index = SignalIndex.getInstance(project)
        val rebuilds = timed(5) { println("BENCH rebuild found ${index.rebuild()} signal names") }
        println("BENCH rebuild ms: $rebuilds")
        println("BENCH refresh, nothing changed, ms: ${timed(20) { index.all() }}")

        // One keystroke in one Kotlin file of the project, then what completion or the inspection asks.
        val vf = runReadActionBlocking { FileTypeIndex.getFiles(KotlinFileType.INSTANCE, scope).first { it.length > 0 } }
        val document = runReadActionBlocking { FileDocumentManager.getInstance().getDocument(vf)!! }
        val psi = runReadActionBlocking { PsiManager.getInstance(project).findFile(vf)!! }
        val afterEdit =
            (1..20).map {
                WriteCommandAction.runWriteCommandAction(project) {
                    document.insertString(0, " ")
                    PsiDocumentManager.getInstance(project).commitDocument(document)
                }
                timed(1) { index.definitionsSeenFrom(psi) }.single()
            }
        println("BENCH definitionsSeenFrom after one edit, ms: $afterEdit")

        // What find usages and go to declaration ask of the stored positions.
        for (word in listOf("count", "value", "loading", "selectedId")) {
            var found = 0
            val took = timed(1) { found = index.declarationsOf(word).size + index.readsOf(word).size }.single()
            println("BENCH stored places of '$word': $found, $took ms")
        }

        // What a references search does without stored positions: the word index's candidates, each read again.
        for (word in listOf("count", "value", "loading", "selectedId")) {
            var candidates = 0
            var declaring = 0
            val took =
                timed(1) {
                    PsiSearchHelper.getInstance(project).processAllFilesWithWordInText(word, scope, { file ->
                        candidates++
                        val language = if (file.fileType == KotlinFileType.INSTANCE) SourceLanguage.KOTLIN else SourceLanguage.HTML
                        if (word in collectSignals(file.text, language)) declaring++
                        true
                    }, true)
                }.single()
            println("BENCH search '$word': $candidates candidates, $declaring declare it, $took ms")
        }
    }

    private fun timed(
        times: Int,
        action: () -> Unit,
    ): List<Long> =
        (1..times).map {
            val start = System.nanoTime()
            runReadActionBlocking(action)
            ms(start)
        }

    private fun ms(start: Long) = (System.nanoTime() - start) / 1_000_000
}
