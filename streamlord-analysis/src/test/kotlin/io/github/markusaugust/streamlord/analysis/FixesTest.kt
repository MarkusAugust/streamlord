package io.github.markusaugust.streamlord.analysis

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The titles of the quick fixes, each marked when it may not be preferred, and the links of the issues against `cases/fixes.json`; the source
 * each fix leaves is in the shared corpus.
 */
class FixesTest {
    private val analyzer = Analyzer()

    private fun line(
        code: String?,
        text: String,
        titles: List<String>,
        link: String?,
    ) = "$code $text" + titles.joinToString("") { "\n  fix: $it" } + (link?.let { "\n  link: $it" } ?: "")

    @Test
    fun `titles each fix and links each issue as the shared cases say`() {
        val cases = sharedCases("fixes.json")
        assertTrue(cases.isNotEmpty())
        for (case in cases) {
            val source = case.string("source")!!
            val expected = case.array("issues")!!.objects()
            val issues =
                when (case.string("language")) {
                    "expression" -> analyzer.validateExpression(source)
                    "html" -> analyzer.analyzeHtml(source)
                    else -> analyzer.analyzeKotlin(source)
                }
            val seen =
                issues.sortedWith(compareBy({ it.start }, { it.end }, { it.code })).mapIndexed { n, i ->
                    val link = i.link.takeIf { expected.getOrNull(n)?.string("link") != null }
                    line(
                        i.code,
                        source.substring(i.start, i.end),
                        i.fixes.map { it.title + if (it.preferred) "" else " (not preferred)" },
                        link,
                    )
                }
            val wanted = expected.map { line(it.string("code"), it.string("text")!!, it.array("titles")!!.strings(), it.string("link")) }
            assertEquals(wanted, seen, case.string("name"))
        }
    }
}
