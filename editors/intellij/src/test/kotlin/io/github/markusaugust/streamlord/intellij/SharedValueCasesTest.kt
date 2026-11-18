package io.github.markusaugust.streamlord.intellij

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.util.TextRange
import io.github.markusaugust.streamlord.analysis.SourceLanguage
import io.github.markusaugust.streamlord.core.json.JsonArray
import io.github.markusaugust.streamlord.core.json.JsonParser
import io.github.markusaugust.streamlord.intellij.analysis.StreamlordAnalysis
import io.github.markusaugust.streamlord.intellij.inspections.KotlinInterpolationInspection
import org.jetbrains.kotlin.psi.KtFile
import java.io.File

/**
 * The cases of `cases/value-questions.json`, which the analysis reads with the facts each case gives: here the Kotlin
 * plugin's own types answer, and the editor must show the same.
 */
class SharedValueCasesTest : StreamlordTestCase() {
    private val cases = File(System.getProperty("streamlord.repo"), "editors/vscode/test/fixtures/cases/value-questions.json")

    override fun setUp() {
        super.setUp()
        myFixture.enableInspections(KotlinInterpolationInspection())
    }

    fun `test the types answer each case as its facts do`() {
        val failed = ArrayList<String>()
        val analysis = StreamlordAnalysis.getInstance(project)
        for ((k, case) in JsonParser
            .parseObject(cases.readText())
            .array("cases")!!
            .objects()
            .withIndex()) {
            val file = myFixture.configureByText("Case$k.kt", case.string("kotlin")!!) as KtFile
            val shown =
                myFixture
                    .doHighlighting()
                    .filter { it.inspectionToolId == "KotlinInterpolationInspection" }
                    .sortedBy { it.startOffset }
                    .map { "${it.text} ${severity(it.severity)}" }
            val wanted = (case["issues"] as JsonArray).strings()
            if (shown != wanted) failed += "${case.string("name")}: expected $wanted but was $shown"
            // Each value by the fact the types gave it, in the order the analysis asks: a wrong type is told from a range that met nothing.
            val asked = analysis.analyzer.valueQuestions(analysis.source(file.text, SourceLanguage.KOTLIN, file))
            val facts = background { analysis.valueFacts(file) }
            val given = asked.map { "${file.text.substring(it.start, it.end)}=${facts[TextRange(it.start, it.end)]?.name?.lowercase()}" }
            val facted = (case["questions"] as JsonArray).objects().map { "${it.string("text")}=${it.string("fact")}" }
            if (given != facted) failed += "${case.string("name")}: expected the facts $facted but they were $given"
        }
        assertTrue(failed.joinToString("\n"), failed.isEmpty())
    }

    /** In a read action off the UI thread, where the Kotlin plugin answers. */
    private fun <T> background(block: () -> T): T =
        ApplicationManager
            .getApplication()
            .executeOnPooledThread<T> { ReadAction.nonBlocking<T>(block).executeSynchronously() }
            .get()

    private fun severity(s: HighlightSeverity): String =
        when (s) {
            HighlightSeverity.ERROR -> "error"
            HighlightSeverity.WARNING -> "warning"
            HighlightSeverity.WEAK_WARNING -> "hint"
            else -> s.name
        }
}
