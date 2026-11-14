package io.github.markusaugust.streamlord.intellij

import com.intellij.codeInspection.LocalInspectionEP
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiDocumentManager
import io.github.markusaugust.streamlord.intellij.analysis.UiThreadWork
import io.github.markusaugust.streamlord.intellij.index.SignalIndex
import io.github.markusaugust.streamlord.intellij.inspections.UnknownSignalInspection
import kotlin.time.measureTime

/** A signal the server reads that no page of the project declares: the analysis's `signal-never-declared`, as VS Code shows it. */
class SignalDriftInspectionTest : StreamlordTestCase() {
    private val form =
        "@Serializable\ndata class Form(val query: String, val page: Int)\n" +
            "suspend fun search(call: ApplicationCall) { val form = call.readSignals<Form>() }\n"

    override fun setUp() {
        super.setUp()
        myFixture.enableInspections(UnknownSignalInspection())
    }

    private fun drift() = myFixture.doHighlighting().filter { it.description?.contains("No markup declares") == true }

    fun `test a property of a class the server reads signals into, that no page declares, is the warning vs code shows`() {
        myFixture.addFileToProject("page.html", "<input data-bind:query>")
        myFixture.configureByText("Search.kt", form)
        val found = drift()
        assertEquals(listOf("page"), found.map { it.text })
        assertEquals(HighlightSeverity.WARNING, found.single().severity)
        assertEquals(
            "No markup declares the signal 'page', so Form.page will always be its default. " +
                "Check the name against the page, or drop the property. See https://data-star.dev/guide/reactive_signals",
            found.single().description,
        )
    }

    fun `test a lookup of a signal no page declares is the warning`() {
        myFixture.addFileToProject("page.html", "<input data-bind:query>")
        myFixture.configureByText("Search.kt", "fun f(signals: Signals) { signals.string(\"query\"); signals.string(\"questQuery\") }")
        assertEquals(listOf("questQuery"), drift().map { it.text })
    }

    fun `test every signal the server reads declared by a page is nothing`() {
        myFixture.addFileToProject("page.html", "<input data-bind:query><div data-signals:page=\"1\"></div>")
        myFixture.configureByText("Search.kt", form)
        assertEquals(emptyList<String>(), drift().map { it.text })
    }

    // One switch in each editor, named alike: VS Code's is streamlord.diagnostics.unknownSignals.
    fun `test the inspection is named as the vs code setting that turns the same two warnings off`() {
        val ep = LocalInspectionEP.LOCAL_INSPECTION.extensionList.single { it.shortName == "UnknownSignalInspection" }
        assertEquals("Unknown signals", ep.displayName)
    }

    fun `test with the unknown-signal inspection off the check is off too`() {
        myFixture.addFileToProject("page.html", "<input data-bind:query>")
        myFixture.configureByText("Search.kt", form)
        myFixture.disableInspections(UnknownSignalInspection())
        assertEquals(emptyList<String>(), drift().map { it.text })
    }

    fun `test the page edited to declare the signal takes the warning away, and edited back brings it again`() {
        val page = myFixture.addFileToProject("page.html", "<input data-bind:query>")
        myFixture.configureByText("Search.kt", form)
        assertEquals(listOf("page"), drift().map { it.text })
        val document = PsiDocumentManager.getInstance(project).getDocument(page)!!

        fun write(text: String) =
            WriteCommandAction.runWriteCommandAction(project) {
                document.setText(text)
                PsiDocumentManager.getInstance(project).commitDocument(document)
            }
        write("<input data-bind:query><input data-bind:page>")
        assertEquals(emptyList<String>(), drift().map { it.text })
        write("<input data-bind:query>")
        assertEquals(listOf("page"), drift().map { it.text })
    }

    // Each file's facts are kept by the index and read again only when the file changes, so a project of many
    // pages costs a pass over what is kept: a thousand pages, highlighted after one of them changes.
    fun `test the check on a project of a thousand pages after one edit`() {
        val pages = (0 until 1000).map { myFixture.addFileToProject("pages/p$it.html", "<input data-bind:s$it>") }
        myFixture.configureByText("Search.kt", form)
        assertEquals(listOf("query", "page"), drift().map { it.text })
        val document = PsiDocumentManager.getInstance(project).getDocument(pages[500])!!
        WriteCommandAction.runWriteCommandAction(project) {
            document.setText("<input data-bind:query>")
            PsiDocumentManager.getInstance(project).commitDocument(document)
        }
        var found: List<String> = emptyList()
        val took = measureTime { found = drift().map { it.text } }
        assertEquals(listOf("page"), found)
        println("1,000 pages, highlighted after one edit: $took")
        assertTrue("the pass took $took", took.inWholeMilliseconds < 5_000)
    }

    // A file with no signals class and no lookup can report nothing, so the other files are not walked for it: the
    // project's read, which the counter sees on this thread, is not even asked for.
    fun `test a kotlin file that reads no signal asks the project nothing`() {
        myFixture.addFileToProject("page.html", "<input data-bind:query>")
        val file = myFixture.configureByText("Plain.kt", "fun f() = 1")
        val before = UiThreadWork.count.get()
        assertEquals(emptyList<Any>(), SignalIndex.getInstance(project).driftIn(file))
        assertEquals(0, UiThreadWork.count.get() - before)
    }
}
