package io.github.markusaugust.streamlord.core

import io.github.markusaugust.streamlord.core.client.JavaScriptPattern
import io.github.markusaugust.streamlord.core.client.regex
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

    /** Kotlin/JS answers `matches` from the first match at the start, and never tries for one that reaches the end. */
    @Test
    fun `a whole input is asked for with matchEntire`() {
        assertEquals(emptyList(), files.filter { ".matches(" in fixtureText(it) })
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

    /**
     * PatternsTest has why: after an empty match the JVM looks again inside an astral character, and
     * JavaScript past it. A pattern may match an empty string only next to some text, `\b` or a look
     * around, so each is tried on texts of the kinds the analysis reads.
     */
    @Test
    fun `no pattern that matches an empty string is iterated with findAll`() {
        val iterated = ArrayList<String>()
        var seen = 0
        for (file in files) {
            val src = fixtureText(file)
            for (call in Regex("""\b([A-Z][A-Z0-9_]*)\.findAll\(""").findAll(src)) {
                val name = call.groupValues[1]
                val declared = Regex("""\bval $name\s*=\s*regex\(""").find(src) ?: continue
                val literal = patternLiterals(src.substring(declared.range.first)).firstOrNull() ?: continue
                seen++
                val pattern = regex(literal)
                if (PROBES.any { probe -> pattern.findAll(probe).any { it.range.isEmpty() } }) iterated += "$file: $name"
            }
        }
        assertTrue(seen > 30, "found $seen iterated patterns")
        assertEquals(emptyList(), iterated)
    }

    /**
     * A repeated group one of whose ways may end in a repeat that can take what starts the group again,
     * `(?:\s|//[^\n]*)*`, matches a run of the same text in more ways than one: JavaScript's engine tries
     * them all where the rest fails to match, twice as many with each more of the run.
     */
    @Test
    fun `no repeated group can end in a repeat that takes what starts it`() {
        val ambiguous = ArrayList<String>()
        for (file in files) {
            for (literal in patternLiterals(fixtureText(file))) {
                if (ambiguousRepeat(Shape(literal).alternatives())) ambiguous += "$file: $literal"
            }
        }
        assertEquals(emptyList(), ambiguous)
    }

    @Test
    fun `the audit of repeated groups tells an ambiguous one from one each of whose repeats starts apart`() {
        for (p in listOf("""(?:\s|//[^\n]*)*$""", """(\w+\s?)*$""", """(?:a|(?:b+))+""", """(?:x[^y]*)+""")) {
            assertTrue(ambiguousRepeat(Shape(p).alternatives()), p)
        }
        for (p in listOf(
            """\w+(?:[.-]\w+)*""",
            """"[^"\\]*(?:\\.[^"\\]*)*"""",
            """\([^()]*(?:\([^()]*\)[^()]*)*\)""",
            """(?:\s*\b(?:in|new)\b)+""",
        )) {
            assertTrue(!ambiguousRepeat(Shape(p).alternatives()), p)
        }
    }

    /** A character, a class or a group of [alternatives], as often as [min] to [unbounded]; [zeroWidth] for an anchor or a look around. */
    private class Piece(
        val atom: String?,
        val alternatives: List<List<Piece>>,
        val min: Int,
        val unbounded: Boolean,
        val zeroWidth: Boolean,
    ) {
        val nullable: Boolean get() = zeroWidth || min == 0 || (atom == null && alternatives.any { a -> a.all { it.nullable } })
    }

    /** A pattern read into [Piece]s, as far as the shape of its repeats goes. */
    private class Shape(
        val p: String,
    ) {
        var i = 0

        fun alternatives(): List<List<Piece>> {
            val out = ArrayList<List<Piece>>()
            var seq = ArrayList<Piece>()
            while (i < p.length && p[i] != ')') {
                if (p[i] == '|') {
                    out += seq
                    seq = ArrayList()
                    i++
                } else {
                    seq += piece()
                }
            }
            out += seq
            return out
        }

        private fun piece(): Piece {
            val start = i
            var group: List<List<Piece>> = emptyList()
            var zeroWidth = false
            when (p[i]) {
                '(' -> {
                    i++
                    if (p[i] == '?') {
                        i++
                        when {
                            p[i] == ':' -> {
                                i++
                            }

                            p[i] == '=' || p[i] == '!' -> {
                                zeroWidth = true
                                i++
                            }

                            p[i] == '<' && (p[i + 1] == '=' || p[i + 1] == '!') -> {
                                zeroWidth = true
                                i += 2
                            }

                            p[i] == '<' -> {
                                i = p.indexOf('>', i) + 1
                            }

                            // Flags, `(?s)` alone or `(?i:...)` over a group.
                            else -> {
                                while (p[i] != ')' && p[i] != ':') i++
                                if (p[i] == ':') i++
                            }
                        }
                    }
                    group = alternatives()
                    i++
                    if (group.all { it.isEmpty() }) zeroWidth = true
                }

                '\\' -> {
                    val c = p[i + 1]
                    i += 2
                    when {
                        c in "pP" || (c == 'x' && p.getOrNull(i) == '{') -> i = p.indexOf('}', i) + 1
                        c == 'u' -> i += 4
                        c == 'x' -> i += 2
                    }
                    zeroWidth = c in "bBzZAG"
                }

                '[' -> {
                    i++
                    if (p[i] == '^') i++
                    if (p[i] == ']') i++
                    while (p[i] != ']') i += if (p[i] == '\\') 2 else 1
                    i++
                }

                '^', '$' -> {
                    zeroWidth = true
                    i++
                }

                else -> {
                    i++
                }
            }
            val atom = if (p[start] == '(' || zeroWidth) null else p.substring(start, i)
            var min = 1
            var unbounded = false
            val bounds = if (i < p.length && p[i] == '{') QUANTIFIER.matchAt(p, i) else null
            val quantified = bounds != null || (i < p.length && p[i] in "*+?")
            if (bounds != null) {
                min = bounds.groupValues[1].toInt()
                unbounded = bounds.groupValues[2] == "," && bounds.groupValues[3].isEmpty()
                i = bounds.range.last + 1
            } else if (quantified) {
                min = if (p[i] == '+') 1 else 0
                unbounded = p[i] != '?'
                i++
            }
            // Lazy or possessive, which changes the order the ways are tried in, not how many there are.
            if (quantified && i < p.length && (p[i] == '?' || p[i] == '+')) i++
            return Piece(atom, if (zeroWidth) emptyList() else group, min, unbounded, zeroWidth)
        }
    }

    /** Whether some repeated group of [alternatives] can end in a repeat that takes a character that starts it. */
    private fun ambiguousRepeat(alternatives: List<List<Piece>>): Boolean =
        alternatives.flatten().any { piece ->
            val inner = piece.alternatives
            (
                piece.atom == null && piece.unbounded && inner.isNotEmpty() &&
                    run {
                        val first = inner.flatMap(::firstAtoms)
                        inner.flatMap { lastAtoms(it, false) }.any { last -> first.any { overlap(last, it) } }
                    }
            ) || ambiguousRepeat(inner)
        }

    /** The characters and classes a match of [seq] can start with. */
    private fun firstAtoms(seq: List<Piece>): List<String> {
        val out = ArrayList<String>()
        for (piece in seq) {
            if (piece.atom != null) out += piece.atom else piece.alternatives.forEach { out += firstAtoms(it) }
            if (!piece.nullable) break
        }
        return out
    }

    /** The characters and classes repeated without bound that a match of [seq] can end with; [repeated] where seq itself is. */
    private fun lastAtoms(
        seq: List<Piece>,
        repeated: Boolean,
    ): List<String> {
        val out = ArrayList<String>()
        for (piece in seq.asReversed()) {
            val more = repeated || piece.unbounded
            if (piece.atom != null) {
                if (more) out += piece.atom
            } else {
                piece.alternatives.forEach { out += lastAtoms(it, more) }
            }
            if (!piece.nullable) break
        }
        return out
    }

    /** Whether one character, of ASCII or a few beyond, matches both [a] and [b]. */
    private fun overlap(
        a: String,
        b: String,
    ): Boolean {
        // A back reference stands for what its group took: anything, as far as this goes.
        val x = runCatching { regex(a) }.getOrNull() ?: return true
        val y = runCatching { regex(b) }.getOrNull() ?: return true
        return SAMPLES.any { x.matchEntire(it) != null && y.matchEntire(it) != null }
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
        val PROBES =
            listOf(
                "",
                "a",
                " ",
                "\n",
                "a b",
                "x.y-z",
                "\$a = 1; @get('/x')",
                "<div data-on:click__once=\"\$a++\">x</div>",
                "fun f() = div { dataText(\"\$\\{x}\") }",
                "a\uD83D\uDE00b",
            )
        val QUANTIFIER = regex("""\{(\d+)(,?)(\d*)}""")
        val SAMPLES = (0 until 128).map { it.toChar().toString() } + listOf("\u00E9", "\u00A0", "\u2028", "\uD83D\uDE00")
        const val RAW = "\"\"\""
        const val ESCAPED_DOLLAR = '\uE000'
    }
}
