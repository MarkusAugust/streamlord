package io.github.markusaugust.streamlord.analysis

import io.github.markusaugust.streamlord.core.client.clientRewrite
import kotlin.test.Test
import kotlin.test.assertEquals

/** The text, runs and rewrites the client's rewrite gives, against `cases/client-rewrites.json`. */
class ClientRewriteCasesTest {
    @Test
    fun `the client's rewrite, case by case`() {
        val cases = sharedCases("client-rewrites.json")
        assertEquals(24, cases.size)
        for (case in cases) {
            val name = case.string("name")
            val read = clientRewrite(case.string("source")!!)
            assertEquals(case.string("js"), read.js, name)
            assertEquals(
                case["runs"]!!.toKotlin(),
                read.runs.map { listOf(it.range.first.toLong(), it.range.last + 1L, it.quote.toLong()) },
                name,
            )
            assertEquals(
                case["rewrites"]!!.toKotlin(),
                read.rewrites.map { listOf(if (it.signal) "signal" else "action", it.start.toLong(), it.end.toLong(), it.name) },
                name,
            )
        }
    }
}
