package io.github.markusaugust.streamlord.analysis

import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import kotlin.test.assertEquals

/** The cases of `twins/server-log.json`, which the VS Code extension's `serverLog.test.ts` reads too. */
class ServerLogTest {
    @TestFactory
    fun `server log`(): List<DynamicTest> =
        twinCases("server-log.json").map { c ->
            val name = c.string("name")!!
            DynamicTest.dynamicTest(name) {
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
}
