package io.github.markusaugust.streamlord.analysis

import io.github.markusaugust.streamlord.core.json.JsonObject
import io.github.markusaugust.streamlord.core.json.JsonParser
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The corpus in the VS Code extension's `test/fixtures/corpus` is read by this suite and by
 * `corpus.test.ts`, so the two editors are held to the same cases. The format is described in
 * each file.
 */
class CorpusTest {
    private val analyzer = Analyzer()
    private val opts = AnalyzeOptions("data-", true)
    private val files = File("../editors/vscode/test/fixtures/corpus").listFiles { f -> f.extension == "json" }!!.sortedBy { it.name }

    private fun run(
        kind: String,
        case: JsonObject,
    ): List<Issue> {
        val source = case.string("source")!!
        return when (kind) {
            "expression" -> {
                analyzer.validateExpression(source)
            }

            "markup" -> {
                analyzer.validateMarkup(source, MarkupOptions(case.boolean("requireIds") ?: false, "data-", true))
            }

            "html" -> {
                analyzer.analyzeHtml(source, opts)
            }

            "kotlin" -> {
                analyzer.analyzeKotlin(source, opts)
            }

            // The signals the source reads that it does not itself declare.
            "references" -> {
                val defined = collectSignalDefinitions(source, SourceLanguage.HTML)
                analyzer.signalReferencesInHtml(source, opts).mapNotNull { unknownSignalIssue(it, defined) }
            }

            else -> {
                error("Unknown corpus kind $kind")
            }
        }
    }

    /**
     * One issue as a line: its code, its range unless the case leaves the range open, its severity
     * and its message where the case gives them, and the source after each fix.
     */
    private fun line(
        code: String?,
        range: Pair<Int, Int>?,
        severity: String?,
        message: String?,
        fixes: List<String>,
    ) = listOfNotNull(code, range?.let { "${it.first}-${it.second}" }, severity).joinToString(" ") +
        (message?.let { "\n  message: $it" } ?: "") + fixes.joinToString("") { "\n  fix: $it" }

    @Test
    fun `judges every case of the shared corpus as written`() {
        assertTrue(files.isNotEmpty())
        for (file in files) {
            val corpus = JsonParser.parseObject(file.readText())
            for (case in corpus.array("cases")!!.objects()) {
                val source = case.string("source")!!
                val expected = case.array("issues")!!.objects()
                val actual = run(corpus.string("kind")!!, case).sortedWith(compareBy({ it.start }, { it.end }, { it.code }))
                val seen =
                    actual.mapIndexed { n, i ->
                        val e = expected.getOrNull(n)
                        val open = e?.let { it.int("start") == null } ?: false
                        val severity = i.severity.name.lowercase()
                        line(
                            i.code,
                            if (open) null else i.start to i.end,
                            severity.takeIf { e?.string("severity") != null },
                            i.message.takeIf { e?.string("message") != null },
                            i.fixes.map { it.apply(source) },
                        )
                    }
                val wanted =
                    expected.map { e ->
                        line(
                            e.string("code"),
                            e.int("start")?.let { it to e.int("end")!! },
                            e.string("severity"),
                            e.string("message"),
                            e.array("fixes")?.strings() ?: emptyList(),
                        )
                    }
                assertEquals(wanted, seen, "${file.name}: ${case.string("name")}")
            }
        }
    }
}
