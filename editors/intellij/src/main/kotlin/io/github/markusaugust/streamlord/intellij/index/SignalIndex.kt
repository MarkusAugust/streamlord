package io.github.markusaugust.streamlord.intellij.index

import com.intellij.ide.highlighter.HtmlFileType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiFile
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.util.ForcefulReparseModificationTracker
import io.github.markusaugust.streamlord.analysis.Selectors
import io.github.markusaugust.streamlord.analysis.SignalDeclaration
import io.github.markusaugust.streamlord.analysis.SignalReference
import io.github.markusaugust.streamlord.analysis.SourceLanguage
import io.github.markusaugust.streamlord.analysis.collectSelectors
import io.github.markusaugust.streamlord.analysis.collectSignalDefinitions
import io.github.markusaugust.streamlord.analysis.collectSignals
import io.github.markusaugust.streamlord.analysis.signalDeclarations
import io.github.markusaugust.streamlord.intellij.analysis.StreamlordAnalysis
import io.github.markusaugust.streamlord.intellij.settings.StreamlordSettings
import org.jetbrains.kotlin.idea.KotlinFileType
import java.util.concurrent.ConcurrentHashMap

/**
 * Project-wide index of signal names and of element ids and classes, for completion. Heuristic
 * and generous, like the VS Code extension's: every Kotlin and HTML file in the project is read
 * once and again when it changes, up to a cap, and the names are offered with this file's first.
 * It keeps where each signal is defined and read as well, so that go to declaration, find usages
 * and rename read no file again: a word search re-reads thousands of files in a large project.
 */
