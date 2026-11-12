package io.github.markusaugust.streamlord.probes

import io.github.markusaugust.streamlord.core.domain.Html
import io.github.markusaugust.streamlord.core.domain.Trusted
import io.github.markusaugust.streamlord.core.domain.interpolate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val REWRITE = "Build this string with interpolate(...)"
private const val HOSTILE = "<i a=\"b\">'&"
private const val LINES = "a\n      b"

/** The values a parameter is run with, by what they try: [plain] alone must leave every rewrite's result as it was. */
private class Vector(
    val kind: String,
    val values: List<Any?>,
    /** The parameter given the value this vector is about, or -1. */
    val at: Int = -1,
)

/**
 * Every probe compiled as written and as each fix leaves it, and both run. A rewrite to
 * `interpolate` gives what the string gave for plain values; a preferred one also for a value that
 * is null or holds a line break; and it never throws, nor writes a hostile value as it is where it
 * passes it to be escaped. Any other fix of a value's issue leaves the result as it was.
 */
class RewriteProbesTest {
    private fun plain(type: String): Any? =
        when (type.removeSuffix("?")) {
            "String", "Any" -> "Ann"
            "Int" -> 3
            "Boolean" -> true
            "Html" -> interpolate("%s", "x")
            "Trusted", "Badge" -> Trusted("<b title=\"x\">y</b>")
            "Widget" -> Widget("Ann")
            "User" -> User("Ann", listOf("a", "b"), Trusted("<b>y</b>"))
            "WidgetContext" -> WidgetContext("root", "content", "events")
            "List<String>" -> listOf("a", "b")
            "Array<String>" -> arrayOf("a", "b")
            else -> error("No value for a parameter of type $type")
        }

    private fun vectors(probe: Probe): List<Vector> {
        val base = probe.types.map(::plain)
        val out = arrayListOf(Vector("plain", base))
        probe.types.forEachIndexed { k, type ->
            fun with(
                kind: String,
                value: Any?,
            ) = out.add(Vector(kind, base.toMutableList().also { it[k] = value }, k))
            if (type.endsWith("?")) with("null", null)
            when (type.removeSuffix("?")) {
                "String" -> {
                    with("lines", LINES)
                    with("hostile", HOSTILE)
                }

                "Any" -> {
                    with("plain", listOf("a", "b"))
                    with("plain", listOf("a"))
                    with("hostile", HOSTILE)
                }

                "Widget" -> {
                    with("hostile", Widget(HOSTILE))
                }

                // Tried as com.acme's Html where the probe means that one.
                "Html" -> {
                    with("hostile", interpolate("<b>%s</b>", "x"))
                }

                "List<String>" -> {
                    with("plain", listOf("a"))
                    with("plain", emptyList<String>())
                }
            }
        }
        return out
    }

    private fun run(
        f: (List<Any?>) -> Any?,
        values: List<Any?>,
    ): Result<String> =
        runCatching {
            when (val made = f(values)) {
                is Html -> made.text
                else -> made.toString()
            }
        }

    @Test
    fun `every fix leaves code that runs and means what the string meant`() {
        val failures = ArrayList<String>()
        var run = 0
        for (probe in PROBES) {
            for (vector in vectors(probe)) {
                // An Html is core's in most probes and com.acme's in some: the probe's own cast says which.
                var values = vector.values
                var before = run(probe.before, values)
                if (before.exceptionOrNull() is ClassCastException) {
                    val text = if (vector.kind == "hostile") HOSTILE else "Ann"
                    values = values.map { if (it is Html) com.acme.Html(text) else it }
                    before = run(probe.before, values)
                }
                for (variant in probe.variants) {
                    run++
                    val after = run(variant.run, values)
                    val where = if (vector.at >= 0) " in parameter ${vector.at}" else ""
                    val at = "${probe.name}, ${variant.title}, ${vector.kind}$where:\n    ${variant.source.trim()}"
                    val failure = after.exceptionOrNull()
                    val wrote = after.getOrNull()
                    val was = before.getOrThrow()
                    val rewrite = variant.title.startsWith(REWRITE)
                    val same = wrote == was
                    when {
                        failure != null && !(vector.kind == "hostile" && "refuses" in variant.title) -> {
                            failures += "$at\n    throws ${failure::class.simpleName}: ${failure.message}"
                        }

                        failure != null -> {}

                        !rewrite -> {
                            if (!same && variant.title != "Quote the value") failures += "$at\n    wrote $wrote\n    for   $was"
                        }

                        vector.kind == "plain" -> {
                            if (!same) failures += "$at\n    wrote $wrote\n    for   $was"
                        }

                        vector.kind == "null" -> {
                            if (!same &&
                                (variant.preferred || wrote != was.replace("null", ""))
                            ) {
                                failures += "$at\n    wrote $wrote\n    for   $was"
                            }
                        }

                        vector.kind == "lines" -> {
                            if (!same && variant.preferred) failures += "$at\n    is preferred, and wrote $wrote\n    for   $was"
                        }

                        // Markup is written as it is in element text and escaped in an attribute, which is what its warning there asks for.
                        values[vector.at] is Html -> {}

                        else -> {
                            val name =
                                probe.source
                                    .substringAfter("fun f(")
                                    .split(",")[vector.at]
                                    .substringBefore(":")
                                    .trim()
                            val vouched = Regex("""Trusted\(\s*${Regex.escape(name)}""").containsMatchIn(variant.source)
                            // The rewrite of a string inside a template of another leaves the outer string's values to Kotlin.
                            val template = Regex("""\$\{?${Regex.escape(name)}""").containsMatchIn(variant.source)
                            if (!vouched && !template && HOSTILE in wrote!!) failures += "$at\n    wrote the value as it is: $wrote"
                        }
                    }
                }
            }
        }
        assertEquals(emptyList(), failures, "${failures.size} of $run runs fail:\n" + failures.joinToString("\n"))
        assertTrue(run > 500, "only $run runs")
    }

    @Test
    fun `each case offers the rewrite, marks the values and says what it is written to`() {
        val failures = ArrayList<String>()
        for (probe in PROBES) {
            val rewrites = probe.variants.filter { it.title.startsWith(REWRITE) }
            val offered =
                when {
                    rewrites.isEmpty() -> "none"
                    rewrites.any { it.preferred } -> "preferred"
                    else -> "offered"
                }
            if (probe.rewrite != null && probe.rewrite != offered) {
                failures += "${probe.name}: the rewrite is $offered, expected ${probe.rewrite}\n    ${probe.source.trim()}"
            }
            for (words in probe.says) {
                if (probe.issues.none { words in it.message }) {
                    failures +=
                        "${probe.name}: no message says \"$words\": ${probe.issues.map { it.message }}"
                }
            }
            for (value in probe.marks) {
                if (probe.issues.none { it.text == value }) {
                    failures +=
                        "${probe.name}: no issue marks $value, only ${probe.issues.map { it.text }}"
                }
            }
        }
        assertEquals(emptyList(), failures, "${failures.size} cases fail:\n" + failures.joinToString("\n"))
    }
}
