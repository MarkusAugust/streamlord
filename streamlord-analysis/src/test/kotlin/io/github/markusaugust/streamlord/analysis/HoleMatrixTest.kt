package io.github.markusaugust.streamlord.analysis

import io.github.markusaugust.streamlord.core.domain.Trusted
import io.github.markusaugust.streamlord.core.domain.UnsafeInterpolationException
import io.github.markusaugust.streamlord.core.domain.interpolate
import io.github.markusaugust.streamlord.core.json.JsonObject
import io.github.markusaugust.streamlord.core.json.JsonParser
import io.github.markusaugust.streamlord.fixtures.fixtureText
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Every place a Kotlin value can land, every kind of value and every way to write the literal,
 * against `cases/hole-matrix.json`. The suite reaches core, so it also holds the table to what
 * `interpolate` does with each value at run time, and runs the call each rewrite leaves.
 */
class HoleMatrixTest {
    private val analyzer = Analyzer()
    private val matrix = JsonParser.parseObject(fixtureText("editors/vscode/test/fixtures/cases/hole-matrix.json"))
    private val places = matrix.array("places")!!.objects()
    private val values = matrix.array("values")!!.objects()
    private val forms = matrix.array("forms")!!.objects()

    /** One source of the matrix: [value] written at [place] in a literal of [form]. */
    private inner class Cell(
        val place: JsonObject,
        val value: JsonObject,
        val form: JsonObject,
    ) {
        val name = "${place.string("name")}, ${value.string("name")}, ${form.string("name")}"
        val shown = form.string("shown")!!
        val source: String

        /** What the table says of the cell: null where nothing is said. */
        val verdict = matrix.obj("cells")!!.obj(place.string("name")!!)!!.obj(value.string("name")!!)

        /** The fixes the table gives the cell, each with whether it is preferred where a rule says; null where no rule says what is offered. */
        val fixes: List<Pair<String, Boolean?>>?

        init {
            val imports = listOf("$DOMAIN.Trusted", "$DOMAIN.interpolate") + value.array("imports")?.strings().orEmpty()
            val markup = place.string("markup")!!.let { if (form.boolean("escaped") == true) it.replace("\"", "\\\"") else it }
            val literal = form.string("open") + markup.replace("§", form.string("hole")!!) + form.string("close")
            val parameter = value.string("type")?.let { "v: $it" }.orEmpty()
            source =
                imports.joinToString("") { "import $it\n" } + "\n" + value.string("declares").orEmpty() + "fun f($parameter) = $literal"
            val own = verdict?.obj("forms")?.obj(form.string("name")!!) ?: verdict
            fixes = own?.array("fixes")?.objects()?.map { it.string("title")!!.replace("{shown}", shown) to it.boolean("preferred") }
        }

        /** The issues of [text], the cell's source or what a fix left of it, with the editor's fact given for the value wherever it stands. */
        fun analyse(text: String): List<Issue> {
            val fact = (value.string("kotlinFact") ?: value.string("fact"))?.let { ValueFact.valueOf(it.uppercase().replace('-', '_')) }
            val opts = AnalyzeOptions(valueFact = { start, end -> fact.takeIf { text.substring(start, end).trim() in WRITTEN } })
            return analyzer.analyzeKotlin(text, opts)
        }
    }

    private fun cells(): List<Cell> = places.flatMap { p -> values.flatMap { v -> forms.map { f -> Cell(p, v, f) } } }

    /** The values a parameter of the kind [sample] names can hold at run time; [url] where the place is a URL. */
    private fun samples(
        sample: String,
        url: Boolean,
    ): List<Any?> {
        val hostile = if (url) "/p?q=<i a=\"b\">'" else "<i a=\"b\">'&"
        return when (sample) {
            "string" -> listOf(hostile)
            "nullable-string" -> listOf(hostile, null)
            "number" -> listOf(3)
            "boolean" -> listOf(true)
            "nullable" -> listOf(3, null)
            "html" -> listOf(interpolate("<b>%s</b>", "x"))
            "trusted" -> listOf(Trusted("<b title=\"x\">y</b>"))
            "object" -> listOf(Widget(hostile))
            else -> error("Unknown sample $sample")
        }
    }

    private class Widget(
        val text: String,
    ) {
        override fun toString(): String = text
    }

    private fun samples(cell: Cell): List<Any?> =
        cell.value
            .string("sample")
            ?.let { samples(it, "href=" in cell.place.string("markup")!! || "src=" in cell.place.string("markup")!!) }
            .orEmpty()

    /** What interpolate does with [sample] in [format]: null where it refuses it, or whether it had to escape it. */
    private fun escaped(
        format: String,
        sample: Any?,
    ): Boolean? =
        try {
            interpolate(format, sample).text != format.replace("%s", (if (sample is Trusted) sample.html else sample?.toString()).orEmpty())
        } catch (_: UnsafeInterpolationException) {
            null
        }

    private fun failed(failures: List<String>) =
        assertEquals(
            emptyList(),
            failures,
            "${failures.size} cells fail:\n" + failures.joinToString("\n"),
        )

