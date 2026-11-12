package io.github.markusaugust.streamlord.analysis

import io.github.markusaugust.streamlord.fixtures.assertGrowsLinearly
import kotlin.test.Test

/**
 * Each of these read the source before a call or a value again for every one there is, and took
 * seconds on a file or a string of a few thousand: the declarations before each `dataSignals`, the
 * file lexed again for each call handed a variable, the expression before each value.
 */
class KotlinFileTimingTest {
    private val analyzer = Analyzer()
    private var copy = 0

    // A package of its own each time, so nothing of an earlier pass is cached for it.
    private fun file(body: (Int) -> String) = { n: Int -> "package p${copy++}\n" + (0 until n).joinToString("\n", transform = body) }

    private fun grows(
        what: String,
        size: Int,
        src: (Int) -> String,
    ) = assertGrowsLinearly(what, size, src) { analyzer.analyzeKotlin(it) }

    @Test
    fun `each dataSignals call is read with the declarations before it, which are read once`() {
        grows("dataSignals calls", 20, file { "fun f$it() = div { val s$it = Form(); dataSignals(codec.toJsonValue(s$it)) }" })
    }

    @Test
    fun `each attributes assignment finds its string without searching the strings before it`() {
        val assignments = file { "fun T.f$it() { attributes[\"data-on:click\"] = \"\\\$n$it++\" }" }
        grows("attributes assignments", 40, assignments)
        // What the IntelliJ plugin's spelling asks of a file.
        assertGrowsLinearly("attributes assignments listed", 40, assignments) { analyzer.attributesIndexes(it) }
    }

    @Test
    fun `a call handed a variable does not lex the file again`() {
        grows(
            "patchElements calls with a mode and no selector",
            20,
            file {
                "fun f$it(h: String) = sse.patchElements(h, mode = ElementPatchMode.INNER)"
            },
        )
    }

    @Test
    fun `a call with a mode and no selector is told so without reading the file before it`() {
        grows(
            "patchElements calls that need a selector",
            20,
            file {
                "fun f$it() = sse.patchElements(\"<p>x</p>\", mode = ElementPatchMode.INNER)"
            },
        )
    }

    @Test
    fun `each value in one expression is told whether it stands in a string without reading the expression again`() {
        grows("values in a DSL expression", 20) { n ->
            "package p${copy++}\nfun f(a: String) = div { dataOnClick(\"" + "\$a + ".repeat(n) +
                "1\") }"
        }
        grows("values in an expression attribute", 20) { n ->
            "package p${copy++}\nfun f(a: String) = \"<div data-text=\\\"" +
                "\$a + ".repeat(n) +
                "1\\\"></div>\""
        }
    }

    @Test
    fun `each value without quotes is offered its quotes without reading the markup before it again`() {
        grows("unquoted values", 1000) { n -> "package p${copy++}\nfun f(a: String) = \"<p>" + "<b title=\$a>x</b>".repeat(n) + "</p>\"" }
    }
}
