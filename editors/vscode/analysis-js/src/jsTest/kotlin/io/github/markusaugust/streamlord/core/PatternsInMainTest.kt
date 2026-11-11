package io.github.markusaugust.streamlord.core

import io.github.markusaugust.streamlord.fixtures.fixtureNames
import io.github.markusaugust.streamlord.fixtures.fixtureText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every pattern in the sources this build shares with the JVM has one meaning on both engines:
 * each is built by [regex], and [regex] knows how to spell each for JavaScript. PatternsTest holds
 * what the spellings mean; a pattern put together at run time is translated, or refused, when it
 * is built, in the tests that reach it and in the editor.
 */
class PatternsInMainTest {
    private val roots = listOf("streamlord-core/src/main/kotlin", "streamlord-analysis/src/main/kotlin")

    private fun sources(folder: String): List<String> =
        fixtureNames(folder).flatMap { name ->
            when {
                name.endsWith(".kt") -> listOf("$folder/$name")
                '.' in name -> emptyList()
                else -> sources("$folder/$name")
            }
        }

    private val files = roots.flatMap(::sources).filterNot { it.endsWith("PlatformJvm.kt") }

    @Test
    fun `no pattern is built past regex`() {
        assertTrue(files.size > 40)
        val direct = Regex("""(?<![A-Za-z_])Regex\(|\.toRegex\(|\bPattern\.compile\(""")
        assertEquals(emptyList(), files.filter { direct.containsMatchIn(fixtureText(it)) })
    }

    @Test
    fun `every pattern written in the sources has a JavaScript spelling`() {
        val refused = ArrayList<String>()
        var seen = 0
        for (file in files) {
            for (literal in patternLiterals(fixtureText(file))) {
                seen++
                runCatching { JavaScriptPattern(literal, emptySet()).translated() }.onFailure { refused += "$file: ${it.message}" }
            }
        }
        assertTrue(seen > 250, "found $seen patterns")
        assertEquals(emptyList(), refused)
    }

    /** The string literals in the arguments of each `regex(` call, a Kotlin template in one read as the letter x. */
    private fun patternLiterals(src: String): List<String> {
        val out = ArrayList<String>()
        for (call in Regex("""(?<![A-Za-z_.])regex\(""").findAll(src)) {
            var i = call.range.last + 1
            var depth = 1
            while (i < src.length && depth > 0) {
                when {
                    src.startsWith(RAW, i) -> {
                        var end = src.indexOf(RAW, i + 3)
                        while (src.startsWith("\"", end + 3)) end++
                        out += templatesAsLetters(src.substring(i + 3, end))
                        i = end + 3
                    }

                    src[i] == '"' -> {
                        var end = i + 1
                        while (src[end] != '"') end += if (src[end] == '\\') 2 else 1
                        out += templatesAsLetters(unescaped(src.substring(i + 1, end)))
                        i = end + 1
                    }

                    else -> {
                        if (src[i] == '(') depth++
                        if (src[i] == ')') depth--
                        i++
                    }
                }
            }
        }
        return out
    }

    private fun templatesAsLetters(text: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < text.length) {
            val next = text.getOrNull(i + 1)
            when {
                text[i] == ESCAPED_DOLLAR -> {
                    out.append('$')
                    i++
                }

                text[i] == '$' && next == '{' -> {
                    var depth = 0
                    do {
                        if (text[i] == '{') depth++
                        if (text[i] == '}') depth--
                        i++
                    } while (depth > 0)
                    out.append('x')
                }

                text[i] == '$' && next != null && (next.isLetter() || next == '_') -> {
                    i++
                    while (i < text.length && (text[i].isLetterOrDigit() || text[i] == '_')) i++
                    out.append('x')
                }

                else -> {
                    out.append(text[i++])
                }
            }
        }
        return out.toString()
    }

    /** An escaped Kotlin literal as the text it stands for, an escaped dollar marked so that it is no template. */
    private fun unescaped(literal: String): String =
        Regex("""\\(u[0-9a-fA-F]{4}|.)""").replace(literal) { m ->
            val c = m.groupValues[1]
            when {
                c.length > 1 -> {
                    c
                        .substring(1)
                        .toInt(16)
                        .toChar()
                        .toString()
                }

                c == "n" -> {
                    "\n"
                }

                c == "t" -> {
                    "\t"
                }

                c == "r" -> {
                    "\r"
                }

                c == "$" -> {
                    ESCAPED_DOLLAR.toString()
                }

                else -> {
                    c
                }
            }
        }

    private companion object {
        const val RAW = "\"\"\""
        const val ESCAPED_DOLLAR = '\uE000'
    }
}
