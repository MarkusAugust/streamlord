package io.github.markusaugust.streamlord.intellij.inspector

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.content.ContentFactory
import io.github.markusaugust.streamlord.analysis.Requests
import io.github.markusaugust.streamlord.analysis.Route
import io.github.markusaugust.streamlord.analysis.SavedRequest

/** The "Streamlord" tool window holding the Stream Inspector. */
class InspectorToolWindowFactory :
    ToolWindowFactory,
    DumbAware {
    override fun createToolWindowContent(
        project: Project,
        toolWindow: ToolWindow,
    ) {
        val panel = InspectorPanel(project, toolWindow.disposable)
        InspectorService.getInstance(project).panel = panel
        val content = ContentFactory.getInstance().createContent(panel, "Stream Inspector", false)
        toolWindow.contentManager.addContent(content)
    }
}

/** Finds the inspector panel once the tool window has been created, and opens it prefilled. */
@Service(Service.Level.PROJECT)
class InspectorService(
    private val project: Project,
) {
    var panel: InspectorPanel? = null

    /** Show the inspector, prefilled when a request is given. */
    fun open(prefill: SavedRequest? = null) {
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID) ?: return
        toolWindow.show {
            val p = panel
            if (prefill != null) p?.prefill(prefill)
        }
    }

    /**
     * Open with a route from the gutter. Every parameter the handler needs is a `{{name}}`, and its
     * value comes from `params` in the env file; one that is not there yet is reported with a link
     * that adds it, so nothing is asked for in a dialog and nothing is forgotten between runs.
     */
    fun openWithRoute(route: Route) {
        open(SavedRequest("", Requests.routeUrl(route), route.method, headers = Requests.routeHeaders(route)))
    }

    companion object {
        const val TOOL_WINDOW_ID = "Streamlord"

        fun getInstance(project: Project): InspectorService = project.service()
    }
}

/** Tools | Streamlord | Open Stream Inspector. */
class OpenInspectorAction : AnAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        InspectorService.getInstance(project).open()
    }
}
