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

    fun `test a declaration of another file is read inside a larger value, and no further than itself`() {
        assertEquals(listOf("\${outer()}"), shown("\${outer()} \${\"by \" + badge()} \${if (c) badge() else \"\"}"))
    }

    fun `test a function of another file is read with the imports of that file`() {
        myFixture.addFileToProject(
            "parts/Cards.kt",
            "package parts\nimport io.github.markusaugust.streamlord.html.elements\nimport kotlinx.html.div\n" +
                "fun card(q: String): String = elements { div { +q } }\n" +
                "fun get(key: String): String = System.getenv(key)\n",
        )
        assertEquals(listOf("\${get(\"a\")}"), shown("\${card(q)} \${get(\"a\")}"))
    }

    fun `test a member of this file reached through a receiver is read where it stands`() {
        myFixture.configureByText(
            "Page.kt",
            "class P {\n    fun badge() = \"<b>x</b>\"\n    fun outer() = badge()\n    fun plain(q: String) = q\n}\n" +
                "fun page(p: P, q: String) = \"<div>\${p.outer()} \${p.plain(q)}</div>\"",
        )
        assertEquals(
            listOf("\${p.plain(q)}"),
            myFixture.doHighlighting().filter { it.inspectionToolId == "KotlinInterpolationInspection" }.map { it.text },
        )
    }

    fun `test an extension of another file is read as any function is`() {
        assertEquals(emptyList<String>(), shown("\${q.wrapped()}"))
        assertEquals(listOf("\${q.shouted()}"), shown("\${q.shouted()}"))
    }
}
