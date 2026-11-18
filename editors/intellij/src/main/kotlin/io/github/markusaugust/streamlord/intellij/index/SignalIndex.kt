package io.github.markusaugust.streamlord.intellij.index

import com.intellij.ide.highlighter.HtmlFileType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.impl.LoadTextUtil
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiFile
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.util.ForcefulReparseModificationTracker
import io.github.markusaugust.streamlord.analysis.ProjectFiles
import io.github.markusaugust.streamlord.analysis.ProjectIndex
import io.github.markusaugust.streamlord.analysis.ProjectSignals
import io.github.markusaugust.streamlord.analysis.Selectors
import io.github.markusaugust.streamlord.analysis.SignalDeclaration
import io.github.markusaugust.streamlord.analysis.SignalFacts
import io.github.markusaugust.streamlord.analysis.SignalReference
import io.github.markusaugust.streamlord.analysis.SourceLanguage
import io.github.markusaugust.streamlord.intellij.analysis.ProjectLibraries
import io.github.markusaugust.streamlord.intellij.analysis.StreamlordAnalysis
import io.github.markusaugust.streamlord.intellij.analysis.StreamlordLibrary
import io.github.markusaugust.streamlord.intellij.analysis.UiThreadWork
import io.github.markusaugust.streamlord.intellij.settings.StreamlordSettings
import org.jetbrains.kotlin.idea.KotlinFileType
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Project-wide index of signal names and of element ids and classes, for completion. Heuristic
 * and generous, like the VS Code extension's: every Kotlin and HTML file in the project is read
 * once and again when it changes, up to a cap, and the names are offered with this file's first.
 * It keeps where each signal is defined and read as well, so that go to declaration, find usages
 * and rename read no file again: a word search re-reads thousands of files in a large project.
 *
 * What a file gives the project is the analysis's to know, in its [ProjectIndex], as in the VS Code
 * extension; this service knows which files there are, when one has changed, and which module a file
 * is in. The DSL's short helpers, `set`, `toggle`, `signal` and their kin, share their names with other
 * APIs, and whether they are the DSL's is asked of the file's module each time: in a module without
 * streamlord-html they are somebody else's, and a file moved to another module or a library added
 * changes no text. A `data*` helper names the attribute it writes, so it counts in every module, a
 * helper of the project's own among them. A `set` through an implicit receiver in a module with the
 * DSL, `headers { set("x", 1) }`, counts too: the text cannot tell.
 */
