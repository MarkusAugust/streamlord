package io.github.markusaugust.streamlord.analysis

import io.github.markusaugust.streamlord.core.json.JsonParser
import io.github.markusaugust.streamlord.fixtures.fixtureText
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Each value of `cases/nullable-values.json` asked of [mayBeNull], the one place a judge of a placeholder asks whether its value
 * may be null: it may be where the Kotlin compiler types it so, and rewrite-probes holds the table to the compiler. A row marked
 * `cautious` never gives null but its text cannot show it, so it is read as one that may; a row marked `missed` may be null and is
 * read as one that never is, a gap of #349. A mark on a row read right fails, so that no mark is left behind.
 */
class NullableValuesTest {
    private val table = JsonParser.parseObject(fixtureText("editors/vscode/test/fixtures/cases/nullable-values.json"))

    @Test
    fun `a value may be null where the compiler types it so`() {
        val params = table.array("params")!!.objects().joinToString(", ") { "${it.string("name")}: ${it.string("type")}" }
        val wrong =
            table.array("values")!!.objects().mapNotNull { row ->
                val value = row.string("value")!!
                val src = "fun f($params) = \"${'$'}{$value}\""
                val read = mayBeNull(src, value, src.indexOf('"'))
                val nullable = row.boolean("nullable")!!
                val cautious = row.boolean("cautious") == true
                val missed = row.boolean("missed") == true
                when {
                    cautious && nullable -> "$value: marked cautious, though it may be null"
                    missed && !nullable -> "$value: marked missed, though it is never null"
                    read != (nullable != (cautious || missed)) -> "$value: $read"
                    else -> null
                }
            }
        assertEquals(emptyList(), wrong, "the analysis reads these otherwise than the compiler")
    }
}
