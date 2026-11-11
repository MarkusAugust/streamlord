package io.github.markusaugust.streamlord.analysis

import io.github.markusaugust.streamlord.core.json.JsonParser
import io.github.markusaugust.streamlord.fixtures.fixtureText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The checks and the caret read markup the one way: every quoted attribute value [tokenize] finds,
 * [valueAtCaret] finds at each offset in it, and every value the caret finds is one [tokenize]
 * found. Over the markup of the shared fixtures, read as a template file and as a Kotlin string,
 * so the two cannot drift apart where the fixtures already reach. oneReading.test.ts holds the same.
 */
class OneReadingTest {
    private val markups: List<Pair<String, String>> =
        twinCases("hole-landings.json").map { it.string("name")!! to it.string("markup")!! } +
            twinCases("completion-contexts.json").map { it.string("name")!! to it.string("before")!! + it.string("after")!! } +
            listOf("markup.json", "html.json").flatMap { file ->
                JsonParser
                    .parseObject(fixtureText("editors/vscode/test/fixtures/corpus/$file"))
                    .array("cases")!!
                    .objects()
                    .map { it.string("name")!! to it.string("source")!! }
            }

    @Test
    fun `the caret finds every quoted value the checks read`() {
        for ((name, markup) in markups) {
            for (templates in listOf(true, false)) {
                for (tag in tokenize(markup, templates).tags) {
                    for (attr in tag.attributes) {
                        if (!attr.quoted || attr.value == null) continue
                        for (offset in attr.valueStart..attr.valueEnd) {
                            val at = valueAtCaret(markup, offset, "data-", templates)
                            assertEquals(
                                attr.name to attr.valueStart,
                                at?.attribute to at?.valueStart,
                                "$name, templates $templates, at $offset",
                            )
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `the checks read every value the caret finds`() {
        for ((name, markup) in markups) {
            for (templates in listOf(true, false)) {
                val values = tokenize(markup, templates).tags.flatMap { it.attributes }.filter { it.quoted && it.value != null }
                for (offset in 0..markup.length) {
                    val at = valueAtCaret(markup, offset, "data-", templates) ?: continue
                    assertTrue(
                        values.any { it.name == at.attribute && it.valueStart == at.valueStart && offset <= it.valueEnd },
                        "$name, templates $templates: the caret at $offset finds ${at.attribute} at ${at.valueStart}",
                    )
                }
            }
        }
    }
}
