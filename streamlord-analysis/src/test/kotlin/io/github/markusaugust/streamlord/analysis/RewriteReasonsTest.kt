package io.github.markusaugust.streamlord.analysis

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A warning about what the client did with a reference gives the reason that holds for that
 * reference. `rewriteReasons.test.ts` reads the same cases.
 */
class RewriteReasonsTest {
    @Test
    fun `the reason a rewrite warning gives`() {
        val failures = ArrayList<String>()
        for (case in twinCases("rewrite-reasons.json")) {
            val name = case.string("name")
            val source = case.string("source")!!
            val code = case.string("code")
            val written = case.string("written")
            val issues =
                ExpressionValidator()
                    .validate(source, returnsValue = true)
                    .filter { it.code == code && source.substring(it.start, it.end) == written }
            assertEquals(1, issues.size, "$name: $code on $written")
            val message = issues.single().message
            for (words in case.array("holds")!!.strings()) {
                if (words !in message) failures += "$name: \"$words\" is missing from: $message"
            }
            for (words in case.array("lacks")!!.strings()) {
                if (words in message) failures += "$name: \"$words\" is in: $message"
            }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }
}