@Service(Service.Level.PROJECT)
class SignalIndex(
    private val project: Project,
) {
    private class Entry(
        val file: VirtualFile?,
        val stamp: Long,
        val length: Int,
        val prefix: String,
        val signals: Set<String>,
        val definitions: Set<String>,
        val selectors: Selectors,
    ) {
        /** Where the file defines and reads signals: worked out on the first search, which completion never asks for. */
        @Volatile
        var places: Places? = null
    }

    private class Places(
        val declarations: List<SignalDeclaration>,
        val reads: List<SignalReference>,
    )

    /** A place in a project file where a signal is defined or read, by the file's offsets. */
    class Place(
        val file: VirtualFile,
        val start: Int,
        val end: Int,
        val name: String,
    )

    private val entries = ConcurrentHashMap<String, Entry>()

    /** The union of every entry's definitions, dropped whenever an entry's definitions change or an entry goes. */
    @Volatile
    private var definitionsUnion: Set<String>? = null

    /** Bumped with each drop, so a union built from entries that changed meanwhile is not kept. Guarded by [unionLock]. */
    private var unionGeneration = 0L
    private val unionLock = Any()

    /** The counts of the last full read, so a pass over many files reads the project once. */
    @Volatile
    private var refreshedAt: List<Long> = emptyList()

    /** The files to read, with the counts they were listed at: an edit changes a file, not which files there are. */
    @Volatile
    private var listed: Pair<List<Long>, List<VirtualFile>> = emptyList<Long>() to emptyList()

    /** The names declared in one file, from its current text. */
    fun forFile(file: PsiFile): Set<String> = entryFor(file)?.signals ?: emptySet()

    fun selectorsForFile(file: PsiFile): Selectors = entryFor(file)?.selectors ?: Selectors.EMPTY

    /** Where [file] defines signals, from its current text. */
    fun declarationsIn(file: PsiFile): List<SignalDeclaration> =
        entryFor(file)?.let { placesOf(it, file.text) }?.declarations ?: emptyList()

    /** Where [file] reads signals, from its current text, calls to something other than Streamlord among them. */
    fun readsIn(file: PsiFile): List<SignalReference> = entryFor(file)?.let { placesOf(it, file.text) }?.reads ?: emptyList()

    /** Every place in the project that defines the signal [name]. */
    fun declarationsOf(name: String): List<Place> {
        refresh()
        return places { e -> placesOf(e, null)?.declarations?.filter { it.name == name }?.map { Triple(it.start, it.end, it.name) } }
    }

    /** Every place in the project that reads the signal [name] or reads into it, `$user.name` for `user`. */
    fun readsOf(name: String): List<Place> {
        refresh()
        val inside = "$name."
        return places { e ->
            placesOf(e, null)?.reads?.filter { it.name == name || it.name.startsWith(inside) }?.map { Triple(it.start, it.end, it.name) }
        }
    }

    /**
     * Where [e]'s file defines and reads signals, from [text] or else from the file as it is now.
     * They are kept only when that is the text [e] was made from; a caller checks each place against
     * the file's own references.
     */
    private fun placesOf(
        e: Entry,
        text: String?,
    ): Places? {
        e.places?.let { return it }
        val vf = e.file ?: return null
        val language = languageOf(vf) ?: return null
        val document = FileDocumentManager.getInstance().getCachedDocument(vf)
        val stamp = document?.modificationStamp ?: vf.modificationStamp
        val current = text ?: document?.text ?: runCatching { String(vf.contentsToByteArray(), vf.charset) }.getOrNull() ?: return null
        val analysis = StreamlordAnalysis.getInstance(project)
        val reads =
            when (language) {
                SourceLanguage.KOTLIN -> analysis.analyzer.signalReferencesInKotlin(current, analysis.options())
                SourceLanguage.HTML -> analysis.analyzer.signalReferencesInHtml(current, analysis.options())
            }.map { SignalReference(it.start, it.end, it.name) }
        val places = Places(signalDeclarations(current, language), reads)
        if (current.length == e.length && (text != null || stamp == e.stamp)) e.places = places
        return places
    }

    private fun places(of: (Entry) -> List<Triple<Int, Int, String>>?): List<Place> {
        val out = ArrayList<Place>()
        for (e in entries.values) {
            val file = e.file?.takeIf { it.isValid } ?: continue
            for ((start, end, name) in of(e) ?: continue) out += Place(file, start, end, name)
        }
        return out
    }

    /** Every signal name declared anywhere in the project. */
    fun all(): Set<String> {
        refresh()
        val out = LinkedHashSet<String>()
        for (e in entries.values) out += e.signals
        return out
    }

    /**
     * Every signal name defined anywhere in the project, [file] read from its current text: what a
     * `$name` is checked against. No reads, as [all] has. Cached until a definition changes.
     */
    fun definitionsSeenFrom(file: PsiFile): Set<String> {
        refresh()
        val own = entryFor(file)?.definitions ?: emptySet()
        definitionsUnion?.let { if (it.containsAll(own)) return it }
        val generation = synchronized(unionLock) { unionGeneration }
        val out = HashSet<String>()
        for (e in entries.values) out += e.definitions
        out += own
        synchronized(unionLock) { if (unionGeneration == generation) definitionsUnion = out }
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
        dropUnion()
        refreshedAt = emptyList()
        listed = emptyList<Long>() to emptyList()
        return all().size
    }

    private fun entryFor(file: PsiFile): Entry? {
        val language = languageOf(file.virtualFile ?: file.viewProvider.virtualFile) ?: return null
        val text = file.text
        val key = file.virtualFile?.path ?: file.name
        val stamp = file.modificationStamp
        val cached = entries[key]
        if (cached != null && cached.stamp == stamp && cached.length == text.length && cached.prefix == prefix()) return cached
        return index(key, file.virtualFile, stamp, text, language)
    }

    private fun index(
        key: String,
        file: VirtualFile?,
        stamp: Long,
        text: String,
        language: SourceLanguage,
    ): Entry {
        val entry =
            Entry(
                file,
                stamp,
                text.length,
                prefix(),
                collectSignals(text, language),
                collectSignalDefinitions(text, language),
                collectSelectors(text),
            )
        val previous = entries.put(key, entry)
        if (previous?.definitions != entry.definitions) dropUnion()
        return entry
    }

    /** Read every project file the index does not know at its current stamp. Callers hold a read action (completion, inspections). */
    private fun refresh() {
        // While the IDE indexes, the file type index is not there to ask; what is cached is offered.
        if (DumbService.isDumb(project)) return
        // Read the project again only when something in it changed since the last read: its PSI, a file
        // on disk (a `git pull` touches files the IDE never parsed), or the template extensions. Every
        // count but the PSI's also lists the files again: the roots and file types decide what is in the
        // project, an overridden file type moves only the forced reparse count, and indexing that
        // finishes may find files a listing made while it scanned did not.
        val counts =
            listOf(
                PsiModificationTracker.getInstance(project).modificationCount,
                VirtualFileManager.getInstance().modificationCount,
                StreamlordSettings.getInstance(project).tracker.modificationCount,
                ProjectRootManager.getInstance(project).modificationCount,
                ForcefulReparseModificationTracker.getInstance().modificationCount,
                DumbService.getInstance(project).modificationTracker.modificationCount,
            )
        if (counts == refreshedAt) return
        val seen = HashSet<String>()
        for (vf in projectFiles(counts.drop(1))) {
            if (!vf.isValid) continue
            val language = languageOf(vf) ?: continue
            seen += vf.path
            val document = FileDocumentManager.getInstance().getCachedDocument(vf)
            val stamp = document?.modificationStamp ?: vf.modificationStamp
            val cached = entries[vf.path]
            if (cached != null && cached.stamp == stamp && cached.prefix == prefix()) continue
            val text = document?.text ?: runCatching { String(vf.contentsToByteArray(), vf.charset) }.getOrNull() ?: continue
            index(vf.path, vf, stamp, text, language)
        }
        // A file deleted, renamed or moved out of the project defines nothing any more.
        if (entries.keys.retainAll(seen)) dropUnion()
        refreshedAt = counts
    }

    private fun projectFiles(counts: List<Long>): List<VirtualFile> {
        listed.let { (at, files) -> if (at == counts) return files }
        val scope = GlobalSearchScope.projectScope(project)
        // Markup first, and the cap on what is kept rather than on what is listed: the pages that define
        // signals must not be crowded out by Kotlin sources or by files under build directories.
        val markup = LinkedHashSet<VirtualFile>()
        markup += FileTypeIndex.getFiles(HtmlFileType.INSTANCE, scope)
        // A template file is markup whatever type the IDE gives it, so it is found by its extension.
        for (extension in markupExtensions()) markup += FilenameIndex.getAllFilesByExt(project, extension, scope)
        val files =
            markup.asSequence().filter(::indexable).take(FILE_CAP) +
                FileTypeIndex
                    .getFiles(KotlinFileType.INSTANCE, scope)
                    .asSequence()
                    .filter(::indexable)
                    .take(FILE_CAP)
        return files.toList().also { listed = counts to it }
    }

    private fun indexable(vf: VirtualFile): Boolean =
        vf.isValid && !vf.isDirectory && vf.length <= SIZE_CAP && vf.path.split('/').none { it in SKIPPED_DIRECTORIES }

    private fun prefix(): String = StreamlordSettings.getInstance(project).attributePrefix

    private fun dropUnion() {
        synchronized(unionLock) {
            unionGeneration++
            definitionsUnion = null
        }
    }

    /** HTML's own extensions and the template extensions in the settings, which the inspections read as markup too. */
    private fun markupExtensions(): Set<String> = HTML_EXTENSIONS + StreamlordSettings.getInstance(project).templateExtensions

    /** Kotlin sources and markup, as the VS Code extension reads them; a Gradle script (`.kts`) declares no signals. */
    private fun languageOf(vf: VirtualFile): SourceLanguage? =
        when (vf.fileType) {
            KotlinFileType.INSTANCE -> if (vf.extension == "kt") SourceLanguage.KOTLIN else null
            HtmlFileType.INSTANCE -> SourceLanguage.HTML
            else -> if (vf.extension?.lowercase() in markupExtensions()) SourceLanguage.HTML else null
        }

    companion object {
        private const val FILE_CAP = 5000
        private const val SIZE_CAP = 1_000_000L
        private val SKIPPED_DIRECTORIES = setOf("build", "node_modules", ".gradle", "dist", "out", "target")
        private val HTML_EXTENSIONS = setOf("html", "htm", "xhtml")

        fun getInstance(project: Project): SignalIndex = project.service()
    }
}
