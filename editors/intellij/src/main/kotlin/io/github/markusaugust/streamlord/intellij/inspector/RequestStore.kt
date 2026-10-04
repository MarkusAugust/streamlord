package io.github.markusaugust.streamlord.intellij.inspector

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.util.Computable
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import io.github.markusaugust.streamlord.analysis.Requests
import io.github.markusaugust.streamlord.analysis.RequestsFile
import io.github.markusaugust.streamlord.analysis.SavedRequest
import io.github.markusaugust.streamlord.intellij.settings.StreamlordSettings

/**
 * Where inspector requests live: saved ones in `.streamlord/inspector.json` in the project (the
 * same file the VS Code extension reads, so a team shares them), recent ones and the last-used
 * request in the workspace state, and the variables in `.streamlord/env.json`, which is meant for
 * local hosts and tokens and stays out of version control.
 */
@Service(Service.Level.PROJECT)
class RequestStore(
    private val project: Project,
) {
    private val settings: StreamlordSettings get() = StreamlordSettings.getInstance(project)

    fun baseDir(): VirtualFile? = project.guessProjectDir()

    /** Project-relative path of the requests file. */
    fun requestsPath(): String = settings.state.requestsFile.ifBlank { Requests.REQUESTS_FILE }

    fun requestsFile(): VirtualFile? = baseDir()?.findFileByRelativePath(requestsPath())

    fun read(): RequestsFile {
        val file = requestsFile() ?: return RequestsFile.EMPTY
        return ApplicationManager.getApplication().runReadAction(Computable { Requests.parseRequestsFile(VfsUtil.loadText(file)) })
    }

    fun saved(): List<SavedRequest> = read().requests

    fun save(request: SavedRequest) {
        write(Requests.upsert(read(), request))
    }

    fun delete(name: String) {
        write(Requests.remove(read(), name))
    }

    private fun write(file: RequestsFile) {
        val base = baseDir() ?: throw IllegalStateException("Open a project to save requests; they are stored in the project.")
        val text = Requests.serializeRequestsFile(file)
        val path = requestsPath()
        val run = {
            WriteCommandAction.runWriteCommandAction(project) {
                val dir =
                    VfsUtil.createDirectoryIfMissing(base, path.substringBeforeLast('/', ""))
                        ?: throw IllegalStateException("Cannot create the directory for $path")
                val name = path.substringAfterLast('/')
                val vf = dir.findChild(name) ?: dir.createChildData(this, name)
                VfsUtil.saveText(vf, text)
            }
        }
        if (ApplicationManager.getApplication().isDispatchThread) run() else ApplicationManager.getApplication().invokeAndWait(run)
    }

    fun recent(): List<SavedRequest> = InspectorState.getInstance(project).recent()

    fun addRecent(request: SavedRequest) = InspectorState.getInstance(project).addRecent(request)

    fun lastUsed(): SavedRequest? = InspectorState.getInstance(project).lastUsed()

    /** The variables that are set, and what is wrong with `.streamlord/env.json`. */
    data class Environment(
        val vars: List<Requests.Variable>,
        val errors: List<String>,
    )

    /**
     * The variables `.streamlord/env.json` sets, `baseUrl` from the settings when it does not, and
     * what is wrong with the file. An invalid file sets nothing: a request is not sent half filled.
     */
    fun environment(): Environment {
        val env = Requests.parseEnv(envText() ?: "{}")
        val usable = if (env.errors.isEmpty()) env else Requests.parseEnv("{}")
        return Environment(Requests.mergeVariables(settings.state.inspectorDefaultUrl, usable), env.errors)
    }

    private fun envFile(): VirtualFile? = baseDir()?.findFileByRelativePath(Requests.ENV_FILE)

    /** The env file as an open editor has it, so Connect uses what is on screen, saved or not. */
    private fun envText(): String? =
        envFile()?.let { env ->
            ApplicationManager.getApplication().runReadAction(
                Computable { FileDocumentManager.getInstance().getCachedDocument(env)?.text ?: VfsUtil.loadText(env) },
            )
        }

    /**
     * Add [keys] to `.streamlord/env.json`, creating it with `baseUrl` when it is missing, and
     * return it to be opened. The flag is false when the file is not a JSON object to add to.
     */
    fun defineVariables(keys: List<String>): Pair<VirtualFile, Boolean> {
        val base = baseDir() ?: throw IllegalStateException("Open a project to keep variables; they live in ${Requests.ENV_FILE}.")
        val text = envText()
        val baseUrl = environment().vars.firstOrNull { it.name == "baseUrl" }?.value ?: ""
        val next = Requests.withEnvVariables(text, keys, baseUrl)
        envFile()?.let { if (next == null || next == text) return it to (next != null) }
        val path = Requests.ENV_FILE
        var file: VirtualFile? = null
        val run = {
            WriteCommandAction.runWriteCommandAction(project) {
                val dir =
                    VfsUtil.createDirectoryIfMissing(base, path.substringBeforeLast('/'))
                        ?: throw IllegalStateException("Cannot create the directory for $path")
                val name = path.substringAfterLast('/')
                val vf = dir.findChild(name) ?: dir.createChildData(this, name)
                // An open editor gets an edit it can undo, not a write underneath its unsaved text.
                val document = FileDocumentManager.getInstance().getCachedDocument(vf)
                if (document != null) document.setText(next ?: "") else VfsUtil.saveText(vf, next ?: "")
                file = vf
            }
        }
        if (ApplicationManager.getApplication().isDispatchThread) run() else ApplicationManager.getApplication().invokeAndWait(run)
        return file!! to true
    }

    companion object {
        fun getInstance(project: Project): RequestStore = project.service()
    }
}