@Service(Service.Level.PROJECT)
class SignalIndex(
    private val project: Project,
) {
    /** A file the index holds, as it was when read: unchanged while its stamp and its length are these. */
    private class Held(
        val file: VirtualFile?,
        val stamp: Long,
        val length: Int,
    )

    /** A place in a project file where a signal is defined or read, by the file's offsets. */
    class Place(
        val file: VirtualFile,
        val start: Int,
        val end: Int,
        val name: String,
    )

    /**
     * The files read, and what they give the project, read with the prefix beside it. The two are kept in step and
     * used under [lock]: highlighting asks from several threads, and a file is read or forgotten in both or in neither.
     */
    private val files = HashMap<String, Held>()
    private var held: Pair<String, ProjectIndex>? = null
    private val lock = Any()

    /** The file whose module [hasDsl] is being asked for through its tree, which knows an injected fragment's host. */
    private val asking = ThreadLocal<Pair<String, PsiFile>>()

    /** The counts of the roots and of indexing the modules were last asked at, since those decide which have the DSL. */
    @Volatile
    private var modulesAt: List<Long> = emptyList()

    /** The counts of the last full read, so a pass over many files reads the project once. */
    @Volatile
    private var refreshedAt: List<Long> = emptyList()

    /** How many times [forget] has run. */
    private val forgotten = AtomicInteger()

    /** The files to read, with the counts they were listed at: an edit changes a file, not which files there are. */
    @Volatile
    private var listed: Pair<List<Long>, List<VirtualFile>> = emptyList<Long>() to emptyList()

    /** The names declared in one file, from its current text. */
    fun forFile(file: PsiFile): Set<String> = ask(file) { key -> signalsOf(key) } ?: emptySet()

    fun selectorsForFile(file: PsiFile): Selectors = ask(file) { key -> selectorsOf(key) } ?: Selectors.EMPTY

    /** Where [file] defines signals, from its current text. */
    fun declarationsIn(file: PsiFile): List<SignalDeclaration> = ask(file) { key -> declarations(key) { file.text } } ?: emptyList()

    /** Where [file] reads signals, from its current text, calls to something other than Streamlord among them. */
    fun readsIn(file: PsiFile): List<SignalReference> =
        ask(file) { key -> reads(key) { StreamlordAnalysis.getInstance(project).source(file.text, languageOf(vfOf(file))!!) } }
            ?: emptyList()

    /** Every place in the project that defines the signal [name], and with [inside] every one that defines a signal under it. */
    fun declarationsOf(
        name: String,
        inside: Boolean = false,
    ): List<Place> {
        val under = "$name."
        return declarations().filter { it.name == name || (inside && it.name.startsWith(under)) }
    }

    /** Every place in the project that defines a signal, by its name: [declarationsOf] for all names at once. */
    fun declarationsByName(): Map<String, List<Place>> = declarations().groupBy { it.name }

    private fun declarations(): List<Place> {
        refresh()
        return places { key, vf -> declarations(key) { textOf(vf) }?.map { Triple(it.start, it.end, it.name) } }
    }

    /** Every place in the project that reads the signal [name] or reads into it, `$user.name` for `user`. */
    fun readsOf(name: String): List<Place> {
        refresh()
        val inside = "$name."
        val analysis = StreamlordAnalysis.getInstance(project)
        return places { key, vf ->
            reads(key) { textOf(vf)?.let { text -> languageOf(vf)?.let { analysis.source(text, it) } } }
                ?.filter { it.name == name || it.name.startsWith(inside) }
                ?.map { Triple(it.start, it.end, it.name) }
        }
    }

    /**
     * What the project knows about the signals of [file], for the analysis's `projectSignalIssues`, as the VS Code
     * extension asks it. A file's facts are read when the check first asks, which completion never does, kept by the
     * analysis, and read again only when the file changes, so a pass costs one read of the file that changed and a walk
     * over what is kept ("Cache Results of Heavy Computations",
     * https://plugins.jetbrains.com/docs/intellij/psi-performance.html). The daemon runs the inspection again on a change
     * in any file; [refresh] is what finds it.
     */
    fun projectSignals(file: PsiFile): ProjectSignals {
        val key = keyOf(file)
        return object : ProjectSignals {
            override fun defined() = definitionsSeenFrom(file)

            override fun own() =
                key?.let {
                    ask(file) {
                        readFacts(it, file.text)
                        signalsFor(it).own()
                    }
                }

            override fun others(): Iterable<SignalFacts> {
                if (key == null) return emptyList()
                refresh()
                for ((waiting, vf) in locked { waiting().mapNotNull { path -> files[path]?.file?.let { path to it } } }) {
                    ProgressManager.checkCanceled()
                    val text = textOf(vf) ?: continue
                    locked { readFacts(waiting, text) }
                }
                return locked { signalsFor(key).others() }
            }
        }
    }

    /** What [of] finds in each file held, as places in it. */
    private fun places(of: ProjectIndex.(String, VirtualFile) -> List<Triple<Int, Int, String>>?): List<Place> {
        val out = ArrayList<Place>()
        for ((key, h) in locked { files.toList() }) {
            ProgressManager.checkCanceled()
            val file = h.file?.takeIf { it.isValid } ?: continue
            for ((start, end, name) in locked { of(key, file) } ?: continue) out += Place(file, start, end, name)
        }
        return out
    }

    /** Every signal name declared anywhere in the project. */
    fun all(): Set<String> {
        refresh()
        return locked { signals() }
    }

    /**
     * Every signal name defined anywhere in the project, [file] read from its current text: what a
     * `$name` is checked against. No reads, as [all] has. Cached until a definition or a library changes.
     */
    fun definitionsSeenFrom(file: PsiFile): Set<String> {
        refresh()
        val at =
            listOf(
                ProjectRootManager.getInstance(project).modificationCount,
                DumbService.getInstance(project).modificationTracker.modificationCount,
            )
        if (at != modulesAt) {
            locked { modulesChanged() }
            modulesAt = at
        }
        return ask(file) { definitions() } ?: locked { definitions() }
    }

    fun allSelectors(): Selectors {
        refresh()
        return locked { selectors() }
    }

    /** Forget everything and read the project again; returns the number of signal names found. */
    fun rebuild(): Int {
        forget()
        return all().size
    }

    /** Forget everything, so the next question reads the project again. Apart from the reading, which a write may stop and start over. */
    fun forget() {
        forgotten.incrementAndGet()
        locked {
            files.clear()
            clear()
        }
        refreshedAt = emptyList()
        listed = emptyList<Long>() to emptyList()
    }

    /** The text of a file that is not open, or null when it cannot be read. A cancellation is not such a failure. */
    private fun loadText(vf: VirtualFile): String? =
        try {
            LoadTextUtil.loadText(vf).toString()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }

    /** The text of [vf] as it is now: an open document's, which may hold edits the disk does not, or else the disk's. */
    private fun textOf(vf: VirtualFile): String? = FileDocumentManager.getInstance().getCachedDocument(vf)?.text ?: loadText(vf)

    private fun vfOf(file: PsiFile): VirtualFile = file.virtualFile ?: file.viewProvider.virtualFile

    /** What the index calls [file], or null for a file it does not read. */
    private fun keyOf(file: PsiFile): String? = languageOf(vfOf(file))?.let { file.virtualFile?.path ?: file.name }

    /** [action] on the index with [file] read from its current text, its module asked of its tree; null for a file not read. */
    private fun <T> ask(
        file: PsiFile,
        action: ProjectIndex.(key: String) -> T,
    ): T? {
        val key = keyOf(file) ?: return null
        val text = file.text
        val stamp = file.modificationStamp
        asking.set(key to file)
        try {
            // Read and asked in one go: a file another thread reads or forgets meanwhile is not answered for by halves.
            return locked {
                val cached = files[key]
                if (cached == null || cached.stamp != stamp || cached.length != text.length) {
                    read(key, file.virtualFile, stamp, text, languageOf(vfOf(file))!!)
                }
                action(key)
            }
        } finally {
            asking.remove()
        }
    }

    /** [action] on the index for the prefix in the settings, under [lock]; another prefix reads every file anew. */
    private fun <T> locked(action: ProjectIndex.() -> T): T =
        synchronized(lock) {
            val prefix = StreamlordSettings.getInstance(project).attributePrefix
            val index =
                held?.takeIf { it.first == prefix }?.second ?: ProjectIndex(prefix, ::hasDsl).also {
                    files.clear()
                    refreshedAt = emptyList()
                    held = prefix to it
                }
            index.action()
        }

    /** Whether the module of the file [key] has streamlord-html; null while the IDE indexes, when the calls count. */
    private fun hasDsl(key: String): Boolean? {
        asking.get()?.let { (asked, file) -> if (asked == key) return ProjectLibraries.known(file, StreamlordLibrary.HTML) }
        return files[key]?.file?.let { ProjectLibraries.known(project, it, StreamlordLibrary.HTML) }
    }

    /** Reads [text] as the file [key]. What it says of the server's reads waits until the check asks. Under [lock]. */
    private fun ProjectIndex.read(
        key: String,
        file: VirtualFile?,
        stamp: Long,
        text: String,
        language: SourceLanguage,
    ) {
        put(key, text, language, facts = false)
        files[key] = Held(file, stamp, text.length)
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
        UiThreadWork.note()
        val forgottenAt = forgotten.get()
        val seen = HashSet<String>()
        for (vf in projectFiles(counts.drop(1))) {
            // Reading a file is no PSI work, so nothing else asks: a write that waits must be able to stop this read.
            ProgressManager.checkCanceled()
            if (!vf.isValid) continue
            val language = languageOf(vf) ?: continue
            seen += vf.path
            val document = FileDocumentManager.getInstance().getCachedDocument(vf)
            val stamp = document?.modificationStamp ?: vf.modificationStamp
            if (locked { files[vf.path]?.stamp } == stamp) continue
            val text = document?.text ?: loadText(vf) ?: continue
            locked { read(vf.path, vf, stamp, text, language) }
        }
        // A file deleted, renamed or moved out of the project defines nothing any more.
        locked {
            for (key in files.keys - seen) {
                files.remove(key)
                remove(key)
            }
        }
        // A read that [forget] emptied half way has not read everything, so it leaves the next one to.
        if (forgotten.get() == forgottenAt) refreshedAt = counts
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
            markup.asSequence().filter(::indexable).take(ProjectFiles.FILE_CAP) +
                FileTypeIndex
                    .getFiles(KotlinFileType.INSTANCE, scope)
                    .asSequence()
                    .filter(::indexable)
                    .take(ProjectFiles.FILE_CAP)
        return files.toList().also { listed = counts to it }
    }

    /**
     * Whether [vf] is read: no folder between it and the outermost content root that holds it is one the analysis
     * leaves unread. The outermost, since Gradle makes a generated source folder under `build` a content root of its own.
     */
    private fun indexable(vf: VirtualFile): Boolean {
        if (!vf.isValid || vf.isDirectory || vf.length > ProjectFiles.SIZE_CAP) return false
        val index = ProjectFileIndex.getInstance(project)
        var root = index.getContentRootForFile(vf)
        while (true) root = root?.parent?.let(index::getContentRootForFile) ?: break
        val path = root?.let { VfsUtilCore.getRelativePath(vf, it, '/') } ?: vf.name
        return !ProjectFiles.inSkippedFolder(path)
    }

    /** HTML's own extensions and the template extensions in the settings, which the inspections read as markup too. */
    private fun markupExtensions(): Set<String> = ProjectFiles.HTML_EXTENSIONS + StreamlordSettings.getInstance(project).templateExtensions

    /** Kotlin sources and markup, as the VS Code extension reads them; a Gradle script (`.kts`) declares no signals. */
    private fun languageOf(vf: VirtualFile): SourceLanguage? =
        when (vf.fileType) {
            KotlinFileType.INSTANCE -> if (vf.extension == "kt") SourceLanguage.KOTLIN else null
            HtmlFileType.INSTANCE -> SourceLanguage.HTML
            else -> if (vf.extension?.lowercase() in markupExtensions()) SourceLanguage.HTML else null
        }

    companion object {
        fun getInstance(project: Project): SignalIndex = project.service()
    }
}
