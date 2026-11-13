package io.github.markusaugust.streamlord.analysis

import io.github.markusaugust.streamlord.core.json.JsonObject
import io.github.markusaugust.streamlord.core.json.JsonParser
import io.github.markusaugust.streamlord.fixtures.fixtureNames
import io.github.markusaugust.streamlord.fixtures.fixtureText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The corpus in the VS Code extension's `test/fixtures/corpus` is read by this suite and by the
 * extension's `port.test.ts`, so the two editors are held to the same cases. The format is
 * described in each file.
 */
class CorpusTest {
    private val analyzer = Analyzer()
    private val files = fixtureNames("editors/vscode/test/fixtures/corpus").filter { it.endsWith(".json") }

    private fun run(
        kind: String,
        case: JsonObject,
    ): List<Issue> {
        val source = case.string("source")!!
        // A case may give the attribute prefix of an aliased bundle; the default bundle's otherwise.
        val prefix = case.string("prefix") ?: "data-"
        // A case may give the workspace's `const val` names, which modules the project has, and the calls that are not Streamlord's.
        val foreign = case.array("foreign")?.strings()?.toSet()
        val starts = foreign?.let { callStarts(source, it).toSet() } ?: emptySet()

        fun read(language: SourceLanguage) =
            Source(
                source,
                language,
                prefix,
                core = case.boolean("core"),
                htmlDsl = case.boolean("htmlDsl"),
                constants = case.array("constants")?.strings()?.toSet() ?: emptySet(),
                foreign = { _, at -> at in starts },
            )
        return when (kind) {
            "expression" -> {
                analyzer.validateExpression(source)
            }

            "markup" -> {
                analyzer.validateMarkup(source, MarkupOptions(case.boolean("requireIds") ?: false, prefix, true))
            }

            "html" -> {
                analyzer.analyzeHtml(read(SourceLanguage.HTML))
            }

            "kotlin" -> {
                analyzer.analyzeKotlin(read(SourceLanguage.KOTLIN))
            }

            // The signals the source reads that it does not itself declare.
            "references" -> {
                val defined = collectSignalDefinitions(Source(source, SourceLanguage.HTML))
                analyzer.signalReferences(read(SourceLanguage.HTML)).mapNotNull { unknownSignalIssue(it, defined) }
            }

            // The same of a Kotlin source: the signals its DSL calls and HTML strings read that it does not declare.
            "kotlin-references" -> {
                val defined = collectSignalDefinitions(Source(source, SourceLanguage.KOTLIN))
                analyzer.signalReferences(read(SourceLanguage.KOTLIN)).mapNotNull { unknownSignalIssue(it, defined) }
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
    fun `a message that sends the author to interpolate offers the rewrite or says why it does not`() {
        val failures = ArrayList<String>()
        for (file in files) {
            val corpus = JsonParser.parseObject(fixtureText("editors/vscode/test/fixtures/corpus/$file"))
            if (corpus.string("kind") != "kotlin") continue
            for (case in corpus.array("cases")!!.objects()) {
                for (issue in run("kotlin", case)) {
                    val advises = "build the string with interpolate(...)" in issue.message
                    val offered = issue.fixes.any { it.title.startsWith(REWRITE_TITLE) }
                    val why =
                        "interpolate(...) is not offered here" in issue.message ||
                            "interpolate(...) cannot take this string" in issue.message
                    if (advises && !offered && !why) failures += "$file: ${case.string("name")}: ${issue.message}"
                }
            }
        }
        assertEquals(emptyList(), failures, failures.joinToString("\n"))
    }

    /** What the analysis reads in place of a Kotlin template is its own, and no message shows it. */
    @Test
    fun `no message of the corpus shows the text read in place of a Kotlin template`() {
        val shown = ArrayList<String>()
        for (file in files) {
            val corpus = JsonParser.parseObject(fixtureText("editors/vscode/test/fixtures/corpus/$file"))
            for (case in corpus.array("cases")!!.objects()) {
                val issues = run(corpus.string("kind")!!, case)
                shown +=
                    issues
                        .filter {
                            PLACEHOLDER in it.message ||
                                it.fixes.any { f ->
                                    PLACEHOLDER in f.title
                                }
                        }.map { "$file: ${case.string("name")}: ${it.message}" }
            }
        }
        assertEquals(emptyList(), shown)
    }

    @Test
    fun `judges every case of the shared corpus as written`() {
        assertTrue(files.isNotEmpty())
        for (file in files) {
            val corpus = JsonParser.parseObject(fixtureText("editors/vscode/test/fixtures/corpus/$file"))
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
                assertEquals(wanted, seen, "$file: ${case.string("name")}")
            }
        }
    }
}
