package io.github.markusaugust.streamlord.intellij.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SimpleModificationTracker
import com.intellij.util.xmlb.XmlSerializerUtil
import io.github.markusaugust.streamlord.analysis.Requests

/**
 * The plugin's settings, per project and shared through `.idea/streamlord.xml`, so a team that
 * loads the aliased bundle or runs the server on another port agrees once.
 */
@Service(Service.Level.PROJECT)
@State(name = "StreamlordSettings", storages = [Storage("streamlord.xml")])
class StreamlordSettings : PersistentStateComponent<StreamlordSettings.State> {
    class State {
        /** The attribute prefix of the Datastar bundle you load: `data-`, or `data-star-` for the aliased bundle. */
        var attributePrefix: String = "data-"

        /** The value of `{{baseUrl}}` in inspector requests when `.streamlord/env.json` does not set baseUrl. */
        var inspectorDefaultUrl: String = "http://localhost:8080/"

        /** Project-relative file holding saved inspector requests. */
        var requestsFile: String = Requests.REQUESTS_FILE

        /** Show "Open in Stream Inspector" in the gutter of Ktor and Spring routes. */
        var routeMarkers: Boolean = true

        /** Inject HTML into Kotlin strings that look like HTML but carry no `@Language("HTML")`. */
        var injectHtml: Boolean = true

        /** Leave the completion of `data-*` attribute names in HTML files to the official Datastar plugin when it is installed. */
        var yieldAttributeNamesToDatastarPlugin: Boolean = true

        /**
         * File extensions of template languages that get the HTML side as text when no plugin gives them an HTML tree:
         * JTE and kte, FreeMarker, Velocity, Mustache, Handlebars, Pebble, Twig, Jinja and the rest.
         */
        var templateExtensions: MutableList<String> = DEFAULT_TEMPLATE_EXTENSIONS.toMutableList()
    }

    private var state = State()

    /** Bumped whenever the settings change, so cached analysis results depending on them are dropped. */
    val tracker = SimpleModificationTracker()

    override fun getState(): State = state

    override fun loadState(state: State) {
        XmlSerializerUtil.copyBean(state, this.state)
        tracker.incModificationCount()
    }

    /** Called by the configurable after the user applied changes. */
    fun changed() {
        tracker.incModificationCount()
    }

    val attributePrefix: String get() = state.attributePrefix.ifBlank { "data-" }

    /** The extensions, lowercased and without dots, as written in the settings. */
    val templateExtensions: Set<String>
        get() =
            state.templateExtensions
                .map { it.trim().removePrefix(".").lowercase() }
                .filter { it.isNotEmpty() }
                .toSet()

    companion object {
        val DEFAULT_TEMPLATE_EXTENSIONS: List<String> =
            listOf(
                "jte",
                "kte",
                "ftl",
                "ftlh",
                "vm",
                "mustache",
                "hbs",
                "handlebars",
                "peb",
                "pebble",
                "twig",
                "jinja",
                "jinja2",
                "j2",
                "cshtml",
                "razor",
                "php",
                "erb",
                "ejs",
                "liquid",
                "njk",
                "edge",
                "astro",
                "svelte",
                "vue",
                "tmpl",
                "gotmpl",
                "gohtml",
            )

        fun getInstance(project: Project): StreamlordSettings = project.service()
    }
}
