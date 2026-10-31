package io.github.markusaugust.streamlord.analysis

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The titles of the quick fixes, each marked when it may not be preferred, and the links of the issues against `twins/fixes.json`, which
 * `fixes.test.ts` reads as well; the source each fix leaves is in the shared corpus.
 */
class FixesTest {
    private val analyzer = Analyzer()
    private val opts = AnalyzeOptions("data-", true)

    private fun line(
        code: String?,
        text: String,
        titles: List<String>,
        link: String?,
    ) = "$code $text" + titles.joinToString("") { "\n  fix: $it" } + (link?.let { "\n  link: $it" } ?: "")

    @Test
    fun `titles each fix and links each issue as the twin cases say`() {
        val cases = twinCases("fixes.json")
        assertTrue(cases.isNotEmpty())
        for (case in cases) {
            val source = case.string("source")!!
            val expected = case.array("issues")!!.objects()
            val issues =
                when (case.string("language")) {
                    "expression" -> analyzer.validateExpression(source)
                    "html" -> analyzer.analyzeHtml(source, opts)
                    else -> analyzer.analyzeKotlin(source, opts)
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
