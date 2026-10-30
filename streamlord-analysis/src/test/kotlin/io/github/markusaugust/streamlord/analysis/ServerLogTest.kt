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
                val read = if (c.string("call") == "startedAt") ServerLog.startedAt(line) else ServerLog.isProblem(line)
                assertEquals(c["expected"]!!.toKotlin(), read, name)
            }
        }
}
