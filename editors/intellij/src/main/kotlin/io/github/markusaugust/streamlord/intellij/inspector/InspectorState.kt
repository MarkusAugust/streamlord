package io.github.markusaugust.streamlord.intellij.inspector

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.XmlSerializerUtil
import io.github.markusaugust.streamlord.analysis.Requests
import io.github.markusaugust.streamlord.analysis.SavedRequest

/** What the inspector remembers for you, per project, in the workspace file: recent requests and the last one used. */
@Service(Service.Level.PROJECT)
@State(name = "StreamlordInspector", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class InspectorState : PersistentStateComponent<InspectorState.State> {
    class Entry {
        var name: String = ""
        var url: String = ""
        var method: String = "GET"
        var signals: String = ""
        var headers: String = ""
    }

    class State {
        var recent: MutableList<Entry> = ArrayList()
        var last: Entry? = null
    }

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        XmlSerializerUtil.copyBean(state, this.state)
    }

    fun recent(): List<SavedRequest> = state.recent.map { it.toRequest() }

    fun lastUsed(): SavedRequest? = state.last?.toRequest()

    fun addRecent(request: SavedRequest) {
        state.recent = Requests.pushRecent(recent(), request).map { it.toEntry() }.toMutableList()
        state.last = request.toEntry()
    }

    private fun Entry.toRequest() = SavedRequest(name, url, method, signals, headers)

    private fun SavedRequest.toEntry() =
        Entry().also {
            it.name = name
            it.url = url
            it.method = method
            it.signals = signals
            it.headers = headers
        }

    companion object {
        fun getInstance(project: Project): InspectorState = project.service()
    }
}
