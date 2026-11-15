package io.github.markusaugust.streamlord.analysis

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The cases of `cases/value-types.json`. */
class ValueTypesTest {
    private val cases = sharedCases("value-types.json")

    @Test
    fun `every case asks one of the three`() {
        for (c in cases) {
            val asked = listOf("type" to "fact", "collection" to "markup", "content" to "tag").filter { (q, _) -> q in c }
            assertEquals(1, asked.size, c.string("name"))
            assertTrue(asked.single().second in c, "${c.string("name")} gives no ${asked.single().second}")
        }
    }

    @Test
    fun `the fact of a declared type`() {
        for (c in cases.filter { it.string("type") != null }) {
            val fact = c.string("fact")?.let { ValueFact.valueOf(it.uppercase().replace('-', '_')) }
            assertEquals(fact, ValueFact.ofTypeName(c.string("type")!!), c.string("name"))
        }
    }

    // A collection of markup joined in element text is markup, and says nothing; any other is text written as it is.
    @Test
    fun `whether a type is a collection of markup`() {
        val analyzer = Analyzer()
        for (c in cases.filter { it.string("collection") != null }) {
            val src =
                "import io.github.markusaugust.streamlord.core.domain.Html\nimport io.github.markusaugust.streamlord.core.domain.Trusted\n" +
                    "import io.github.markusaugust.streamlord.core.domain.interpolate\n" +
                    "fun f(xs: ${c.string("collection")}) = \"<p>\${xs.joinToString(\"\")}</p>\""
            assertEquals(c.boolean("markup"), analyzer.analyzeKotlin(src).isEmpty(), c.string("name"))
        }
    }

    @Test
    fun `whether a literal opens with a tag`() {
        for (c in cases.filter { it.string("content") != null }) {
            val trimMargin = c.boolean("trimMargin") ?: false
            assertEquals(c.boolean("tag"), opensWithTag(c.string("content")!!, trimMargin), c.string("name"))
        }
    }
}
