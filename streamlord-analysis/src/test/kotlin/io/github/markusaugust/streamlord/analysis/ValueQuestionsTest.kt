package io.github.markusaugust.streamlord.analysis

import io.github.markusaugust.streamlord.core.json.JsonArray
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
    fun `asks about each value of a case in order, and judges it by the answer`() {
        eachCase(sharedCases("value-questions.json"), { it.string("name")!! }) { c ->
            val src = c.string("kotlin")!!
            val wanted = (c["questions"] as JsonArray).objects()
            val source = Source(src, SourceLanguage.KOTLIN)
            val asked = analyzer.valueQuestions(source)
            assertEquals(
                wanted.map { it.string("text") to (it.boolean("argument") == true) },
                asked.map { src.substring(it.start, it.end) to it.argument },
                "asked about",
            )
            val answers =
                asked.zip(wanted).associate { (q, w) ->
                    (q.start to q.end) to w.string("fact")?.let { ValueFact.valueOf(it.uppercase().replace('-', '_')) }
                }
            val answered = source.copy(valueFact = { start, end -> answers[start to end] })
            val shown =
                analyzer
                    .analyzeKotlin(answered)
                    .sortedBy { it.start }
                    .map { "${src.substring(it.start, it.end)} ${it.severity.name.lowercase()}" }
            assertEquals((c["issues"] as JsonArray).strings(), shown)
            // What is asked hangs on no answer, so an editor that has answered is asked nothing it was not told of.
            assertEquals(
                asked.map { it.start to it.end },
                analyzer.valueQuestions(answered).map { it.start to it.end },
                "asked about once answered",
            )
        }
    }
}
