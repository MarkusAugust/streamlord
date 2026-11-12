package io.github.markusaugust.streamlord.probes

import io.github.markusaugust.streamlord.analysis.AnalyzeOptions
import io.github.markusaugust.streamlord.analysis.Analyzer
import io.github.markusaugust.streamlord.analysis.Fix
import io.github.markusaugust.streamlord.analysis.ValueFact
import io.github.markusaugust.streamlord.analysis.rewriteAt
import io.github.markusaugust.streamlord.core.json.JsonParser
import java.io.File

private const val DOMAIN = "io.github.markusaugust.streamlord.core.domain"
private const val PACKAGE = "io.github.markusaugust.streamlord.probes"

/** The issues whose fixes keep what the string writes; a fix for the dollar trap changes it on purpose. */
private val JUDGED = setOf("unescaped-interpolation", "unsafe-interpolation")

/** One function to probe: `fun f(params) = body`, with what the file imports and declares beside it. */
private class Case(
    val name: String,
    val imports: List<String>,
    val declares: String,
    val params: String,
    val body: String,
    /** The fact the editor gives of a value written as one of [written], as the matrix has it. */
    val fact: ValueFact?,
    val written: Set<String>,
    val expects: String?,
    val says: List<String>,
    val marks: List<String>,
)

private fun literal(text: String): String =
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

private fun probes(file: File): List<Case> =
    JsonParser.parseObject(file.readText()).array("cases")!!.objects().map { c ->
        Case(
            c.string("name")!!,
            listOf("$DOMAIN.Html", "$DOMAIN.Trusted", "$DOMAIN.interpolate"),
            c.string("declares").orEmpty(),
            c.string("params").orEmpty(),
            c.string("body")!!,
            null,
            emptySet(),
            c.string("rewrite"),
            c.array("says")?.strings().orEmpty(),
            c.array("marks")?.strings().orEmpty(),
        )
    }

/** Every cell of the matrix, built as HoleMatrixTest builds it. */
private fun matrix(file: File): List<Case> {
    val m = JsonParser.parseObject(file.readText())
    val out = ArrayList<Case>()
    for (place in m.array("places")!!.objects()) {
        for (value in m.array("values")!!.objects()) {
            for (form in m.array("forms")!!.objects()) {
                val markup = place.string("markup")!!.let { if (form.boolean("escaped") == true) it.replace("\"", "\\\"") else it }
                out +=
                    Case(
                        "${place.string("name")}, ${value.string("name")}, ${form.string("name")}",
                        listOf("$DOMAIN.Trusted", "$DOMAIN.interpolate") + value.array("imports")?.strings().orEmpty(),
                        value.string("declares").orEmpty(),
                        value.string("type")?.let { "v: $it" }.orEmpty(),
                        form.string("open") + markup.replace("§", form.string("hole")!!) + form.string("close"),
                        (value.string("kotlinFact") ?: value.string("fact"))?.let { ValueFact.valueOf(it.uppercase().replace('-', '_')) },
                        setOf("\${v}", "\$\${v}", "v"),
                        null,
                        emptyList(),
                        emptyList(),
                    )
            }
        }
    }
    return out
}

private val analyzer = Analyzer()

/** The object of one case: the function as written, and as each fix and each context action leaves it. */
private fun render(
    n: Int,
    case: Case,
): String {
    val header = case.imports.joinToString("") { "import $it\n" } + "\n"
    val declares = if (case.declares.isEmpty()) "" else case.declares.trimEnd() + "\n"
    val source = header + declares + "fun f(${case.params}) = ${case.body}\n"
    val opts = AnalyzeOptions(valueFact = { start, end -> case.fact.takeIf { source.substring(start, end).trim() in case.written } })
    val sites = analyzer.analyzeKotlinSites(source, opts)
    val issues = sites.flatMap { it.issues }.sortedBy { it.start }
    val variants = ArrayList<Triple<String, Fix, Boolean>>()
    for (issue in issues.filter { it.code in JUDGED }) for (fix in issue.fixes) variants += Triple(fix.title, fix, false)
    val seen = HashSet<String>()
    for (at in header.length until source.length) {
        val fix = rewriteAt(sites, at) ?: continue
        if (seen.add(fix.apply(source))) variants += Triple(fix.title, fix, true)
    }
    val types = if (case.params.isBlank()) emptyList() else case.params.split(",").map { it.substringAfter(":").trim() }
    val call = types.indices.joinToString(", ") { "a[$it] as ${types[it]}" }
    val out = StringBuilder("/** ${case.name.replace("*/", "* /")} */\nobject P$n {\n")
    out.append("    object Before {\n${source.substring(header.length).prependIndent("        ")}\n    }\n")
    val made = ArrayList<String>()
    variants.forEachIndexed { k, (title, fix, context) ->
        val fixed = fix.apply(source)
        // A probe has its imports, so a fix that edits above the function wrote something it should not.
        val body =
            if (fixed.startsWith(
                    header,
                )
            ) {
                fixed.substring(header.length)
            } else {
                "fun f(${case.params}): String = error(\"the fix changes the imports\")"
            }
        out.append("    object V$k {\n${body.prependIndent("        ")}\n    }\n")
        made += "Variant(${literal(title)}, ${fix.preferred}, $context, ${literal(fixed.substring(header.length))}) { a -> V$k.f($call) }"
    }
    out.append("    val probe = Probe(\n        ${literal(case.name)},\n        ${literal(source.substring(header.length))},\n")
    out.append("        listOf(${types.joinToString(", ") { literal(it) }}),\n")
    out.append("        ${case.expects?.let(::literal)},\n        listOf(${case.says.joinToString(", ") { literal(it) }}),\n")
    out.append("        listOf(${case.marks.joinToString(", ") { literal(it) }}),\n")
    out.append(
        "        listOf(${issues.joinToString(
            ", ",
        ) { "Said(${literal(source.substring(it.start, it.end))}, ${literal(it.message)})" }}),\n",
    )
    out.append(
        "        { a -> Before.f($call) },\n        listOf(\n${made.joinToString("") { "            $it,\n" }}        ),\n    )\n}\n",
    )
    return out.toString()
}

fun main(args: Array<String>) {
    val cases = File(args[0])
    val out = File(args[1], PACKAGE.replace('.', '/'))
    out.deleteRecursively()
    out.mkdirs()
    val all = probes(File(cases, "rewrite-probes.json")) + matrix(File(cases, "hole-matrix.json"))
    // One file for the cases that import alike, since an import is the file's.
    val files = all.withIndex().groupBy { it.value.imports }
    files.entries.forEachIndexed { k, (imports, group) ->
        val text =
            "@file:Suppress(\"UNUSED_PARAMETER\", \"UNCHECKED_CAST\", \"USELESS_CAST\", \"unused\", \"ktlint\")\n\npackage $PACKAGE\n\n" +
                imports.joinToString("") { "import $it\n" } + "\n" + group.joinToString("\n") { render(it.index, it.value) }
        File(out, "Probes$k.kt").writeText(text)
    }
    File(out, "All.kt").writeText(
        "package $PACKAGE\n\nval PROBES: List<Probe> = listOf(\n" + all.indices.joinToString("") { "    P$it.probe,\n" } + ")\n",
    )
}
