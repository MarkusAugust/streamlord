package io.github.markusaugust.streamlord.intellij.inspector

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
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
 * request in the workspace state, variables in the settings merged with `.streamlord/env.json`,
 * which is meant to be git-ignored, for tokens and local hosts.
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

    /** Create the file with an empty list when it does not exist yet, and return it. */
    fun ensureFile(): VirtualFile? {
        requestsFile()?.let { return it }
        write(RequestsFile.EMPTY)
        return requestsFile()
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

    /** Settings first, `.streamlord/env.json` on top; `baseUrl` always has a value. */
    fun variables(): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        out["baseUrl"] = settings.state.inspectorDefaultUrl.trimEnd('/')
        out.putAll(settings.state.inspectorVariables)
        val env = baseDir()?.findFileByRelativePath(Requests.ENV_FILE)
        if (env !=
            null
        ) {
            out.putAll(ApplicationManager.getApplication().runReadAction(Computable { Requests.parseEnvFile(VfsUtil.loadText(env)) }))
        }
        return out
    }

    companion object {
        fun getInstance(project: Project): RequestStore = project.service()
    }
}
