package io.github.markusaugust.streamlord.intellij

import com.intellij.lang.annotation.HighlightSeverity
import io.github.markusaugust.streamlord.core.json.JsonArray
import io.github.markusaugust.streamlord.core.json.JsonParser
import io.github.markusaugust.streamlord.intellij.inspections.KotlinInterpolationInspection
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
        for ((k, case) in JsonParser
            .parseObject(cases.readText())
            .array("cases")!!
            .objects()
            .withIndex()) {
            myFixture.configureByText("Case$k.kt", case.string("kotlin")!!)
            val shown =
                myFixture
                    .doHighlighting()
                    .filter { it.inspectionToolId == "KotlinInterpolationInspection" }
                    .sortedBy { it.startOffset }
                    .map { "${it.text} ${severity(it.severity)}" }
            val wanted = (case["issues"] as JsonArray).strings()
            if (shown != wanted) failed += "${case.string("name")}: expected $wanted but was $shown"
        }
        assertTrue(failed.joinToString("\n"), failed.isEmpty())
    }

    private fun severity(s: HighlightSeverity): String =
        when (s) {
            HighlightSeverity.ERROR -> "error"
            HighlightSeverity.WARNING -> "warning"
            HighlightSeverity.WEAK_WARNING -> "hint"
            else -> s.name
        }
}
