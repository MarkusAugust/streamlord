package io.github.markusaugust.streamlord.analysis

import io.github.markusaugust.streamlord.core.json.JsonParser
import io.github.markusaugust.streamlord.fixtures.fixtureNames
import io.github.markusaugust.streamlord.fixtures.fixtureText
import io.github.markusaugust.streamlord.fixtures.recordFixture
import kotlin.test.Test
import kotlin.test.fail

/**
 * Everything the analysis says of the shared fixtures, recorded in `test/fixtures/verdicts.txt` and
 * compared with it on the JVM and on Node: each issue's code, range, severity, message and fixes, in
 * the order the analysis gives them, and what completion, hover and signature help answer. The other
 * suites hold a case to what its author wrote down; this one holds every case to all it was given,
 * and reads each judged value half typed and in the three ways markup is written, so a change to how
 * a value is read shows each verdict it moves.
 *
 * A verdict that was meant to move is recorded anew on the JVM, and the diff of the file is read:
 * `./gradlew :streamlord-analysis:test --tests '*VerdictDigestTest' -PrecordVerdicts=true`.
 */
class VerdictDigestTest {
    private val analyzer = Analyzer()
    private val blocks = ArrayList<Pair<String, List<String>>>()
    private val seen = HashMap<String, Int>()

    /** Records [lines] under [name], numbered where the fixtures hold the name twice. */
    private fun add(
        name: String,
        lines: List<String>,
    ) {
        val nth = (seen[name] ?: 0) + 1
        seen[name] = nth
        blocks += (escaped(name) + if (nth > 1) " ($nth)" else "") to lines
    }

    private fun issues(found: List<Issue>): List<String> =
        found.flatMap { i ->
            listOf("${i.code} ${i.start}-${i.end} ${i.severity.name.lowercase()}: ${escaped(i.message)}") +
                i.fixes.flatMap { f ->
                    listOf("  fix ${quoted(f.title)} ${f.start}-${f.end} ${quoted(f.text)}" + if (f.preferred) "" else " (not preferred)") +
                        f.edits.map { "    edit ${it.start}-${it.end} ${quoted(it.text)}" }
                }
        }

    private fun completions(
        found: Completions,
        caret: Int,
    ): List<String> =
        listOf(if (found.alone) "completions, alone" else "completions") +
            found.items.map { item ->
                listOfNotNull(
                    "  ${quoted(item.label)} ${item.kind.name.lowercase()} ${item.replaceFrom - caret}..${item.replaceTo - caret}",
                    item.insert?.let { "insert ${quoted(it)}" },
                    item.snippet?.let { s ->
                        "snippet ${quoted(s.text)} ${s.places.map { "${it.start}-${it.end} ${it.choices.map(::quoted)}" }}"
                    },
                    item.detail?.let { "detail ${quoted(it)}" },
                    item.tail?.let { "tail ${quoted(it)}" },
                    item.filter?.let { "filter ${quoted(it)}" },
                    item.doc?.let { "doc ${hash(it)}" },
                ).joinToString(" ")
            }

    private fun hover(found: Hover?): List<String> =
        listOf(if (found == null) "no hover" else "hover ${found.start}-${found.end} ${hash(found.markdown)}")

    private fun signature(found: SignatureHelp?): List<String> =
        listOf(
            if (found == null) {
                "no signature"
            } else {
                "signature ${quoted(found.label)} at ${found.active} of ${found.parameters.map { "${it.first}-${it.last}" }} " +
                    hash(found.doc + found.parameterDocs)
            },
        )

    private fun judgedValues() {
        val workspace = Workspace(listOf("foo", "bar"), emptyList(), emptyList(), "workspace")
        val typedPages = HashSet<String>()
        for (case in sharedCases("judged-values.json")) {
            val name = "judged-values.json: ${case.string("name")}"
            val attribute = case.string("attribute")
            val call = case.string("kotlin")
            val value = case.string("value")
            if (call != null) {
                add(name, issues(analyzer.analyzeKotlin(judgedCall(call))))
            } else if (value == null) {
                val (source, caret) = judgedPage(attribute!!, case.string("caret")!!)
                add(
                    name,
                    completions(analyzer.complete(source, caret, workspace), caret) + signature(analyzer.signatureHelp(source, caret)),
                )
            } else {
                // The value as far as it is typed, to its end, in an HTML file; what two values start with is read once.
                for (typed in 0..value.length) {
                    if (typed < value.length && value[typed].isLowSurrogate()) continue
                    val page = judgedPage(attribute!!, value.substring(0, typed)).first
                    if (typedPages.add(page.text)) add("html: ${page.text}", issues(analyzer.analyzeHtml(page)))
                }
                // The whole value the two other ways markup is written: in a Kotlin string of HTML and through the DSL.
                val page = judgedPage(attribute!!, value).first.text
                add("string: $page", issues(analyzer.analyzeKotlin("fun f() = ${kotlinLiteral(page)}")))
                add("dsl: $page", issues(analyzer.analyzeKotlin(judgedCall("attributes[\"$attribute\"] = ${kotlinLiteral(value)}"))))
            }
        }
    }

    private fun corpus() {
        for (file in fixtureNames("$FIXTURES/corpus").filter { it.endsWith(".json") }) {
            val corpus = JsonParser.parseObject(fixtureText("$FIXTURES/corpus/$file"))
            for (case in corpus.array("cases")!!.objects()) {
                add("corpus/$file: ${case.string("name")}", issues(corpusIssues(analyzer, corpus.string("kind")!!, case)))
            }
        }
    }