    @Test
    fun `every cell is judged as the table says`() {
        val failures = ArrayList<String>()
        for (cell in cells()) {
            val issues = cell.analyse(cell.source)
            val expected = cell.verdict
            if (expected == null) {
                if (issues.isNotEmpty()) failures += "${cell.name}: expected nothing, got ${issues.map { it.code }}"
                continue
            }
            val issue = issues.singleOrNull()
            if (issue == null) {
                failures += "${cell.name}: expected ${expected.string("code")}, got ${issues.map { it.code }}"
                continue
            }
            val wrong = ArrayList<String>()
            val judged = "${issue.code} ${issue.severity.name.lowercase()}"
            if (judged != "${expected.string("code")} ${expected.string("severity")}") wrong += "is $judged"
            for (part in listOf(cell.shown, cell.place.string("lands")!!)) if (part !in issue.message) wrong += "does not name $part"
            val offered = issue.fixes.map { it.title to it.preferred }
            val wanted = cell.fixes
            if (wanted != null &&
                (
                    wanted.map { it.first } != offered.map { it.first } ||
                        wanted.zip(offered).any { (w, o) -> w.second != null && w.second != o.second }
                )
            ) {
                wrong += "offers ${offered.map { it.first + if (it.second) "" else " (not preferred)" }}, " +
                    "expected ${wanted.map { it.first + if (it.second == false) " (not preferred)" else "" }}"
            }
            if (wrong.isNotEmpty()) failures += "${cell.name}: ${wrong.joinToString("; ")}"
        }
        failed(failures)
    }

    @Test
    fun `the table offers the rewrite where interpolate escapes and keeps the value, and nowhere it refuses it`() {
        val failures = ArrayList<String>()
        for (cell in cells().filter { it.form == forms.first() }) {
            val format = cell.place.string("markup")!!.replace("§", "%s")
            val run = samples(cell).map { escaped(format, it) }
            if (run.isEmpty()) continue
            val rewrite = cell.fixes?.any { it.first.startsWith(REWRITE) }
            if (null in run && rewrite == true) failures += "${cell.name}: interpolate refuses the value, and the table offers the rewrite"
            if (null !in run && true in run &&
                rewrite == false
            ) {
                failures += "${cell.name}: interpolate escapes and keeps the value, and the table offers no rewrite"
            }
        }
        failed(failures)
    }

    @Test
    fun `every fix offered leaves its issue gone and a call interpolate takes`() {
        val failures = ArrayList<String>()
        for (cell in cells()) {
            for (issue in cell.analyse(cell.source)) {
                for (fix in issue.fixes) {
                    val fixed = fix.apply(cell.source)
                    val after = cell.analyse(fixed)
                    val left = after.filter { it.code == issue.code || it.severity == Severity.ERROR }
                    if (left.isNotEmpty()) {
                        failures +=
                            "${cell.name}, ${fix.title}: leaves ${left.map { "${it.code} ${it.severity.name.lowercase()}" }} in $fixed"
                    }
                    if ("= interpolate(" !in fixed) continue
                    for (sample in samples(cell)) {
                        val out =
                            try {
                                call(fixed, sample)
                            } catch (e: UnsafeInterpolationException) {
                                failures +=
                                    "${cell.name}, ${fix.title}: the call throws for ${sample?.let { it::class.simpleName }}: ${e.message}"
                                continue
                            }
                        val raw = if (sample is Trusted) sample.html else sample?.toString()
                        if (fix.title == ESCAPES_TITLE && raw != null && '<' in raw && raw in out) {
                            failures += "${cell.name}, ${fix.title}: the call writes ${sample!!::class.simpleName} as it is: $out"
                        }
                    }
                }
            }
        }
        failed(failures)
    }

    /**
     * What the `interpolate` call a rewrite left in [fixed] gives with [sample] for `v`: the format
     * as the compiler reads the literal, trimmed where a trim call follows it, and the value as
     * the call passes it, bare or as `Trusted(v)`.
     */
    private fun call(
        fixed: String,
        sample: Any?,
    ): String {
        val at = fixed.indexOf("= interpolate(")
        val literal = lexKotlin(fixed).strings.first { it.start > at }
        val rest = fixed.substring(literal.end)
        val format = if (rest.startsWith(".trimIndent()")) literal.text.trimIndent() else literal.text
        val value =
            when (val argument = ARGUMENT.find(rest)?.groupValues?.get(1)) {
                "v" -> sample
                "Trusted(v)" -> Trusted(sample.toString())
                else -> error("Unread argument $argument in $fixed")
            }
        return interpolate(format, value).text
    }

    private companion object {
        const val DOMAIN = "io.github.markusaugust.streamlord.core.domain"
        const val REWRITE = "Build it with interpolate(...), which"

        /** The value as each form writes it, and as a rewrite passes it. */
        val WRITTEN = setOf("\${v}", "\$\${v}", "v")
        val ARGUMENT = Regex("""^(?:\.trimIndent\(\)| \+ "")?,\s*(.+)\)\.text\s*$""")
    }
}
