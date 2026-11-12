package io.github.markusaugust.streamlord.analysis

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Calls of every function the analysis looks for, written every way an argument list can be
 * written: named, by position, missing, no literal, with a call after it, spread, a trailing
 * lambda, empty parentheses. Made from a seed, so a run can be repeated.
 */
internal object CallShapes {
    private val analyzer = Analyzer()
    private val extra = listOf("interpolate", "Trusted", "f")
    private val names = (analyzer.callSiteNames + analyzer.dslNames + analyzer.catalog.signalNameHelpers.keys + extra).sorted()
    private val parameters =
        (
            "name event key property attribute signal expression format nonce value case modifiers selector mode elements " +
                "signals script useViewTransition onlyIfMissing"
        ).split(" ")
    private val values =
        listOf(
            "\"x\"",
            "\"\"",
            "\"tabIndex\"",
            "\"tab-index\"",
            "\"on:click\"",
            "\"{a: 1}\"",
            "\"\$count\"",
            "\"\\\$count\"",
            "\"\${v}\"",
            "\"<p id=\\\"a\\\">\$v</p>\"",
            "\"<a href=\\\"\$v\\\">\${w}</a>\"",
            "\"\"\"<p>\$v</p>\"\"\"",
            "\"\"\"\n  |<p>\$v</p>\"\"\".trimMargin()",
            "\$\$\"<p>\$\$v \$w</p>\"",
            "\"x\".uppercase()",
            "\"<p>\".trimIndent()",
            "\"<p>\" + v",
            "v + \"</p>\"",
            "\"<p>\" + v + \"\"\"</p>\"\"\"[0]",
            "\"a\" + \"b\".trim()",
            "v",
            "f(v)",
            "*xs",
            "null",
            "1",
            "true",
            "Case.KEBAB",
            "ElementPatchMode.APPEND",
            "{ }",
            "listOf(\"a\")",
            "'x'",
            "\"@get('/x/\$id')\"",
            "\"%s <b>%s</b>\"",
            "Trusted(\"<b>\")",
            "\"abc",
            "\"\"\"abc",
            "",
            "(",
            ")",
            "@A(\"<p>\$v</p>\")",
        )
    private val lambdas = listOf("", "", "", " { }", " { li { +\"x\" } }", " { case = Case.CAMEL }", " { debounce(1) }", " {")
    private val frames =
        listOf(
            "fun f(v: String, w: Int) = div { § }",
            "val x = §",
            "suspend fun g(sse: Sse) { sse.§\n}",
            "import io.github.markusaugust.streamlord.html.*\nfun f(v: String) = div { §; § }",
            "div { attributes[\"data-on:click\"] = § }",
            "const val A = §",
        )

    private fun call(random: Random): String {
        val args =
            List(random.nextInt(4)) {
                val value = values[random.nextInt(values.size)]
                if (random.nextInt(3) == 0) "${parameters[random.nextInt(parameters.size)]} = $value" else value
            }
        val list = if (args.isEmpty() && random.nextBoolean()) "" else "(${args.joinToString(", ")})"
        return names[random.nextInt(names.size)] + list + lambdas[random.nextInt(lambdas.size)]
    }

    /**
     * [count] sources, the same for the same [seed]. One in four is cut off somewhere, as while it
     * is typed, and one in four has a character struck or one of [STRAY] put in.
     */
    fun sources(
        count: Int,
        seed: Int,
    ): Sequence<String> {
        val random = Random(seed)
        return generateSequence {
            val frame = frames[random.nextInt(frames.size)]
            val source = frame.split("§").let { parts -> parts.drop(1).fold(parts[0]) { out, part -> out + call(random) + part } }
            val at = random.nextInt(source.length)
            when (random.nextInt(8)) {
                0, 1 -> source.substring(0, at)
                2 -> source.removeRange(at, at + 1)
                3 -> source.substring(0, at) + STRAY[random.nextInt(STRAY.length)] + source.substring(at)
                else -> source
            }
        }.take(count)
    }

    private const val STRAY = "\"$(){}[]\\'+.,=@*%<>\n"
}

/** No source makes the analysis throw: a call it cannot read is one it says nothing of. */
class NeverThrowsTest {
    private val analyzer = Analyzer()

    /** Everything an editor asks of a Kotlin file, as each editor may set it up. */
    private fun analyse(src: String) {
        for (opts in OPTIONS) {
            val sites = analyzer.analyzeKotlinSites(src, opts)
            for (site in sites) for (issue in site.issues) for (fix in issue.fixes) fix.apply(src)
            for (at in 0..src.length step 5) rewriteAt(sites, at)?.apply(src)
            analyzer.signalReferencesInKotlin(src, opts)
        }
        analyzer.attributesIndexes(src)
        for (at in 0..src.length step 5) analyzer.htmlStringAt(src, at)
        collectSignals(src, SourceLanguage.KOTLIN)
        collectSignalDefinitions(src, SourceLanguage.KOTLIN)
        constantNames(src)
        otherDeclaredNames(src)
    }

    private fun thrown(sources: Sequence<String>): List<String> =
        sources.mapNotNull { src -> runCatching { analyse(src) }.exceptionOrNull()?.let { "$it in:\n$src" } }.toList()

    @Test
    fun `a helper whose name is given by name and is no literal is left alone`() {
        val src = "fun f() = div { dataAttr(name = \"tab-index\".uppercase(), expression = \"0\") }"
        assertEquals(emptyList(), analyzer.analyzeKotlin(src).filter { it.code == "attribute-case" })
    }

    @Test
    fun `no call of a function the analysis looks for makes it throw, however its arguments are written`() {
        assertEquals(emptyList(), thrown(CallShapes.sources(COUNT, 1)).take(5))
    }

    private companion object {
        const val COUNT = 4000
        val OPTIONS =
            listOf(
                AnalyzeOptions(),
                AnalyzeOptions(htmlDsl = true, valueFact = { _, _ -> ValueFact.STRING }),
                AnalyzeOptions(htmlDsl = false, checkHtmlAttributes = false, valueFact = { start, _ ->
                    ValueFact.entries[
                        start %
                            ValueFact.entries.size,
                    ]
                }),
                AnalyzeOptions(core = false),
            )
    }
}
