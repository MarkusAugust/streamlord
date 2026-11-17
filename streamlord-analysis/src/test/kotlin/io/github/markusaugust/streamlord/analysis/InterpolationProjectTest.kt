package io.github.markusaugust.streamlord.analysis

import io.github.markusaugust.streamlord.core.json.JsonArray
import io.github.markusaugust.streamlord.core.json.JsonParser
import io.github.markusaugust.streamlord.fixtures.fixtureText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The files of the interpolation test project, and what the analysis must say about each
 * placeholder in them.
 */
class InterpolationProjectTest {
    private val analyzer = Analyzer()
    private val dir = "editors/vscode/test/fixtures/interpolationtests"
    private val codes =
        setOf(
            IssueCode.KOTLIN_INTERPOLATION,
            IssueCode.UNESCAPED_INTERPOLATION,
            IssueCode.UNSAFE_INTERPOLATION,
            IssueCode.INTERPOLATE_TEMPLATE,
            IssueCode.INTERPOLATE_PLACEHOLDERS,
        )

    private fun line(
        start: Int,
        end: Int,
        code: String?,
        severity: String,
        message: String,
    ) = "$code $start-$end $severity\n  message: $message"

    @Test
    fun `finds the constants the project gives the analysis`() {
        val expected = JsonParser.parseObject(fixtureText("$dir/expected.json"))
        val sources = expected.obj("files")!!.keys.map { fixtureText("$dir/$it") }
        assertEquals(expected.array("constants")!!.strings().toSet(), projectConstants(sources.map(::declaredNames)))
    }

    @Test
    fun `judges every placeholder of the project as expected json says`() {
        val expected = JsonParser.parseObject(fixtureText("$dir/expected.json"))
        val constants = expected.array("constants")!!.strings().toSet()
        val files = expected.obj("files")!!
        assertTrue(files.isNotEmpty())
        for ((file, issues) in files) {
            val all = analyzer.analyzeKotlin(Source(fixtureText("$dir/$file"), SourceLanguage.KOTLIN, constants = constants))
            val seen =
                all
                    .filter { it.code in codes }
                    .sortedWith(compareBy({ it.start }, { it.end }, { it.code.id }))
                    .map { line(it.start, it.end, it.code.id, it.severity.name.lowercase(), it.message) }
            val wanted =
                (issues as JsonArray).objects().map {
                    line(it.int("start")!!, it.int("end")!!, it.string("code"), it.string("severity")!!, it.string("message")!!)
                }
            assertEquals(wanted, seen, file)
            assertEquals(emptyList(), all.filter { it.code == IssueCode.MISSING_ID || it.code == IssueCode.TOP_LEVEL_TEXT }, file)
        }
    }
}
