package io.github.markusaugust.streamlord.intellij.settings

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.toNullableProperty
import io.github.markusaugust.streamlord.analysis.Requests

/** Settings | Tools | Streamlord. */
class StreamlordConfigurable(
    private val project: Project,
) : BoundConfigurable("Streamlord") {
    override fun apply() {
        super.apply()
        StreamlordSettings.getInstance(project).changed()
    }

    override fun createPanel(): DialogPanel {
        val state = StreamlordSettings.getInstance(project).state
        return panel {
            group("Datastar") {
                row("Attribute prefix:") {
                    comboBox(listOf("data-", "data-star-"))
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
