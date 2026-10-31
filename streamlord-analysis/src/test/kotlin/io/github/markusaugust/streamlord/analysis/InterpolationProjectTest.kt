package io.github.markusaugust.streamlord.analysis

import io.github.markusaugust.streamlord.core.json.JsonArray
import io.github.markusaugust.streamlord.core.json.JsonParser
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The files of the interpolation test project, and what the analysis must say about each
 * hole in them; `interpolationProject.test.ts` reads the same expected.json.
 */
class InterpolationProjectTest {
    private val analyzer = Analyzer()
    private val dir = File("../editors/vscode/test/fixtures/interpolationtests")
    private val codes =
        setOf("kotlin-interpolation", "unescaped-interpolation", "unsafe-interpolation", "interpolate-template", "interpolate-holes")

    private fun line(
        start: Int,
        end: Int,
        code: String?,
        severity: String,
        message: String,
    ) = "$code $start-$end $severity\n  message: $message"

    @Test
    fun `finds the constants the project gives the analysis`() {
        val expected = JsonParser.parseObject(dir.resolve("expected.json").readText())
        val sources = expected.obj("files")!!.keys.map { dir.resolve(it).readText() }
        assertEquals(expected.array("constants")!!.strings().toSet(), projectConstants(sources))
    }

    @Test
    fun `judges every hole of the project as expected json says`() {
        val expected = JsonParser.parseObject(dir.resolve("expected.json").readText())
        val opts = AnalyzeOptions("data-", true, constants = expected.array("constants")!!.strings().toSet())
        val files = expected.obj("files")!!
        assertTrue(files.isNotEmpty())
        for ((file, issues) in files) {
            val all = analyzer.analyzeKotlin(dir.resolve(file).readText(), opts)
            val seen =
                all
                    .filter { it.code in codes }
                    .sortedWith(compareBy({ it.start }, { it.end }, { it.code }))
                    .map { line(it.start, it.end, it.code, it.severity.name.lowercase(), it.message) }
            val wanted =
                (issues as JsonArray).objects().map {
                    line(it.int("start")!!, it.int("end")!!, it.string("code"), it.string("severity")!!, it.string("message")!!)
                }
            assertEquals(wanted, seen, file)
            assertEquals(emptyList(), all.filter { it.code == "missing-id" || it.code == "top-level-text" }, file)
        }
    }
}
