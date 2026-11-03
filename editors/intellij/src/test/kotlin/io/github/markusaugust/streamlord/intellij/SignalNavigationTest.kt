package io.github.markusaugust.streamlord.intellij

import com.intellij.codeInsight.TargetElementUtil
import com.intellij.codeInsight.navigation.actions.GotoDeclarationAction
import com.intellij.codeInsight.navigation.actions.GotoDeclarationOrUsageHandler2
import com.intellij.psi.impl.source.tree.injected.InjectedLanguageEditorUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.markusaugust.streamlord.intellij.index.SignalIndex
import io.github.markusaugust.streamlord.intellij.navigation.SignalTarget
import io.github.markusaugust.streamlord.intellij.navigation.SignalUsageTargetProvider

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
        val outcome =
            GotoDeclarationOrUsageHandler2.testGTDUOutcomeInNonBlockingReadAction(
                myFixture.editor,
                myFixture.file,
                myFixture.caretOffset,
            )
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

    fun `test rename writes the new name as each place wants it`() {
        val page = myFixture.addFileToProject("page.html", "<div data-signals:count=\"0\"><span data-text=\"\$count\"></span></div>")
        val view =
            myFixture.addFileToProject(
                "View.kt",
                dsl + "fun f() = span { dataText(\"\${'\$'}count\") }\nfun h() = patchSignals(mapOf(\"count\" to 1))",
            )
        myFixture.configureByText("other.html", "<b data-text=\"\$co<caret>unt + 1\"></b>")
        myFixture.renameElementAtCaretUsingHandler("totalCount")
        assertEquals("<b data-text=\"\$totalCount + 1\"></b>", myFixture.file.text)
        assertEquals("<div data-signals:total-count=\"0\"><span data-text=\"\$totalCount\"></span></div>", page.text)
        assertEquals(
            dsl + "fun f() = span { dataText(\"\${'\$'}totalCount\") }\nfun h() = patchSignals(mapOf(\"totalCount\" to 1))",
            view.text,
        )
    }

    fun `test rename of an object moves what is under it`() {
        val page = myFixture.addFileToProject("page.html", "<div data-signals=\"{user: {name: ''}}\"></div>")
        val view = myFixture.addFileToProject("View.kt", dsl + "fun h() = patchSignals(mapOf(\"user.name\" to 1))")
        myFixture.configureByText("Free.kt", "fun g() = \"\"\"<span data-text=\"\${'\$'}us<caret>er.name\"></span>\"\"\"")
        myFixture.renameElementAtCaretUsingHandler("account")
        assertEquals("<div data-signals=\"{account: {name: ''}}\"></div>", page.text)
        assertEquals(dsl + "fun h() = patchSignals(mapOf(\"account.name\" to 1))", view.text)
        assertEquals(
            "fun g() = \"\"\"<span data-text=\"\${'\$'}account.name\"></span>\"\"\"",
            InjectedLanguageEditorUtil.getTopLevelEditor(myFixture.editor).document.text,
        )
    }

    fun `test each part of a read names its own signal`() {
        myFixture.addFileToProject("page.html", "<div data-signals=\"{user: {name: ''}}\"></div>")
        myFixture.configureByText("other.html", "<b data-text=\"\$user.na<caret>me.length\"></b>")
        assertEquals(listOf("page.html" to "name"), targets())
        myFixture.renameElementAtCaretUsingHandler("user.title")
        assertEquals("<b data-text=\"\$user.title.length\"></b>", myFixture.file.text)
        assertEquals(
            "<div data-signals=\"{user: {title: ''}}\"></div>",
            myFixture.findFileInTempDir("page.html").let {
                psiManager.findFile(it)!!.text
            },
        )
    }

    fun `test rename stops at a place that cannot hold the new name`() {
        myFixture.configureByText("page.html", "<div data-signals:my-value__case.snake=\"0\" data-text=\"\$my_va<caret>lue\"></div>")
        try {
            myFixture.renameElementAtCaretUsingHandler("myTitle")
            fail("a key under __case.snake gives no camelCase name back")
        } catch (e: com.intellij.refactoring.BaseRefactoringProcessor.ConflictsInTestsException) {
            assertTrue(e.messages.joinToString(), e.messages.any { "my-value" in it && "myTitle" in it })
        }
        myFixture.renameElementAtCaretUsingHandler("my_title")
        assertEquals("<div data-signals:my-title__case.snake=\"0\" data-text=\"\$my_title\"></div>", myFixture.file.text)
    }

    fun `test a search reads only what the index reads, and no word search`() {
        val skipped = myFixture.addFileToProject("build/gen.html", "<b data-text=\"\$count\"></b>")
        myFixture.addFileToProject("page.html", "<div data-signals:count=\"0\"></div>")
        myFixture.configureByText("other.html", "<b data-text=\"\$co<caret>unt\"></b>")
        val target =
            TargetElementUtil
                .getInstance()
                .getTargetCandidates(
                    TargetElementUtil.findReference(myFixture.editor, myFixture.caretOffset)!!,
                ).first()
        assertEquals(listOf("other.html", "page.html"), myFixture.findUsages(target).map { it.file!!.name }.sorted())
        myFixture.renameElementAtCaretUsingHandler("total")
        assertEquals("<b data-text=\"\$count\"></b>", skipped.text)
    }

    fun `test a file with CRLF line endings that no editor holds`() {
        val vf = myFixture.tempDirFixture.createFile("page.html", "<p>\r\n</p>\r\n<div data-signals:count=\"0\"></div>")
        myFixture.configureByText("other.html", "<b data-text=\"\$co<caret>unt\"></b>")
        assertEquals(listOf("page.html" to "count"), targets())
        assertTrue(vf.isValid)
    }

    fun `test an assignment is renamed once`() {
        myFixture.configureByText(
            "page.html",
            "<div data-signals:count=\"0\" data-on:click=\"\$count = \$count + 1\" data-text=\"\$co<caret>unt\"></div>",
        )
        myFixture.renameElementAtCaretUsingHandler("total")
        assertEquals(
            "<div data-signals:total=\"0\" data-on:click=\"\$total = \$total + 1\" data-text=\"\$total\"></div>",
            myFixture.file.text,
        )
    }

    fun `test a call to something else defines no signal and is not renamed`() {
        val other =
            myFixture.addFileToProject(
                "Other.kt",
                "class Other { fun patchSignals(m: Map<String, Int>) = m }\nfun o() = Other().patchSignals(mapOf(\"count\" to 1))",
            )
        myFixture.addFileToProject("page.html", "<div data-signals:count=\"0\"></div>")
        myFixture.configureByText("other.html", "<b data-text=\"\$co<caret>unt\"></b>")
        assertEquals(listOf("page.html" to "count"), targets())
        myFixture.renameElementAtCaretUsingHandler("total")
        assertTrue(other.text, "\"count\" to 1" in other.text)
    }

    fun `test a place a rename cannot reach is a conflict`() {
        myFixture.addFileToProject("Signals.kt", "@Serializable data class S(val count: Int)")
        myFixture.configureByText("page.html", "<div data-signals:count=\"0\" data-text=\"\$co<caret>unt\"></div>")
        try {
            myFixture.renameElementAtCaretUsingHandler("total")
            fail("a property of a signals class is Kotlin's to rename")
        } catch (e: com.intellij.refactoring.BaseRefactoringProcessor.ConflictsInTestsException) {
            assertTrue(e.messages.joinToString(), e.messages.any { "property of a signals class" in it })
        }
    }

    fun `test a template read as plain text is navigated and renamed`() {
        val template = myFixture.addFileToProject("page.jte", "<div data-signals:count=\"0\"></div>")
        myFixture.configureByText("other.html", "<b data-text=\"\$co<caret>unt\"></b>")
        assertEquals(listOf("page.jte" to "count"), targets())
        myFixture.renameElementAtCaretUsingHandler("total")
        assertEquals("<div data-signals:total=\"0\"></div>", template.text)
        myFixture.configureByText("view.jte", "<b data-text=\"\$to<caret>tal\"></b>")
        val outcome =
            GotoDeclarationOrUsageHandler2.testGTDUOutcomeInNonBlockingReadAction(
                myFixture.editor,
                myFixture.file,
                myFixture.caretOffset,
            )
        assertEquals(GotoDeclarationOrUsageHandler2.GTDUOutcome.GTD, outcome)
        val found = GotoDeclarationAction.findAllTargetElements(project, myFixture.editor, myFixture.caretOffset)
        assertEquals(listOf("page.jte"), found.map { it.containingFile.name })
        val usages = SignalUsageTargetProvider().getTargets(myFixture.editor, myFixture.file)!!.single()
        val target = (usages as com.intellij.usages.PsiElementUsageTarget).element
        assertEquals(listOf("other.html", "page.jte", "view.jte"), myFixture.findUsages(target).map { it.file!!.name }.sorted())
        myFixture.renameElementAtCaretUsingHandler("sum")
        assertEquals("<div data-signals:sum=\"0\"></div>", template.text)
        assertEquals("<b data-text=\"\$sum\"></b>", myFixture.file.text)
    }
}
