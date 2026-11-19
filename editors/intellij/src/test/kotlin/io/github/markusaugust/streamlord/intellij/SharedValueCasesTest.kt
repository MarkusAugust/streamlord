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
 * The cases of `cases/value-questions.json`, which the analysis reads with the answers each case gives: here the Kotlin
 * plugin's own types answer, and the editor must answer and show the same.
 */
class SharedValueCasesTest : StreamlordTestCase() {
    private val cases = File(System.getProperty("streamlord.repo"), "editors/vscode/test/fixtures/cases/value-questions.json")

    override fun setUp() {
        super.setUp()
        myFixture.enableInspections(KotlinInterpolationInspection())
        myFixture.addFileToProject(
            "stubs/Language.kt",
            "package org.intellij.lang.annotations\nannotation class Language(val value: String)",
        )
    }

    fun `test the types answer each case as it answers`() {
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
            // Each value by what the types answered, in the order the analysis asks: a wrong type is told from a range that met nothing.
            val asked = analysis.analyzer.valueQuestions(analysis.source(file.text, SourceLanguage.KOTLIN, file))
            val answers = background { analysis.valueAnswers(file) }
            val given =
                asked.map {
                    val answer = answers[TextRange(it.start, it.end)]
                    listOf(file.text.substring(it.start, it.end), answer?.type, answer?.constant == true)
                }
            val answered =
                (case["questions"] as JsonArray).objects().map {
                    listOf(it.string("text"), it.string("type"), it.boolean("constant") == true)
                }
            if (given != answered) failed += "${case.string("name")}: expected the answers $answered but they were $given"
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
