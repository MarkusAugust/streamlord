package io.github.markusaugust.streamlord.intellij

import io.github.markusaugust.streamlord.intellij.analysis.StreamlordAnalysis
import io.github.markusaugust.streamlord.intellij.index.SignalIndex

/** The signals a template reads are read with its engine's rules, for the inspection and for navigation alike. */
class TemplateEngineReadsTest : StreamlordTestCase() {
    fun `test a dollar brace in a jte javascript template is the engine's, not a signal`() {
        val file = myFixture.configureByText("row.jte", "<p data-text=\"`\${\$count}` + \$total\"></p>")
        assertEquals(listOf("total"), StreamlordAnalysis.getInstance(project).htmlSignalReferences(file).map { it.name })
        assertEquals(listOf("total"), SignalIndex.getInstance(project).readsIn(file).map { it.name })
    }
}
