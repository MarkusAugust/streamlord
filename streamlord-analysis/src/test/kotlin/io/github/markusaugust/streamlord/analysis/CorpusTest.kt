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
    private val files = File("../editors/vscode/test/fixtures/corpus").listFiles { f -> f.extension == "json" }!!.sortedBy { it.name }

    private fun run(
        kind: String,
        case: JsonObject,
    ): List<Issue> {
        val source = case.string("source")!!
        // A case may give the attribute prefix of an aliased bundle; the default bundle's otherwise.
        val prefix = case.string("prefix") ?: "data-"
        // A case may give the workspace's `const val` names, which modules the project has, and the calls that are not Streamlord's.
        val foreign = case.array("foreign")?.strings()?.toSet()
        val opts =
            AnalyzeOptions(
                prefix,
                true,
                constants = case.array("constants")?.strings()?.toSet() ?: emptySet(),
                htmlDsl = case.boolean("htmlDsl"),
                core = case.boolean("core"),
                foreignCalls = foreign?.let { callStarts(source, it).toSet() } ?: emptySet(),
            )
        return when (kind) {
            "expression" -> {
                analyzer.validateExpression(source)
            }

            "markup" -> {
                analyzer.validateMarkup(source, MarkupOptions(case.boolean("requireIds") ?: false, prefix, true))
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

            // The same of a Kotlin source: the signals its DSL calls and HTML strings read that it does not declare.
            "kotlin-references" -> {
                val defined = collectSignalDefinitions(source, SourceLanguage.KOTLIN)
                analyzer.signalReferencesInKotlin(source, opts).mapNotNull { unknownSignalIssue(it, defined) }
            }

            else -> {
                error("Unknown corpus kind $kind")
            }
        }
    }

    /**
     * Where each call of [names] in [text] starts, those in the code of a `${'$'}{...}` template too, read
     * as the hyphen check reads them (collectHyphenSites): offsets of the whole source.
     */
    private fun callStarts(
        text: String,
        names: Set<String>,
        shift: Int = 0,
        depth: Int = 0,
    ): List<Int> {
        val lex = lexKotlin(text)
        val out = findCallSites(text, names, lex).mapTo(ArrayList()) { it.nameStart + shift }
        if (depth >= 4) return out
        for (s in lex.strings) {
            for (t in s.interpolations) {
                if (t.kind != InterpolationKind.BRACED) continue
                val open = text.indexOf('{', t.start)
                if (open < 0 || open + 1 > t.end - 1 || text.substring(open + 1, t.end - 1) != t.text) continue
                out += callStarts(t.text, names, shift + open + 1, depth + 1)
            }
        }
        return out
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
