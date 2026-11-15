package io.github.markusaugust.streamlord.analysis

import io.github.markusaugust.streamlord.core.domain.Position
import io.github.markusaugust.streamlord.core.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The cases of `cases/value-verdict.json`, asked of a Kotlin template written at each position, with
 * the editor's fact for it: nothing said is SAFE, `unescaped-interpolation` ESCAPE, an
 * `unsafe-interpolation` that says the digits are safe DIGITS, and any other UNSAFE.
 */
class ValueVerdictTest {
    private val analyzer = Analyzer()
    private val places =
        mapOf(
            Position.TEXT to "<p>§</p>",
            Position.ATTRIBUTE to "<p title=\"§\"></p>",
            Position.URL to "<a href=\"/x§\"></a>",
            Position.STYLE to "<p style=\"width: §px\"></p>",
            Position.DATASTAR to "<p data-text=\"1 + §\"></p>",
            Position.CODE to "<p onclick=\"f(§)\"></p>",
            Position.STRUCTURE to "<p §></p>",
        )

    private fun judged(
        fact: ValueFact?,
        markup: String,
    ): String {
        val src =
            "import io.github.markusaugust.streamlord.core.domain.interpolate\nfun f(v: Any?) = \"" +
                markup.replace("\"", "\\\"").replace("§", "\${v}") + "\""
        val issue =
            analyzer
                .analyzeKotlin(Source(src, SourceLanguage.KOTLIN, valueFact = { s, e -> fact.takeIf { src.substring(s, e).trim() == "\${v}" } }))
                .singleOrNull()
        return when {
            issue == null -> "SAFE"
            issue.code == "unescaped-interpolation" -> "ESCAPE"
            issue.code == "unsafe-interpolation" && "as digits" in issue.message -> "DIGITS"
            else -> "UNSAFE"
        }
    }

    @Test
    fun `the verdict on a fact in each position`() {
        for (c in sharedCases("value-verdict.json")) {
            val fact = c.string("fact")?.let { ValueFact.valueOf(it.uppercase().replace('-', '_')) }
            val verdicts = c["verdicts"] as JsonObject
            assertEquals(Position.entries.map { it.name }.toSet(), verdicts.keys, c.string("name"))
            for ((position, markup) in places) {
                assertEquals(verdicts.string(position.name), judged(fact, markup), "${c.string("name")} in ${position.name}")
            }
        }
    }
}
