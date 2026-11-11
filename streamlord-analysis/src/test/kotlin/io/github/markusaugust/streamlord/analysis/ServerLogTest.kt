package io.github.markusaugust.streamlord.analysis

import kotlin.test.Test
import kotlin.test.assertEquals

/** The cases of `twins/server-log.json`, which the VS Code extension's `serverLog.test.ts` reads too. */
class ServerLogTest {
    @Test
    fun `server log`() =
        eachCase(twinCases("server-log.json"), { it.string("name")!! }) { c ->
            val name = c.string("name")!!
            val line = c.string("line")!!
            val read =
                when (val call = c.string("call")) {
                    "startedAt" -> ServerLog.startedAt(line)
                    "isProblem" -> ServerLog.isProblem(line)
                    else -> error("Unknown call $call")
                }
            assertEquals(c["expected"]!!.toKotlin(), read, name)
        }
}
