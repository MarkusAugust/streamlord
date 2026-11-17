package io.github.markusaugust.streamlord.analysis

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The attribute prefix set in the editor is the one every reading of the file uses, as Datastar's bundle reads only its own. */
class AttributePrefixTest {
    private val analyzer = Analyzer()
    private val dsl = "import io.github.markusaugust.streamlord.html.*\n"

    private fun html(
        text: String,
        prefix: String,
    ) = analyzer.analyzeHtml(Source(text, SourceLanguage.HTML, prefix)).map { it.code }

    private fun kotlin(
        text: String,
        prefix: String,
    ) = analyzer.analyzeKotlin(Source(text, SourceLanguage.KOTLIN, prefix)).map { it.code }

    @Test
    fun `a kebab-case signal declared under the prefix is the one an expression reads`() {
        assertEquals(
            emptyList(),
            html("""<div data-ds-signals:my-value__case.kebab="1" data-ds-text="${'$'}my-value"></div>""", "data-ds-"),
        )
        assertEquals(emptyList(), html("""<input data-ds-bind="my-value"><p data-ds-text="${'$'}my-value"></p>""", "data-ds-"))
        val inKotlin = "fun f() = \"<div data-ds-signals:my-value__case.kebab=\\\"1\\\" data-ds-text=\\\"\\\$my-value\\\"></div>\""
        assertEquals(emptyList(), kotlin(inKotlin, "data-ds-"))
    }

    @Test
    fun `completion calls a signal declared under the prefix this file's`() {
        val text = """<div data-ds-signals:count="0" data-ds-text="${'$'}co"></div>"""
        val found =
            analyzer.complete(
                Source(text, SourceLanguage.HTML, "data-ds-"),
                text.indexOf("co\"") + 2,
                Workspace(listOf("count"), emptyList(), emptyList(), called = "workspace"),
            )
        assertEquals(listOf("signal (this file)"), found.items.filter { it.label == "\$count" }.map { it.detail })
    }

    @Test
    fun `a message names the attribute as the prefix writes it`() {
        fun messages(issues: List<Issue>) = issues.joinToString("\n") { it.message }
        val prefix = "data-ds-"
        val read =
            listOf(
                analyzer.analyzeHtml(Source("""<div data-ds-text="evt.key"></div>""", SourceLanguage.HTML, prefix)),
                analyzer.analyzeHtml(Source("""<div data-ds-computed:x="${'$'}a = 1"></div>""", SourceLanguage.HTML, prefix)),
                analyzer.analyzeKotlin(
                    Source(dsl + "fun FlowContent.f() { div { dataSignals(\"myValue\", \"1\") } }", SourceLanguage.KOTLIN, prefix),
                ),
                analyzer.analyzeKotlin(
                    Source(dsl + "fun FlowContent.f() { div { dataText(\"evt.key\") } }", SourceLanguage.KOTLIN, prefix),
                ),
            ).map(::messages)
        for (m in read) assertTrue(m.isNotEmpty() && Regex("""\bdata-(?!ds-)""").find(m) == null && "data-ds-" in m, m)
    }

    @Test
    fun `the attributes form of data-ignore is read under the prefix`() {
        fun page(ignore: String) =
            dsl + "fun FlowContent.f() { div { attributes[\"$ignore\"] = \"\"; span { dataText(\"\\\$count +\") } } }"
        assertEquals(emptyList(), kotlin(page("data-ds-ignore"), "data-ds-"))
        assertEquals(listOf(IssueCode.EXPRESSION_SYNTAX), kotlin(page("data-ignore"), "data-star-"))
        assertEquals(emptyList(), kotlin(page("data-ignore"), "data-"))
    }
}
