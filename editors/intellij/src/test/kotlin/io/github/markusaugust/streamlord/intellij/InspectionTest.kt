package io.github.markusaugust.streamlord.intellij

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.markusaugust.streamlord.intellij.analysis.StreamlordAnalysis
import io.github.markusaugust.streamlord.intellij.inspections.DatastarAttributeInspection
import io.github.markusaugust.streamlord.intellij.inspections.DatastarExpressionInspection
import io.github.markusaugust.streamlord.intellij.inspections.DatastarKeyCaseInspection
import io.github.markusaugust.streamlord.intellij.inspections.InspectionCodes
import io.github.markusaugust.streamlord.intellij.inspections.KotlinInterpolationInspection
import io.github.markusaugust.streamlord.intellij.inspections.StreamlordMarkupInspection
import io.github.markusaugust.streamlord.intellij.inspections.UnknownSignalInspection

class InspectionTest : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        myFixture.enableInspections(
            KotlinInterpolationInspection(),
            DatastarExpressionInspection(),
            DatastarAttributeInspection(),
            DatastarKeyCaseInspection(),
            StreamlordMarkupInspection(),
            UnknownSignalInspection(),
        )
    }

    fun `test kotlin interpolation is an error with the helper as the first fix`() {
        myFixture.configureByText("Routes.kt", "fun f() { dataOnClick(\"\$count++\") }")
        val highlights = myFixture.doHighlighting().filter { it.description?.contains("Kotlin interpolates") == true }
        assertEquals(1, highlights.size)
        assertEquals("\$count", highlights[0].text)
        val fix = myFixture.getAllQuickFixes().first { it.text == "Use increment(\"count\")" }
        myFixture.launchAction(fix)
        myFixture.checkResult("fun f() { dataOnClick(increment(\"count\")) }")
    }

    fun `test html strings in kotlin get the attribute checks with offsets`() {
        myFixture.configureByText(
            "View.kt",
            "fun side(): String = \"\"\"<div data-on:click__debunce.500ms=\"@post('/x')\"><p data-text=\"\${'\$'}count +\"></p></div>\"\"\"",
        )
        val highlights = myFixture.doHighlighting()
        assertEquals(listOf("debunce.500ms"), highlights.filter { it.description?.startsWith("Unknown modifier") == true }.map { it.text })
        // The syntax error sits at the end of the expression, which is the closing quote of the attribute.
        assertEquals(1, highlights.count { it.description?.startsWith("Datastar expression") == true })
        val fix = myFixture.getAllQuickFixes().first { it.text == "Change to __debounce" }
        myFixture.launchAction(fix)
        assertTrue(
            myFixture.editor.document.text
                .contains("__debounce.500ms"),
        )
    }

    fun `test html files get the template side`() {
        myFixture.configureByText(
            "page.html",
            "<form data-on:submit__prevent=\"@post('/save')\"><input data-bind:search data-onn:x=\"1\"></form>",
        )
        val highlights = myFixture.doHighlighting().filter { it.description?.contains("Unknown Datastar attribute") == true }
        assertEquals(listOf("data-onn:x"), highlights.map { it.text })
    }

    fun `test key case warns in html and hints in the dsl`() {
        myFixture.configureByText("keys.html", "<div data-signals:fooBar=\"1\"></div>")
        val html = myFixture.doHighlighting().filter { it.description?.contains("browser lowercases") == true }
        assertEquals(listOf("fooBar"), html.map { it.text })
        myFixture.configureByText("Keys.kt", "fun f() { dataSignals(\"fooBar\", \"1\") }")
        val kt = myFixture.doHighlighting().filter { it.description?.contains("Written on the wire") == true }
        assertEquals(listOf("\"fooBar\""), kt.map { it.text })
    }

    fun `test a call that resolves to something else is left alone`() {
        myFixture.configureByText(
            "Mine.kt",
            """
            package mine
            fun patchElements(html: String) = html
            fun f() = patchElements("<div>no id</div>")
            """.trimIndent(),
        )
        assertEquals(
            emptyList<String>(),
            myFixture.doHighlighting().filter { it.description?.contains("has no id") == true }.map { it.text },
        )
        myFixture.configureByText("Theirs.kt", "fun f() = patchElements(\"<div>no id</div>\")")
        assertEquals(1, myFixture.doHighlighting().count { it.description?.contains("has no id") == true })
    }

    fun `test a signal no file defines is a warning`() {
        val q = "\"\"\""
        myFixture.configureByText(
            "Demo.kt",
            "fun f(): String = \$\$$q<div data-signals:teller=\"0\"><button data-on:click=\"\$telefon++\">x</button>" +
                "<span data-text=\"\$teller\"></span></div>$q",
        )
        val highlights = myFixture.doHighlighting().filter { it.description?.contains("defines the signal") == true }
        assertEquals(listOf("\$telefon"), highlights.map { it.text })
    }

    fun `test a signal another file defines is known, and a near one is the fix`() {
        myFixture.addFileToProject("page.html", "<div data-signals:count=\"0\"></div>")
        myFixture.configureByText("Routes.kt", "fun f() { dataText(\"\\\$count + \\\$coutn\") }")
        val highlights = myFixture.doHighlighting().filter { it.description?.contains("defines the signal") == true }
        assertEquals(listOf("\\\$coutn"), highlights.map { it.text })
        myFixture.launchAction(myFixture.getAllQuickFixes().first { it.text == "Change to \$count" })
        myFixture.checkResult("fun f() { dataText(\"\\\$count + \\\$count\") }")
    }

    fun `test a template file defines signals, and a deleted file no longer does`() {
        myFixture.addFileToProject("search.peb", "<input data-bind:query>")
        val page = myFixture.addFileToProject("page.html", "<div data-signals:total=\"0\"></div>")
        myFixture.configureByText("Routes.kt", "fun f() { dataText(\"\\\$query + \\\$total\") }")
        val unknown = { myFixture.doHighlighting().filter { it.description?.contains("defines the signal") == true }.map { it.text } }
        assertEquals(emptyList<String>(), unknown())
        com.intellij.openapi.command.WriteCommandAction
            .runWriteCommandAction(project) { page.virtualFile.delete(this) }
        assertEquals(listOf("\\\$total"), unknown())
    }

    fun `test every issue code belongs to an inspection`() {
        val analyzer = StreamlordAnalysis.getInstance(project).analyzer
        val src =
            listOf(
                "dataOnClick(\"\$a\"); dataText(\"@Post('x') + \$b-c +\"); dataOnClick(\"@clipboard(1)\"); dataText(\" \")",
                "s.patchElements(\"\"\"<div data-onn:x=\"1\" data-on:click__debunce=\"y()\" data-on=\"1\" data-text:k=\"1\" data-persist=\"1\"",
                " data-bind=\"\$x + 1\" data-star-on:x=\"1\" data-signals:fooBar=\"1\" data-on-intersect__threshold.150=\"y()\"></div><p>x\"\"\")",
                "s.patchElements(\"<li>x</li>\", mode = ElementPatchMode.APPEND); s.removeElements(\" \"); s.removeElements(\"a\\nb\")",
                "s.executeScript(\"'</script>'\"); dataSignals(\"fooBar\", \"1\"); s.patchElements(\"text </b>\")",
            ).joinToString("\n")
        val codes = analyzer.analyzeKotlin(src).mapNotNull { it.code }.toSet()
        val unclaimed = codes - InspectionCodes.all
        assertEquals(emptySet<String>(), unclaimed)
        assertTrue(codes.toString(), codes.size > 15)
    }
}
