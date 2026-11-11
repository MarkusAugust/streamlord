package io.github.markusaugust.streamlord.analysis

import io.github.markusaugust.streamlord.core.client.clientRewrite
import kotlin.test.Test
import kotlin.test.assertEquals

/** The client's rewrite gives the same text, runs and rewrites here as in TypeScript: `clientRewriteTwin.test.ts` reads the same cases. */
class ClientRewriteTwinTest {
    @Test
    fun `the client's rewrite, as both twins give it`() {
        val cases = twinCases("client-rewrites.json")
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
