package io.github.markusaugust.streamlord.analysis

import io.github.markusaugust.streamlord.html.InvalidSignalNameException
import io.github.markusaugust.streamlord.html.dataSignals
import io.github.markusaugust.streamlord.html.not
import io.github.markusaugust.streamlord.html.signal
import kotlinx.html.HTMLTag
import kotlinx.html.div
import kotlinx.html.stream.createHTML
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Holds [secondArgument] to the Kotlin compiler: each `dataSignals("a-b", argument)` is compiled
 * here and rendered, and the overload the compiler picked shows in what happens. The keyed form
 * takes "a-b" as a name and refuses its hyphen; the object form takes it as an expression and
 * renders it. The table is the one the editor's test reads, so both analyses answer to this.
 */
class DataSignalsSecondArgumentTest {
    private val table = twinCases("data-signals-second-argument.json")

    @Suppress("SimplifyBooleanWithConstants", "KotlinConstantConditions", "USELESS_IS_CHECK")
    private fun calls(
        mode: String,
        flag: Boolean,
        expr: String,
        n: Int,
    ): Map<String, HTMLTag.() -> Unit> =
        linkedMapOf(
            "\"1\"" to { dataSignals("a-b", "1") },
            "\"\"\"1\"\"\"" to { dataSignals("a-b", """1""") },
            "\"n${'$'}n\"" to { dataSignals("a-b", "n$n") },
            "\"1\" + expr" to { dataSignals("a-b", "1" + expr) },
            "signal(\"count\")" to { dataSignals("a-b", signal("count")) },
            "not(\"open\")" to { dataSignals("a-b", not("open")) },
            "signal(\"a\") + \" + 1\"" to { dataSignals("a-b", signal("a") + " + 1") },
            "true" to { dataSignals("a-b", true) },
            "false" to { dataSignals("a-b", false) },
            "mode == \"x\"" to { dataSignals("a-b", mode == "x") },
            "\"x\" == mode" to { dataSignals("a-b", "x" == mode) },
            "mode != \"x\"" to { dataSignals("a-b", mode != "x") },
            "!flag" to { dataSignals("a-b", !flag) },
            "flag && mode == \"x\"" to { dataSignals("a-b", flag && mode == "x") },
            "flag || n > 1" to { dataSignals("a-b", flag || n > 1) },
            "n > 1" to { dataSignals("a-b", n > 1) },
            "n >= 1" to { dataSignals("a-b", n >= 1) },
            "(n < 1)" to { dataSignals("a-b", (n < 1)) },
            "\"x\" in mode" to { dataSignals("a-b", "x" in mode) },
            "mode !in listOf(\"x\")" to { dataSignals("a-b", mode !in listOf("x")) },
            "expr is String" to { dataSignals("a-b", expr is String) },
            "signal(\"a\") == \"x\"" to { dataSignals("a-b", signal("a") == "x") },
            "flag" to { dataSignals("a-b", flag) },
            "expr" to { dataSignals("a-b", expr) },
            "mode.isEmpty()" to { dataSignals("a-b", mode.isEmpty()) },
            "mode.startsWith(\"x\")" to { dataSignals("a-b", mode.startsWith("x")) },
            "\"x\".isEmpty()" to { dataSignals("a-b", "x".isEmpty()) },
            "expr.trim()" to { dataSignals("a-b", expr.trim()) },
            "\"x\".repeat(n)" to { dataSignals("a-b", "x".repeat(n)) },
            "emptyList<String>().joinToString()" to { dataSignals("a-b", emptyList<String>().joinToString()) },
            "listOf<String>().isEmpty()" to { dataSignals("a-b", listOf<String>().isEmpty()) },
            "if (flag) \"1\" else \"2\"" to { dataSignals("a-b", if (flag) "1" else "2") },
        )

    private fun overload(call: HTMLTag.() -> Unit): String =
        try {
            createHTML().div { call() }
            "ifMissing"
        } catch (_: InvalidSignalNameException) {
            "expression"
        }

    @Test
    fun `reads the second argument of dataSignals as the compiler does`() {
        val calls = calls(mode = "y", flag = true, expr = "1", n = 2)
        assertEquals(calls.keys.toList(), table.map { it.string("argument") }, "the table and the compiled calls")
        val analyzer = Analyzer()
        for (case in table) {
            val argument = case.string("argument")!!
            assertEquals(case.string("kotlin"), overload(calls.getValue(argument)), argument)
            val read = case.string("analysis")!!
            assertTrue(read == "unknown" || read == case.string("kotlin"), "$argument: the analysis may not contradict the compiler")
            val src =
                "import io.github.markusaugust.streamlord.html.*\n" +
                    "fun p(mode: String, flag: Boolean, expr: String, n: Int) = div { dataSignals(\"a-b\", $argument) }"
            val reported = analyzer.analyzeKotlin(src).any { it.code == "signal-name-hyphen" }
            assertEquals(read == "expression", reported, argument)
        }
    }
}
