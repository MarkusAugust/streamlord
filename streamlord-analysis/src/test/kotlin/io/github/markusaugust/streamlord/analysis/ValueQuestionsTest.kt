package io.github.markusaugust.streamlord.analysis

import io.github.markusaugust.streamlord.core.json.JsonArray
import io.github.markusaugust.streamlord.core.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The cases of `cases/value-questions.json`: the analysis says which values it wants to know about, is
 * answered with the case's facts, and shows what the case expects. The IntelliJ plugin reads the same
 * cases with real types.
 */
class ValueQuestionsTest {
    private val analyzer = Analyzer()

    @Test
    fun `asks about each value by the range it judges it by, and judges it by the answer`() {
        eachCase(sharedCases("value-questions.json"), { it.string("name")!! }) { c ->
            val src = c.string("kotlin")!!
            val facts = c["facts"] as JsonObject
            val source = Source(src, SourceLanguage.KOTLIN)
            val asked = analyzer.valueQuestions(source).map { src.substring(it.start, it.end) }
            assertEquals(facts.keys, asked.toSet(), "asked about")
            val answers =
                analyzer.valueQuestions(source).associate { q ->
                    (q.start to q.end) to ValueFact.valueOf(facts.string(src.substring(q.start, q.end))!!.uppercase().replace('-', '_'))
                }
            val shown =
                analyzer
                    .analyzeKotlin(source.copy(valueFact = { start, end -> answers[start to end] }))
                    .sortedBy { it.start }
                    .map { "${src.substring(it.start, it.end)} ${it.severity.name.lowercase()}" }
            assertEquals((c["issues"] as JsonArray).strings(), shown)
        }
    }
}
