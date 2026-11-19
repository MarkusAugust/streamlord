package io.github.markusaugust.streamlord.intellij

import io.github.markusaugust.streamlord.intellij.inspections.KotlinInterpolationInspection

/** A value that names a declaration of another file: the plugin sends the declaration, and the analysis reads it. */
class DeclarationElsewhereTest : StreamlordTestCase() {
    override fun setUp() {
        super.setUp()
        myFixture.enableInspections(KotlinInterpolationInspection())
        myFixture.addFileToProject(
            "stubs/Language.kt",
            "package org.intellij.lang.annotations\nannotation class Language(val value: String)",
        )
        myFixture.addFileToProject(
            "parts/Parts.kt",
            "package parts\nimport org.intellij.lang.annotations.Language\n" +
                "fun badge(): String = \"<b>new</b>\"\n" +
                "fun either(open: Boolean): String {\n    if (open) return \"<b>a</b>\"\n    return \"<i>b</i>\"\n}\n" +
                "@Language(\"HTML\") fun loaded(): String = load()\n" +
                "fun load(): String = \"\"\n" +
                "fun plain(who: String) = who\n" +
                "fun outer() = badge()\n" +
                "object Marks {\n    val stamp = \"<i>seen</i>\"\n    fun seal() = \"<u>ok</u>\"\n    val word = \"seen\"\n}\n" +
                "class Card(@Language(\"HTML\") val body: String)\n" +
                "fun String.wrapped() = \"<p>\" + this + \"</p>\"\n" +
                "fun String.shouted() = uppercase()\n",
        )
    }

    private fun shown(values: String): List<String> {
        myFixture.configureByText(
            "Page.kt",
            "import parts.*\nfun page(q: String, c: Boolean, card: Card) = \"\"\"<div>$values</div>\"\"\"",
        )
        return myFixture
            .doHighlighting()
            .filter { it.inspectionToolId == "KotlinInterpolationInspection" }
            .sortedBy { it.startOffset }
            .map { it.text }
    }

    fun `test markup a function or a val of another file gives is markup`() {
        assertEquals(
            emptyList<String>(),
            shown("\${badge()} \${either(c)} \${loaded()} \${Marks.stamp} \${Marks.seal()} \${card.body}"),
        )
    }

    fun `test what another file's declaration does not show to be markup is a String`() {
        assertEquals(listOf("\${plain(q)}", "\${load()}", "\${Marks.word}"), shown("\${plain(q)} \${load()} \${Marks.word}"))
    }

    fun `test a declaration is followed one file away, and not from inside a larger value`() {
        assertEquals(listOf("\${outer()}", "\${q + badge()}"), shown("\${outer()} \${q + badge()}"))
    }

    fun `test an extension of another file is read as any function is`() {
        assertEquals(emptyList<String>(), shown("\${q.wrapped()}"))
        assertEquals(listOf("\${q.shouted()}"), shown("\${q.shouted()}"))
    }
}
