package io.github.markusaugust.streamlord.analysis

import io.github.markusaugust.streamlord.core.json.JsonArray
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The cases of `cases/value-questions.json`: the analysis says which values it wants to know about, is
 * answered as the case answers, and shows what the case expects. The IntelliJ plugin reads the same
 * cases with real types.
 */
class ValueQuestionsTest {
    private val analyzer = Analyzer()

    private fun shown(source: Source): List<String> =
        analyzer
            .analyzeKotlin(source)
            .sortedBy { it.start }
            .map { "${source.text.substring(it.start, it.end)} ${it.severity.name.lowercase()}" }

    @Test
    fun `asks about each value of a case in order, and judges it by the answer`() {
        eachCase(sharedCases("value-questions.json"), { it.string("name")!! }) { c ->
            val src = c.string("kotlin")!!
            val wanted = (c["questions"] as JsonArray).objects()
            val source = Source(src, SourceLanguage.KOTLIN, modules = Modules.known(core = true, htmlDsl = true))
            val asked = analyzer.valueQuestions(source)
            assertEquals(
                wanted.map { it.string("text") },
                asked.map { src.substring(it.start, it.end) },
                "asked about",
            )
            val answers =
                asked.zip(wanted).associate { (q, w) ->
                    (q.start to q.end) to ValueAnswer(w.string("type"), w.boolean("constant") == true)
                }
            assertEquals(
                wanted.map { "${it.string("text")}=${it.string("fact")}" },
                asked.zip(wanted).map { (q, w) ->
                    "${w.string("text")}=${answers.getValue(q.start to q.end).fact()?.name?.lowercase()?.replace('_', '-')}"
                },
                "the facts",
            )
            val answered = source.copy(valueAnswer = { start, end -> answers[start to end] })
            assertEquals((c["issues"] as JsonArray).strings(), shown(answered), "answered")
            // As an editor that resolves nothing reads the file, VS Code among them.
            assertEquals((c["unanswered"] as JsonArray).strings(), shown(source), "unanswered")
            // An editor is asked of nothing it was not told of, so it can answer all in one go.
            val listed = asked.mapTo(HashSet()) { it.start to it.end }
            val unlisted = ArrayList<String>()
            shown(
                source.copy(valueAnswer = { start, end ->
                    answers[start to end].also {
                        if (start to end !in
                            listed
                        ) {
                            unlisted += src.substring(start, end)
                        }
                    }
                }),
            )
            assertEquals(emptyList(), unlisted, "asked about though not listed")
        }
    }
}
