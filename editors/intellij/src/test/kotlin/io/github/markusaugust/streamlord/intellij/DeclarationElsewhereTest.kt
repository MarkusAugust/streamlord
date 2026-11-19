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

    fun `test a declaration of this file is the one the value names, whatever else is spelled the same`() {
        myFixture.configureByText(
            "Page.kt",
            "import org.intellij.lang.annotations.Language\n" +
                "class B(@Language(\"HTML\") val body: String)\nclass A(val body: String)\n" +
                "fun page(a: A, b: B) = \"<div>\${a.body} \${b.body}</div>\"",
        )
        assertEquals(
            listOf("\${a.body}"),
            myFixture.doHighlighting().filter { it.inspectionToolId == "KotlinInterpolationInspection" }.map { it.text },
        )
    }

    fun `test what a val or a function of this file is set to is read through to another file`() {
        myFixture.configureByText(
            "Page.kt",
            "import parts.*\nfun wrapped() = badge()\nfun bare(q: String) = plain(q)\n" +
                "fun page(q: String): String {\n    val row = badge()\n    val stamp = Marks.stamp\n    val word = plain(q)\n" +
                "    return \"<div>\$row \$stamp \$word \${wrapped()} \${bare(q)}</div>\"\n}",
        )
        assertEquals(
            listOf("\$word", "\${bare(q)}"),
            myFixture.doHighlighting().filter { it.inspectionToolId == "KotlinInterpolationInspection" }.map { it.text },
        )
    }

    fun `test a helper's argument, an enum and a collection declared in another file are what resolving says`() {
        myFixture.addFileToProject(
            "parts/Keys.kt",
            "package parts\nimport io.github.markusaugust.streamlord.core.domain.Html\n" +
                "object Keys {\n    const val id = \"id\"\n    val loaded = System.getenv(\"K\")\n}\nenum class Tab { HOME }\n" +
                "class Repo {\n    fun rows(): List<Html> = emptyList()\n    fun names(): List<String> = emptyList()\n}\n",
        )
        myFixture.configureByText(
            "Page.kt",
            "import parts.*\nimport io.github.markusaugust.streamlord.html.signal\n" +
                "fun page(repo: Repo) = \"\"\"<b data-text=\"\${signal(Keys.id)}\" data-show=\"\${signal(Tab.HOME.name)}\" " +
                "data-class=\"\${signal(Keys.loaded)}\">\${Tab.HOME}</b>\${repo.rows().joinToString(\"\")}\${repo.names().joinToString(\"\")}\"\"\"",
        )
        assertEquals(
            listOf("\${signal(Keys.loaded)}", "\${repo.names().joinToString(\"\")}"),
            myFixture.doHighlighting().filter { it.inspectionToolId == "KotlinInterpolationInspection" }.map { it.text },
        )
    }
}
