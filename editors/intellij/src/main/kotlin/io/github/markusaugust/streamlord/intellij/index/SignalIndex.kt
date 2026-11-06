package io.github.markusaugust.streamlord.intellij.index

import com.intellij.ide.highlighter.HtmlFileType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.impl.LoadTextUtil
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
import io.github.markusaugust.streamlord.intellij.analysis.ProjectLibraries
import io.github.markusaugust.streamlord.intellij.analysis.StreamlordAnalysis
import io.github.markusaugust.streamlord.intellij.analysis.StreamlordLibrary
import io.github.markusaugust.streamlord.intellij.settings.StreamlordSettings
import org.jetbrains.kotlin.idea.KotlinFileType
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

/**
 * Project-wide index of signal names and of element ids and classes, for completion. Heuristic
 * and generous, like the VS Code extension's: every Kotlin and HTML file in the project is read
 * once and again when it changes, up to a cap, and the names are offered with this file's first.
 * It keeps where each signal is defined and read as well, so that go to declaration, find usages
 * and rename read no file again: a word search re-reads thousands of files in a large project.
 *
 * What is kept comes from a file's text alone. The DSL's short helpers, `set`, `setExpr`,
 * `toggle`, `signal`, `increment`, `decrement` and `not`, share their names with other APIs, so a
 * Kotlin file that calls one is read a second time without them, and which of the two counts is
 * asked of the file's module each time: in a module without streamlord-html they are somebody
 * else's, and a file moved to another module or a library added changes no text. A `data*` helper names the attribute it writes, so it counts in
 * every module, a helper of the project's own among them. A `set` through an implicit receiver in
 * a module with the DSL, `headers { set("x", 1) }`, counts too: the text cannot tell.
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
        /** What the file names and defines without its short DSL helpers, or null when it calls none. */
        val withoutDsl: Names?,
        val selectors: Selectors,
    ) {
        /**
         * Where the file defines signals and where it reads them, each worked out when first asked for, which completion
         * never does. Where it defines them is cheap, and highlighting asks for it; the reads only a search asks for.
         */
        @Volatile
        var declared: Declared? = null

        @Volatile
        var reads: List<SignalReference>? = null
    }

    private class Names(
        val signals: Set<String>,
        val definitions: Set<String>,
    )

    private class Declared(
        val declarations: List<SignalDeclaration>,
        /** The declarations without the file's short DSL helpers, when it calls any. */
        val withoutDsl: List<SignalDeclaration>?,
    )

    /** A place in a project file where a signal is defined or read, by the file's offsets. */
    class Place(
        val file: VirtualFile,
        val start: Int,
        val end: Int,
        val name: String,
    )

    private val entries = ConcurrentHashMap<String, Entry>()

    /**
     * The union of every entry's definitions, with the counts of the roots and of indexing it was made at,
     * since those decide which modules have the DSL. Dropped whenever an entry's definitions change or an entry goes.
     */
    @Volatile
    private var definitionsUnion: Pair<List<Long>, Set<String>>? = null

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
    fun forFile(file: PsiFile): Set<String> = entryFor(file)?.let { signalsOf(it, file) } ?: emptySet()

    fun selectorsForFile(file: PsiFile): Selectors = entryFor(file)?.selectors ?: Selectors.EMPTY

    /** Where [file] defines signals, from its current text. */
    fun declarationsIn(file: PsiFile): List<SignalDeclaration> {
        val e = entryFor(file) ?: return emptyList()
        val declared = declaredIn(e, file.text) ?: return emptyList()
        return if (withoutDsl(e, file)) declared.withoutDsl ?: declared.declarations else declared.declarations
    }

    /** Where [file] reads signals, from its current text, calls to something other than Streamlord among them. */
    fun readsIn(file: PsiFile): List<SignalReference> = entryFor(file)?.let { readsIn(it, file.text) } ?: emptyList()

    /** Every place in the project that defines the signal [name], and with [inside] every one that defines a signal under it. */
    fun declarationsOf(
        name: String,
        inside: Boolean = false,
    ): List<Place> {
        refresh()
        val under = "$name."
        return places { e ->
            declaredIn(e, null)
                ?.let { if (withoutDsl(e)) it.withoutDsl ?: it.declarations else it.declarations }
                ?.filter { it.name == name || (inside && it.name.startsWith(under)) }
                ?.map { Triple(it.start, it.end, it.name) }
        }
    }

    /** Every place in the project that reads the signal [name] or reads into it, `$user.name` for `user`. */
    fun readsOf(name: String): List<Place> {
        refresh()
        val inside = "$name."
        return places { e ->
            readsIn(e, null)?.filter { it.name == name || it.name.startsWith(inside) }?.map { Triple(it.start, it.end, it.name) }
        }
    }

    /**
     * The text of [e]'s file: [text], or else the file as it is now, with whether it is the text [e] was
     * made from, when what is worked out from it may be kept. A caller checks each place against the
     * file's own references.
     */
    private fun textOf(
        e: Entry,
        text: String?,
    ): Pair<String, Boolean>? {
        val vf = e.file ?: return null
        val document = FileDocumentManager.getInstance().getCachedDocument(vf)
        val stamp = document?.modificationStamp ?: vf.modificationStamp
        val current = text ?: document?.text ?: runCatching { LoadTextUtil.loadText(vf).toString() }.getOrNull() ?: return null
        return current to (current.length == e.length && (text != null || stamp == e.stamp))
    }

    private fun declaredIn(
        e: Entry,
        text: String?,
    ): Declared? {
        e.declared?.let { return it }
        val language = e.file?.let(::languageOf) ?: return null
        val (current, keep) = textOf(e, text) ?: return null
        val helpers = shortHelpers()
        // The current text may call a short helper the text the entry was made from did not.
        val withoutDsl =
            if (language == SourceLanguage.KOTLIN &&
                callsAny(current, helpers)
            ) {
                signalDeclarations(current, language, helpers)
            } else {
                null
            }
        val declared = Declared(signalDeclarations(current, language), withoutDsl)
        if (keep) e.declared = declared
        return declared
    }

    private fun readsIn(
        e: Entry,
        text: String?,
    ): List<SignalReference>? {
        e.reads?.let { return it }
        val language = e.file?.let(::languageOf) ?: return null
        val (current, keep) = textOf(e, text) ?: return null
        val analysis = StreamlordAnalysis.getInstance(project)
        val reads =
            when (language) {
                SourceLanguage.KOTLIN -> analysis.analyzer.signalReferencesInKotlin(current, analysis.options())
                SourceLanguage.HTML -> analysis.analyzer.signalReferencesInHtml(current, analysis.options())
            }.map { SignalReference(it.start, it.end, it.name) }
        if (keep) e.reads = reads
        return reads
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
        for (e in entries.values) out += signalsOf(e)
        return out
    }

    private fun signalsOf(
        e: Entry,
        psi: PsiFile? = null,
    ): Set<String> = if (withoutDsl(e, psi)) e.withoutDsl!!.signals else e.signals

    private fun definitionsOf(
        e: Entry,
        psi: PsiFile? = null,
    ): Set<String> = if (withoutDsl(e, psi)) e.withoutDsl!!.definitions else e.definitions

    /**
     * Whether [e]'s file is read without its short DSL helpers: it calls some, and its module has no
     * streamlord-html. Asked each time, of [psi] when there is one. While the IDE indexes the
     * module cannot tell, and the calls count.
     */
    private fun withoutDsl(
        e: Entry,
        psi: PsiFile? = null,
    ): Boolean {
        if (e.withoutDsl == null) return false
        val known =
            if (psi != null) {
                ProjectLibraries.known(psi, StreamlordLibrary.HTML)
            } else {
                e.file?.let { ProjectLibraries.known(project, it, StreamlordLibrary.HTML) }
            }
        return known == false
    }

    /**
     * Every signal name defined anywhere in the project, [file] read from its current text: what a
     * `$name` is checked against. No reads, as [all] has. Cached until a definition or a library changes.
     */
    fun definitionsSeenFrom(file: PsiFile): Set<String> {
        refresh()
        val own = entryFor(file)?.let { definitionsOf(it, file) } ?: emptySet()
        val at =
            listOf(
                ProjectRootManager.getInstance(project).modificationCount,
                DumbService.getInstance(project).modificationTracker.modificationCount,
            )
        definitionsUnion?.let { (stamp, union) -> if (stamp == at && union.containsAll(own)) return union }
        val generation = synchronized(unionLock) { unionGeneration }
        val out = HashSet<String>()
        for (e in entries.values) out += definitionsOf(e)
        out += own
        synchronized(unionLock) { if (unionGeneration == generation) definitionsUnion = at to out }
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
        val helpers = shortHelpers()
        val withoutDsl =
            if (language == SourceLanguage.KOTLIN && callsAny(text, helpers)) {
                Names(collectSignals(text, language, helpers), collectSignalDefinitions(text, language, helpers))
            } else {
                null
            }
        val entry =
            Entry(
                file,
                stamp,
                text.length,
                prefix(),
                collectSignals(text, language),
                collectSignalDefinitions(text, language),
                withoutDsl,
                collectSelectors(text),
            )
        val previous = entries.put(key, entry)
        if (previous?.definitions != entry.definitions || previous.withoutDsl?.definitions != entry.withoutDsl?.definitions) dropUnion()
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
            val text = document?.text ?: runCatching { LoadTextUtil.loadText(vf).toString() }.getOrNull() ?: continue
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

    /** The DSL's helpers that name a signal, whose names other APIs use too. */
    private fun shortHelpers(): Set<String> =
        StreamlordAnalysis
            .getInstance(project)
            .catalog.signalNameHelpers.keys

    /** Whether [text] calls one of [names] on no receiver: a file without such a call reads the same in every module. */
    private fun callsAny(
        text: String,
        names: Set<String>,
    ): Boolean {
        val m = CALL.matcher(text)
        while (m.find()) {
            if (m.group(1) !in names) continue
            var i = m.start() - 1
            while (i >= 0 && text[i].isWhitespace()) i--
            if (i < 0 || text[i] != '.') return true
        }
        return false
    }

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
        private val CALL = Pattern.compile("""\b([A-Za-z_]\w*)\s*\(""")
        private const val FILE_CAP = 5000
        private const val SIZE_CAP = 1_000_000L
        private val SKIPPED_DIRECTORIES = setOf("build", "node_modules", ".gradle", "dist", "out", "target")
        private val HTML_EXTENSIONS = setOf("html", "htm", "xhtml")

        fun getInstance(project: Project): SignalIndex = project.service()
    }
}
