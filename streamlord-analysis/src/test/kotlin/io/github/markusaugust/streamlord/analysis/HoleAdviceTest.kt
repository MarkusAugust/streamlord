package io.github.markusaugust.streamlord.analysis

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the issue of a value written into an HTML string says and offers, against
 * `cases/hole-advice.json`: the parts of a message a rule fixes, where the corpus holds whole
 * messages.
 */
class HoleAdviceTest {
    private val analyzer = Analyzer()

    @Test
    fun `says and offers what the cases say`() {
        val cases = sharedCases("hole-advice.json")
        assertTrue(cases.isNotEmpty())
        for (case in cases) {
            val name = case.string("name")
            val source = case.string("source")!!
            val expected = case.array("issues")!!.objects()
            val issues =
                analyzer
                    .analyzeKotlin(
                        Source(
                            source,
                            SourceLanguage.KOTLIN,
                            modules =
                                case.boolean("coreHtml")?.let { Modules.known(true, true, it) } ?: Modules.UNKNOWN,
                        ),
                    ).sortedWith(
                        compareBy({
                            it.start
                        }, { it.end }, { it.code }),
                    )
            assertEquals(
                expected.map { "${it.string("code")} ${it.string("text")}" },
                issues.map { "${it.code} ${source.substring(it.start, it.end)}" },
                name,
            )
            for ((issue, e) in issues.zip(expected)) {
                val at = "$name, ${e.string("text")}: ${issue.message}"
                assertEquals(e.array("titles")!!.strings(), issue.fixes.map { it.title }, at)
                for (title in e.array("notPreferred")?.strings().orEmpty()) {
                    assertEquals(false, issue.fixes.first { it.title == title }.preferred, "$name: $title")
                }
                for (part in e.array("says")?.strings().orEmpty()) assertTrue(part in issue.message, at)
                e.string("ends")?.let { assertTrue(issue.message.endsWith(it), at) }
                for (part in e.array("silent")?.strings().orEmpty()) assertTrue(part !in issue.message, at)
            }
        }
    }
}
