package io.github.markusaugust.streamlord.intellij.inspector

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.concurrency.annotations.RequiresEdt
import com.intellij.util.concurrency.annotations.RequiresReadLock
import io.github.markusaugust.streamlord.analysis.Requests
import io.github.markusaugust.streamlord.analysis.RequestsEdit
import io.github.markusaugust.streamlord.analysis.RequestsFile
import io.github.markusaugust.streamlord.analysis.SavedRequest
import io.github.markusaugust.streamlord.intellij.settings.StreamlordSettings

/**
 * Where inspector requests live: saved ones in `.streamlord/inspector.json` in the project (the
 * same file the VS Code extension reads, so a team shares them), recent ones and the last-used
 * request in the workspace state, and the variables in `.streamlord/env.json`, which is meant for
 * local hosts and tokens and stays out of version control.
 *
 * It takes no lock of its own: a caller reads under a read action, off the UI thread, and writes
 * on the UI thread.
 */
@Service(Service.Level.PROJECT)
class RequestStore(
    private val project: Project,
) {
    private val settings: StreamlordSettings get() = StreamlordSettings.getInstance(project)

    fun baseDir(): VirtualFile? = project.guessProjectDir()

    /** Project-relative path of the requests file; a path that leaves the project falls back to the default, and a save says so. */
    fun requestsPath(): String {
        val configured = settings.state.requestsFile
        return if (configured.isBlank() || Requests.requestsPathProblem(configured) != null) Requests.REQUESTS_FILE else configured
    }

    /** What is wrong with the path the settings name, or null; the default file is read meanwhile. */
    fun requestsPathProblem(): String? = Requests.requestsPathProblem(settings.state.requestsFile)

    fun requestsFile(): VirtualFile? = baseDir()?.findFileByRelativePath(requestsPath())

    /** Where the requests file is, found or not, written plainly, so a change of it is known by its path alone. */
    fun requestsFilePath(): String? =
        baseDir()?.path?.let { base ->
            (listOf(base) + requestsPath().split('/').filter { it.isNotEmpty() && it != "." }).joinToString("/")
        }

    /**
     * The requests file as an open editor has it, saved or not; a file that is missing is empty, one
     * that is not read says why in [RequestsFile.error], and an entry that is not read is named in
     * [RequestsFile.skipped].
     */
    @RequiresReadLock
    fun read(): RequestsFile {
        val file = requestsFile() ?: return RequestsFile.EMPTY
        return Requests.parseRequestsFile(textOf(file), requestsPath())
    }

    @RequiresReadLock
    fun saved(): List<SavedRequest> = read().requests

    /**
     * A file of the project as it was read, and what to write in its place. Planned under a read
     * action off the UI thread, and written by [apply] on it.
     */
    class Change internal constructor(
        internal val path: String,
        internal val stamp: Long?,
        internal val was: String?,
        /** Null when the file is not one this change can be made in. */
        internal val next: String?,
        /** Whether an open editor takes the change as an edit of its own. */
        internal val throughDocument: Boolean,
    )

    /** Save [request] in the requests file, in place of the entry of its name, and leave the rest of the file as it is. */
    @RequiresReadLock
    fun saving(request: SavedRequest): Change = requests { text, path -> Requests.saveRequest(text, request, path) }

    /** Take the request called [name] out of the requests file, and leave the rest of the file as it is. */
    @RequiresReadLock
    fun deleting(name: String): Change = requests { text, path -> Requests.removeRequest(text, name, path) }

    /** The requests file with [edit] made in its text, or the reason it cannot be: a file that is not read is not written over. */
    private fun requests(edit: (text: String?, path: String) -> RequestsEdit): Change {
        requestsPathProblem()?.let { throw IllegalStateException("$it Nothing was saved.") }
        val file = requestsFile()
        val text = file?.let(::textOf)
        val edited = edit(text, requestsPath())
        val next =
            edited.text ?: throw IllegalStateException("${edited.error} Fix the file before saving, so the requests in it are not lost.")
        return Change(requestsPath(), file?.let(::stampOf), text ?: "", next, throughDocument = true)
    }

    /**
     * Write what [change] planned, and return the file with whether it holds the change: false when the
     * file is not one the change can be made in. A file changed since it was read is left as it is, and
     * a change that changes nothing writes nothing, so it makes no file where there was none.
     */
    @RequiresEdt
    fun apply(change: Change): Pair<VirtualFile?, Boolean> {
        val base = baseDir() ?: throw IllegalStateException("Open a project to keep ${change.path}; it is stored in the project.")
        val existing = base.findFileByRelativePath(change.path)
        val documents = FileDocumentManager.getInstance()
        val stamp = existing?.let { if (change.throughDocument) stampOf(it) else it.modificationStamp }
        if (stamp != change.stamp) throw IllegalStateException("${change.path} changed in the meantime. Try again.")
        val next = change.next
        if (next == null || next == change.was) return existing to (next != null)
        var file: VirtualFile? = null
        WriteCommandAction.runWriteCommandAction(project) {
            val dir =
                VfsUtil.createDirectoryIfMissing(base, change.path.substringBeforeLast('/', ""))
                    ?: throw IllegalStateException("Cannot create the directory for ${change.path}")
            val name = change.path.substringAfterLast('/')
            val vf = dir.findChild(name) ?: dir.createChildData(this, name)
            // An open editor gets an edit it can undo, not a write underneath its unsaved text.
            val document = if (change.throughDocument) documents.getCachedDocument(vf) else null
            if (document != null) document.setText(next) else VfsUtil.saveText(vf, next)
            file = vf
        }
        return file!! to true
    }

    /** The stamp of [file] as an open editor has it. */
    private fun stampOf(file: VirtualFile): Long =
        FileDocumentManager.getInstance().getCachedDocument(file)?.modificationStamp ?: file.modificationStamp

    fun recent(): List<SavedRequest> = InspectorState.getInstance(project).recent()

    fun addRecent(request: SavedRequest) = InspectorState.getInstance(project).addRecent(request)

    fun lastUsed(): SavedRequest? = InspectorState.getInstance(project).lastUsed()

    /** The variables that are set, and what is wrong with `.streamlord/env.json`. */
    data class Environment(
        val vars: List<Requests.Variable>,
        val errors: List<String>,
    )

    /**
     * The variables `.streamlord/env.json` sets, `baseUrl` from a server the project started or from
     * the settings when it does not, and what is wrong with the file. An invalid file sets nothing:
     * a request is not sent half filled.
     */
    @RequiresReadLock
    fun environment(): Environment {
        val env = Requests.parseEnv(envText() ?: "{}")
        val usable = if (env.errors.isEmpty()) env else Requests.parseEnv("{}")
        val running = RunningServers.getInstance(project).current()
        return Environment(Requests.mergeVariables(settings.state.inspectorDefaultUrl, usable, running), env.errors)
    }

    private fun envFile(): VirtualFile? = baseDir()?.findFileByRelativePath(Requests.ENV_FILE)

    private fun envText(): String? = envFile()?.let(::textOf)

    /** A file as an open editor has it, so the panel uses what is on screen, saved or not. */
    private fun textOf(file: VirtualFile): String =
        FileDocumentManager.getInstance().getCachedDocument(file)?.text ?: VfsUtil.loadText(file)

    /**
     * Add [keys] to `.streamlord/env.json`, creating it with `baseUrl` when it is missing. The file is
     * left alone when it is not a JSON object to add to.
     */
    @RequiresReadLock
    fun definingVariables(keys: List<String>): Change {
        val text = envText()
        val baseUrl = environment().vars.firstOrNull { it.name == "baseUrl" }?.value ?: ""
        return env(text, Requests.withEnvVariables(text, keys, baseUrl))
    }

    /** Set `baseUrl` in `.streamlord/env.json` to [url], leaving the rest as written. */
    @RequiresReadLock
    fun usingBaseUrl(url: String): Change {
        val text = envText()
        return env(text, Requests.withBaseUrl(text, url))
    }

    private fun env(
        text: String?,
        next: String?,
    ): Change = Change(Requests.ENV_FILE, envFile()?.let(::stampOf), text, next, throughDocument = true)

    companion object {
        fun getInstance(project: Project): RequestStore = project.service()
    }
}
