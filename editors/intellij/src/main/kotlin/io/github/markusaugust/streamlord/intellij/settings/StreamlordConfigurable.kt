package io.github.markusaugust.streamlord.intellij.settings

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.toNullableProperty
import io.github.markusaugust.streamlord.analysis.Requests
import io.github.markusaugust.streamlord.core.catalog.CatalogNames
import io.github.markusaugust.streamlord.intellij.inspector.InspectorService

/** Settings | Tools | Streamlord. */
class StreamlordConfigurable(
    private val project: Project,
) : BoundConfigurable("Streamlord") {
    /** The field of the requests file, to judge what is in it before it is stored. */
    private var requestsFile: JBTextField? = null

    override fun apply() {
        // The field marks a path it refuses as it is typed; here it is not stored either.
        requestsFile?.text?.let(Requests::requestsPathProblem)?.let { throw ConfigurationException(it) }
        super.apply()
        StreamlordSettings.getInstance(project).changed()
        // An open inspector shows the file and the base URL of the settings, so it reads them again.
        InspectorService.getInstance(project).panel?.pushRequests(null)
    }

    override fun disposeUIResources() {
        requestsFile = null
        super.disposeUIResources()
    }

    override fun createPanel(): DialogPanel {
        val state = StreamlordSettings.getInstance(project).state
        return panel {
            group("Datastar") {
                row("Attribute prefix:") {
                    comboBox(listOf(CatalogNames.PREFIX, CatalogNames.ALIASED_PREFIX))
                        .bindItem(state::attributePrefix.toNullableProperty())
                        .comment("data-star- when you load the aliased bundle.")
                }
                row {
                    checkBox("Inject HTML into Kotlin strings that open with a tag")
                        .bindSelected(state::injectHtml)
                        .comment(
                            "Strings with @Language(\"HTML\") are injected by the Kotlin plugin already; this covers the ones without it.",
                        )
                }
                row("Template file extensions:") {
                    textField()
                        .bindText(
                            { state.templateExtensions.joinToString(", ") },
                            { text ->
                                state.templateExtensions =
                                    text
                                        .split(',', ' ')
                                        .map { it.trim() }
                                        .filter { it.isNotEmpty() }
                                        .toMutableList()
                            },
                        ).align(AlignX.FILL)
                        .comment(
                            "Files with these extensions get the HTML side as plain text when no plugin gives them an HTML tree: " +
                                "diagnostics, completion, hover and colours for the data-* attributes.",
                        )
                }
                row {
                    checkBox("Leave data-* attribute names to the Datastar plugin when it is installed")
                        .bindSelected(state::yieldAttributeNamesToDatastarPlugin)
                        .comment(
                            "Avoids listing every attribute twice in HTML files. Modifiers, signals, actions, diagnostics and hover stay.",
                        )
                }
            }
            group("Stream Inspector") {
                row("Default base URL:") {
                    textField()
                        .bindText(state::inspectorDefaultUrl)
                        .align(AlignX.FILL)
                        .comment("The value of {{baseUrl}} unless .streamlord/env.json sets baseUrl.")
                }
                row("Saved requests file:") {
                    textField()
                        .bindText(state::requestsFile)
                        .align(AlignX.FILL)
                        // Said here, where the path is written, and not first when a request is saved.
                        .validationOnInput { field -> Requests.requestsPathProblem(field.text)?.let { error(it) } }
                        .applyToComponent { requestsFile = this }
                        .comment("Project-relative; ${Requests.REQUESTS_FILE} by default. Commit it to share requests with the team.")
                }
                row {
                    checkBox("Show \"Open in Stream Inspector\" in the gutter of Ktor and Spring routes")
                        .bindSelected(state::routeMarkers)
                }
            }
        }
    }
}
