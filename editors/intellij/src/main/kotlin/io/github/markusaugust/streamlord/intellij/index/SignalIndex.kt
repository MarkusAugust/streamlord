package io.github.markusaugust.streamlord.intellij.index

import com.intellij.ide.highlighter.HtmlFileType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import io.github.markusaugust.streamlord.analysis.Selectors
import io.github.markusaugust.streamlord.analysis.SourceLanguage
import io.github.markusaugust.streamlord.analysis.collectSelectors
import io.github.markusaugust.streamlord.analysis.collectSignals
import org.jetbrains.kotlin.idea.KotlinFileType
import java.util.concurrent.ConcurrentHashMap

/**
 * Project-wide index of signal names and of element ids and classes, for completion. Heuristic
 * and generous, like the VS Code extension's: every Kotlin and HTML file in the project is read
 * once and again when it changes, up to a cap, and the names are offered with this file's first.
 */
@Service(Service.Level.PROJECT)
class SignalIndex(
    private val project: Project,
) {
    private class Entry(
        val stamp: Long,
        val length: Int,
        val signals: Set<String>,
        val selectors: Selectors,
    )

    private val entries = ConcurrentHashMap<String, Entry>()

    /** The names declared in one file, from its current text. */
    fun forFile(file: PsiFile): Set<String> = entryFor(file)?.signals ?: emptySet()

    fun selectorsForFile(file: PsiFile): Selectors = entryFor(file)?.selectors ?: Selectors.EMPTY

    /** Every signal name declared anywhere in the project. */
    fun all(): Set<String> {
        refresh()
        val out = LinkedHashSet<String>()
        for (e in entries.values) out += e.signals
        return out
    }

    fun allSelectors(): Selectors {
        refresh()
        val ids = LinkedHashSet<String>()
        val classes = LinkedHashSet<String>()
        for (e in entries.values) {
            ids += e.selectors.ids
            classes += e.selectors.classes
        }
        return Selectors(ids, classes)
    }

    /** Forget everything and read the project again; returns the number of signal names found. */
    fun rebuild(): Int {
        entries.clear()
        return all().size
    }

    private fun entryFor(file: PsiFile): Entry? {
        val language = languageOf(file.virtualFile ?: file.viewProvider.virtualFile) ?: return null
        val text = file.text
        val key = file.virtualFile?.path ?: file.name
        val stamp = file.modificationStamp
        val cached = entries[key]
        if (cached != null && cached.stamp == stamp && cached.length == text.length) return cached
        return index(key, stamp, text, language)
    }

    private fun index(
        key: String,
        stamp: Long,
        text: String,
        language: SourceLanguage,
    ): Entry {
        val entry = Entry(stamp, text.length, collectSignals(text, language), collectSelectors(text))
        entries[key] = entry
        return entry
    }

    /** Read every project file the index does not know at its current stamp. Callers hold a read action (completion, inspections). */
    private fun refresh() {
        // While the IDE indexes, the file type index is not there to ask; what is cached is offered.
        if (com.intellij.openapi.project.DumbService
                .isDumb(project)
        ) {
            return
        }
        val scope = GlobalSearchScope.projectScope(project)
        val files = ArrayList<VirtualFile>()
        files += FileTypeIndex.getFiles(KotlinFileType.INSTANCE, scope)
        files += FileTypeIndex.getFiles(HtmlFileType.INSTANCE, scope)
        for (vf in files.take(FILE_CAP)) {
            if (!vf.isValid || vf.isDirectory || vf.length > SIZE_CAP) continue
            if (vf.path.split('/').any { it in SKIPPED_DIRECTORIES }) continue
            val language = languageOf(vf) ?: continue
            val document = FileDocumentManager.getInstance().getCachedDocument(vf)
            val stamp = document?.modificationStamp ?: vf.modificationStamp
            val cached = entries[vf.path]
            if (cached != null && cached.stamp == stamp) continue
            val text = document?.text ?: runCatching { String(vf.contentsToByteArray(), vf.charset) }.getOrNull() ?: continue
            index(vf.path, stamp, text, language)
        }
    }

    /** Kotlin sources and markup, as the VS Code extension reads them; a Gradle script (`.kts`) declares no signals. */
    private fun languageOf(vf: VirtualFile): SourceLanguage? =
        when (vf.fileType) {
            KotlinFileType.INSTANCE -> if (vf.extension == "kt") SourceLanguage.KOTLIN else null
            HtmlFileType.INSTANCE -> SourceLanguage.HTML
            else -> if (vf.extension?.lowercase() in HTML_EXTENSIONS) SourceLanguage.HTML else null
        }

    companion object {
        private const val FILE_CAP = 5000
        private const val SIZE_CAP = 1_000_000L
        private val SKIPPED_DIRECTORIES = setOf("build", "node_modules", ".gradle", "dist", "out", "target")
        private val HTML_EXTENSIONS =
            setOf("html", "htm", "xhtml", "jte", "kte", "ftl", "ftlh", "vm", "mustache", "peb", "pebble", "twig", "hbs")

        fun getInstance(project: Project): SignalIndex = project.service()
    }
}
