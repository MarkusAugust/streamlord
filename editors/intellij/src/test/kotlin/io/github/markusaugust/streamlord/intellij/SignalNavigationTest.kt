package io.github.markusaugust.streamlord.intellij

import com.intellij.codeInsight.TargetElementUtil
import com.intellij.codeInsight.navigation.actions.GotoDeclarationOrUsageHandler2
import com.intellij.psi.impl.source.tree.injected.InjectedLanguageEditorUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.markusaugust.streamlord.intellij.index.SignalIndex
import io.github.markusaugust.streamlord.intellij.navigation.SignalTarget

class SignalNavigationTest : BasePlatformTestCase() {
    fun `test the index keeps where each signal is defined and read`() {
        val page =
            myFixture.addFileToProject(
                "page.html",
                "<div data-signals:count=\"0\"><span data-text=\"\$count + \$user.name\"></span></div>",
            )
        val view =
            myFixture.addFileToProject(
                "View.kt",
                "import io.github.markusaugust.streamlord.html.*\nfun f() = div { dataSignals(\"user\" to 1); dataText(\"\${'\$'}count\") }",
            )
        val index = SignalIndex.getInstance(project)

        val declared = index.declarationsOf("count").map { it.file.name to page.text.substring(it.start, it.end) }
        assertEquals(listOf("page.html" to "count"), declared)
        val reads = index.readsOf("count").map { it.file.name }.sorted()
        assertEquals(listOf("View.kt", "page.html"), reads)
        // A read into a signal is a read of it.
        val user = index.readsOf("user").single()
        assertEquals("\$user.name", page.text.substring(user.start, user.end))
        assertEquals("user", view.text.substring(index.declarationsOf("user").single().let { it.start until it.end }))
    }

    private val dsl = "import io.github.markusaugust.streamlord.html.*\n"

    /**
     * Where go to declaration lands from the caret: the file and the text at each target. Ctrl+click
     * goes there, and not to find usages, which it does on a defining place.
     */
    private fun targets(): List<Pair<String, String>> {
        val outcome = GotoDeclarationOrUsageHandler2.testGTDUOutcome(myFixture.editor, myFixture.file, myFixture.caretOffset)
        assertEquals(GotoDeclarationOrUsageHandler2.GTDUOutcome.GTD, outcome)
        // In HTML injected into a Kotlin string the reference is the string's, at the offset in the file.
        val editor = InjectedLanguageEditorUtil.getTopLevelEditor(myFixture.editor)
        val reference = TargetElementUtil.findReference(editor, editor.caretModel.offset)!!
        return TargetElementUtil.getInstance().getTargetCandidates(reference).map {
            val t = it as SignalTarget
            t.containingFile.name to t.containingFile.text.substring(t.textRange.startOffset, t.textRange.endOffset)
        }
    }

    fun `test go to declaration from markup to markup and to a DSL call`() {
        myFixture.addFileToProject("page.html", "<div data-signals:count=\"0\"></div>")
        myFixture.addFileToProject("Handler.kt", dsl + "fun h() = patchSignals(mapOf(\"count\" to 1))")
        myFixture.configureByText("other.html", "<span data-text=\"\$co<caret>unt + 1\"></span>")
        assertEquals(listOf("Handler.kt" to "count", "page.html" to "count"), targets().sortedBy { it.first })
    }

    fun `test go to declaration from a Kotlin string, plain and with HTML in it`() {
        myFixture.addFileToProject("page.html", "<div data-signals=\"{user: {name: ''}}\"></div>")
        myFixture.configureByText("View.kt", dsl + "fun f() = span { dataText(\"\${'\$'}user.na<caret>me.length\") }")
        assertEquals(listOf("page.html" to "name"), targets())
        myFixture.configureByText("Free.kt", "fun g() = \"\"\"<span data-text=\"\${'\$'}us<caret>er\"></span>\"\"\"")
        assertEquals(listOf("page.html" to "user"), targets())
    }

    fun `test ctrl+click on a defining place lists every place the signal is defined`() {
        myFixture.addFileToProject("Handler.kt", dsl + "fun h() = patchSignals(mapOf(\"count\" to 1))")
        myFixture.configureByText("page.html", "<div data-signals:co<caret>unt=\"0\"></div>")
        assertEquals(listOf("Handler.kt" to "count", "page.html" to "count"), targets().sortedBy { it.first })
    }

    fun `test find usages lists every place a signal is defined and read`() {
        myFixture.addFileToProject("page.html", "<div data-signals:count=\"0\"><span data-text=\"\$count\"></span></div>")
        myFixture.addFileToProject("View.kt", dsl + "fun f() = span { dataText(\"\${'\$'}count + \${'\$'}other\") }")
        myFixture.configureByText("other.html", "<b data-text=\"\$co<caret>unt\"></b>")
        assertEquals(
            GotoDeclarationOrUsageHandler2.GTDUOutcome.GTD,
            GotoDeclarationOrUsageHandler2.testGTDUOutcome(myFixture.editor, myFixture.file, myFixture.caretOffset),
        )
        val target =
            TargetElementUtil.findReference(myFixture.editor, myFixture.caretOffset)!!.let {
                TargetElementUtil.getInstance().getTargetCandidates(it).first()
            }
        val usages =
            myFixture.findUsages(target).map { usage ->
                val range = usage.navigationRange
                usage.file!!.name to usage.file!!.text.substring(range.startOffset, range.endOffset)
            }
        assertEquals(
            listOf("View.kt" to "count", "other.html" to "count", "page.html" to "count", "page.html" to "count"),
            usages.sortedWith(compareBy({ it.first }, { it.second })),
        )
    }
}