    private fun cases() {
        for (cell in ValueMatrix(analyzer).cells()) add("value-matrix.json: ${cell.name}", issues(cell.analyse(cell.source)))
        for (case in sharedCases("fixes.json")) {
            val source = case.string("source")!!
            val found =
                when (case.string("language")) {
                    "expression" -> analyzer.validateExpression(source)
                    "html" -> analyzer.analyzeHtml(source)
                    else -> analyzer.analyzeKotlin(source)
                }
            add("fixes.json: ${case.string("name")}", issues(found))
        }
        for (case in sharedCases("completions.json")) {
            val (source, caret) = helpQuestion(case)
            val found =
                analyzer.complete(
                    source,
                    caret,
                    helpWorkspace(case),
                    attributeNames = case.boolean("names") ?: true,
                    domEvents = case.boolean("domEvents") ?: true,
                )
            add("completions.json: ${case.string("name")}", completions(found, caret))
        }
        for (case in sharedCases("hovers.json")) {
            val (source, caret) = helpQuestion(case)
            add("hovers.json: ${case.string("name")}", hover(analyzer.hover(source, caret)))
        }
        for (case in sharedCases("signature-help.json")) {
            val (source, caret) = helpQuestion(case)
            add("signature-help.json: ${case.string("name")}", signature(analyzer.signatureHelp(source, caret)))
        }
    }

    /** The files under [folder], each by its path from it; a name with no dot is a folder. */
    private fun files(folder: String): List<String> =
        fixtureNames("$FIXTURES/$folder").flatMap { if ('.' in it) listOf("$folder/$it") else files("$folder/$it") }

    /** The files the real editors are run over, read as VS Code hands them to the analysis. */
    private fun realFiles() {
        for (file in files("real")) {
            val engine =
                when (file.substringAfterLast('.')) {
                    "kt", "html", "mustache" -> null
                    "jte", "kte" -> TemplateEngine.JTE
                    "ftl" -> TemplateEngine.FREEMARKER
                    "vm" -> TemplateEngine.VELOCITY
                    "cshtml" -> TemplateEngine.RAZOR
                    else -> continue
                }
            val kotlin = file.endsWith(".kt")
            val source =
                Source(
                    fixtureText("$FIXTURES/$file"),
                    if (kotlin) SourceLanguage.KOTLIN else SourceLanguage.HTML,
                    if (file.startsWith("real/aliased/")) "data-star-" else "data-",
                    Modules.FROM_IMPORTS,
                    engine = engine,
                )
            add(file, issues(if (kotlin) analyzer.analyzeKotlin(source) else analyzer.analyzeHtml(source)))
        }
    }

    @Test
    fun `the analysis says of every shared fixture what is recorded`() {
        judgedValues()
        corpus()
        cases()
        realFiles()
        val found = blocks.joinToString("") { (name, lines) -> "== $name\n" + lines.joinToString("") { "$it\n" } }
        if (recordFixture(RECORDED, found)) return
        val recorded = fixtureText(RECORDED)
        if (found == recorded) return

        val was = LinkedHashMap<String, String>()
        var name = ""
        for (line in recorded.lineSequence()) {
            if (line.startsWith("== ")) {
                name = line.substring(3)
                was[name] = ""
            } else if (line.isNotEmpty()) {
                was[name] = was[name] + line + "\n"
            }
        }
        val now = blocks.associate { (n, lines) -> n to lines.joinToString("") { "$it\n" } }
        val moved =
            now.filter { (n, text) -> was[n] != text }.map { (n, text) -> "$n\n was:\n${was[n] ?: "(not recorded)\n"} is:\n$text" } +
                was.keys.filter { it !in now }.map { "$it\n is recorded, and no fixture gives it" }
        fail(
            if (moved.isEmpty()) {
                "The verdicts are the recorded ones, in another order"
            } else {
                "${moved.size} of ${now.size} verdicts are not the recorded ones; the first of them:\n" +
                    moved.take(SHOWN).joinToString("\n")
            },
        )
    }

    private companion object {
        const val FIXTURES = "editors/vscode/test/fixtures"
        const val RECORDED = "$FIXTURES/verdicts.txt"
        const val SHOWN = 20

        /** [text] on one line: a line break, a tab, a backslash and half a surrogate pair written as an escape. */
        fun escaped(text: String): String =
            buildString {
                for (c in text) {
                    when {
                        c == '\\' -> append("\\\\")
                        c == '\n' -> append("\\n")
                        c == '\r' -> append("\\r")
                        c == '\t' -> append("\\t")
                        c < ' ' || c.isSurrogate() -> append("\\u").append(c.code.toString(16).padStart(4, '0'))
                        else -> append(c)
                    }
                }
            }

        fun quoted(text: String): String = "\"" + escaped(text).replace("\"", "\\\"") + "\""

        /** A long text, a documentation, as eight hex digits: enough to say that it changed. */
        fun hash(text: String): String {
            var h = 0x811c9dc5.toInt()
            for (c in text) h = (h xor c.code) * 0x01000193
            return h.toUInt().toString(16).padStart(8, '0')
        }

        /** [text] as a Kotlin string literal that holds it. */
        fun kotlinLiteral(text: String): String =
            buildString {
                append('"')
                for (c in text) {
                    when (c) {
                        '\\' -> append("\\\\")
                        '"' -> append("\\\"")
                        '$' -> append("\\$")
                        '\n' -> append("\\n")
                        '\r' -> append("\\r")
                        '\t' -> append("\\t")
                        else -> append(c)
                    }
                }
                append('"')
            }
    }
}
