package io.github.markusaugust.streamlord.intellij.highlighting

import com.intellij.icons.AllIcons
import com.intellij.lang.html.HTMLLanguage
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.options.colors.AttributesDescriptor
import com.intellij.openapi.options.colors.ColorDescriptor
import com.intellij.openapi.options.colors.ColorSettingsPage
import javax.swing.Icon

/** Settings | Editor | Color Scheme | Streamlord: the Datastar tokens, and nothing that belongs to a theme. */
class DatastarColorSettingsPage : ColorSettingsPage {
    override fun getDisplayName(): String = "Streamlord"

    override fun getIcon(): Icon = AllIcons.Nodes.Plugin

    override fun getHighlighter(): SyntaxHighlighter = SyntaxHighlighterFactory.getSyntaxHighlighter(HTMLLanguage.INSTANCE, null, null)

    override fun getAttributeDescriptors(): Array<AttributesDescriptor> = DESCRIPTORS

    override fun getColorDescriptors(): Array<ColorDescriptor> = ColorDescriptor.EMPTY_ARRAY

    override fun getAdditionalHighlightingTagToDescriptorMap(): Map<String, TextAttributesKey> = TAGS

    override fun getDemoText(): String =
        """
        <div <prefix>data-</prefix><plugin>signals</plugin>="{count: <number>0</number>, open: <keyword>false</keyword>}"
             <prefix>data-</prefix><plugin>on</plugin><sigil>:</sigil><key>click</key><sigil>__</sigil><modifier>debounce</modifier><sigil>.</sigil><arg>500ms</arg>="<signal>${'$'}count</signal>++; <backend>@post</backend>(<string>'/count'</string>, {<option>headers</option>: {<string>'X-Csrf'</string>: <signal>${'$'}csrf</signal>}})"
             <prefix>data-</prefix><plugin>on-intersect</plugin><sigil>__</sigil><modifier>once</modifier>="<action>@peek</action>(() => <scope>el</scope>.dataset.<signal>${'$'}user</signal><path>.name</path>)"
             <prefix>data-</prefix><plugin>text</plugin>="<scope>evt</scope> ? <string>'yes'</string> : <regex>/^a/</regex>.test(<signal>${'$'}form</signal><path>.q</path>)">
        </div>
        """.trimIndent()

    private companion object {
        val DESCRIPTORS =
            arrayOf(
                AttributesDescriptor("Expression//Signal", DatastarColors.SIGNAL),
                AttributesDescriptor("Expression//Signal path", DatastarColors.SIGNAL_PATH),
                AttributesDescriptor("Expression//Backend action (@get, @post, ...)", DatastarColors.ACTION_BACKEND),
                AttributesDescriptor("Expression//Other action", DatastarColors.ACTION),
                AttributesDescriptor("Expression//Scope variable (el, evt, patch)", DatastarColors.SCOPE_VARIABLE),
                AttributesDescriptor("Expression//Option key", DatastarColors.OPTION_KEY),
                AttributesDescriptor("Expression//Duration", DatastarColors.DURATION),
                AttributesDescriptor("Expression//String", DatastarColors.STRING),
                AttributesDescriptor("Expression//Regular expression", DatastarColors.REGEX),
                AttributesDescriptor("Expression//JavaScript keyword", DatastarColors.KEYWORD),
                AttributesDescriptor("Expression//Number", DatastarColors.NUMBER),
                AttributesDescriptor("Expression//Kotlin dollar escape", DatastarColors.KOTLIN_DOLLAR_ESCAPE),
                AttributesDescriptor("Attribute//Prefix (data-)", DatastarColors.ATTRIBUTE_PREFIX),
                AttributesDescriptor("Attribute//Plugin name", DatastarColors.ATTRIBUTE_PLUGIN),
                AttributesDescriptor("Attribute//Key", DatastarColors.ATTRIBUTE_KEY),
                AttributesDescriptor("Attribute//Modifier", DatastarColors.MODIFIER),
                AttributesDescriptor("Attribute//Modifier sigils (__ : .)", DatastarColors.MODIFIER_SIGIL),
                AttributesDescriptor("Attribute//Modifier argument", DatastarColors.MODIFIER_ARG),
            )
        val TAGS =
            mapOf(
                "signal" to DatastarColors.SIGNAL,
                "path" to DatastarColors.SIGNAL_PATH,
                "backend" to DatastarColors.ACTION_BACKEND,
                "action" to DatastarColors.ACTION,
                "scope" to DatastarColors.SCOPE_VARIABLE,
                "option" to DatastarColors.OPTION_KEY,
                "string" to DatastarColors.STRING,
                "regex" to DatastarColors.REGEX,
                "keyword" to DatastarColors.KEYWORD,
                "number" to DatastarColors.NUMBER,
                "prefix" to DatastarColors.ATTRIBUTE_PREFIX,
                "plugin" to DatastarColors.ATTRIBUTE_PLUGIN,
                "key" to DatastarColors.ATTRIBUTE_KEY,
                "modifier" to DatastarColors.MODIFIER,
                "sigil" to DatastarColors.MODIFIER_SIGIL,
                "arg" to DatastarColors.MODIFIER_ARG,
            )
    }
}
