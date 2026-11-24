package io.github.markusaugust.streamlord.analysis

import kotlin.test.Test
import kotlin.test.assertEquals

/** Brackets matched in code alone, by the one [matching], wherever the analysis reads a lambda or a list in brackets. */
class OneBracketMatchTest {
    private val dsl = "import io.github.markusaugust.streamlord.html.*\n"

    private fun modifierIssues(body: String) = Analyzer().analyzeKotlin(dsl + body).count { it.code == IssueCode.MODIFIER_ARGS }

    @Test
    fun `a modifiers lambda is read past a brace in a string or a comment`() {
        for (lambda in listOf("{ val s = \"}\"; half = true; full = true }", "{ half = true; /* } */ full = true }")) {
            assertEquals(1, modifierIssues("fun DIV.a() {\n    dataOnIntersect(\"@get('/x')\") $lambda\n}\n"), lambda)
        }
    }

    @Test
    fun `a modifiers lambda with a bracket left open reaches no further call`() {
        val open =
            "fun DIV.a() {\n    dataOnIntersect(\"@get('/more')\") { half = true; println(\n    }\n" +
                "    dataOnIntersect(\"@get('/x')\") { full = true }\n}\n"
        assertEquals(0, modifierIssues(open))
    }

    @Test
    fun `a constructor's annotations are read to their end, whatever their strings hold`() {
        val src = "data class Form @Inject @Named(\")\") constructor(val firstName: String)\n"
        assertEquals(listOf("firstName"), signalClasses(src).single { it.name == "Form" }.properties.map { it.name })
    }
}
