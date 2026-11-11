package io.github.markusaugust.streamlord.core

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What a pattern means, written down once as java.util.regex reads it. The JavaScript build of the
 * analysis runs the same cases on its own engine, which reads white space, the end of the input, the
 * dot and letter case differently unless [regex] says otherwise.
 */
class PatternsTest {
    /** Where [pattern] matches in [input], each match as `start-end` with the end exclusive. */
    private fun found(
        pattern: String,
        input: String,
        vararg options: RegexOption,
    ): List<String> = regex(pattern, *options).findAll(input).map { "${it.range.first}-${it.range.last + 1}" }.toList()

    @Test
    fun `white space is the six ASCII characters`() {
        assertEquals(listOf("0-1", "2-3", "4-5", "6-7", "8-9", "10-11"), found("""\s""", " a\tb\nc\u000Bd\u000Ce\r"))
        assertEquals(emptyList(), found("""\s""", "\u00A0\u2028\u2029\uFEFF\u3000\u0085"))
        assertEquals(listOf("0-3", "4-5"), found("""\S+""", "\u00A0\u2028x y"))
        assertEquals(listOf("1-3"), found("""[\s,]+""", "\u00A0 ,\u2028"))
        assertEquals(listOf("0-1", "2-3"), found("""[^\s]""", "\u00A0 \u2028"))
        assertEquals(listOf("0-4"), found("""[\s\S]+""", "\u00A0 \nx"))
    }

    @Test
    fun `the end of the input is also before its last line break`() {
        assertEquals(listOf("0-1"), found("a$", "a"))
        assertEquals(listOf("0-1"), found("a$", "a\n"))
        assertEquals(listOf("0-1"), found("a$", "a\r\n"))
        assertEquals(listOf("0-1"), found("a$", "a\r"))
        assertEquals(listOf("0-1"), found("a$", "a\u2028"))
        assertEquals(listOf("0-1"), found("a$", "a\u0085"))
        assertEquals(emptyList(), found("a$", "a\n\n"))
        assertEquals(emptyList(), found("a$", "a\nb"))
        assertEquals(listOf("1-1", "2-2"), found("$", "a\n"))
        assertEquals(listOf("1-1", "3-3"), found("$", "a\r\n"))
        assertEquals(listOf("0-1"), found("""a\Z""", "a\n"))
        assertEquals(emptyList(), found("""a\z""", "a\n"))
        assertEquals(listOf("2-3"), found("""a\z""", "a\na"))
        assertEquals(listOf("0-1"), found("""\Aa""", "a\na"))
        assertEquals(listOf("0-2"), found("""[$]a""", "\$a"))
    }

    @Test
    fun `a line starts and ends at every kind of line break when the pattern asks for lines`() {
        assertEquals(listOf("0-1", "3-4", "5-6"), found("^.", "a\r\nb\nc", RegexOption.MULTILINE))
        assertEquals(listOf("0-1", "3-4", "5-6"), found(".$", "a\r\nb\nc", RegexOption.MULTILINE))
        assertEquals(listOf("1-1", "4-4", "5-5"), found("$", "a\r\nb\n", RegexOption.MULTILINE))
        assertEquals(listOf("0-0", "3-3"), found("^", "a\r\nb\n", RegexOption.MULTILINE))
        assertEquals(listOf("0-1", "2-3"), found("^.", "a\u0085b", RegexOption.MULTILINE))
    }

    @Test
    fun `the dot stops at each line break and takes them all when told to`() {
        assertEquals(listOf("0-1", "6-7"), found(".+", "a\n\r\u0085\u2028\u2029b"))
        assertEquals(listOf("0-3"), found(".+", "a\u000Bb"))
        assertEquals(listOf("0-7"), found("(?s).+", "a\n\r\u0085\u2028\u2029b"))
        assertEquals(listOf("0-3"), found("(?s)a.b", "a\nb"))
        assertEquals(listOf("0-1"), found("[.]", ".a"))
        assertEquals(listOf("0-2"), found(".", "\uD83D\uDE00"))
    }

    @Test
    fun `letter case written into the pattern is ignored for ASCII letters alone`() {
        assertEquals(listOf("0-6"), found("(?i)data-x", "DATA-X"))
        assertEquals(listOf("0-1", "1-2"), found("(?i)k", "kK\u212A"))
        assertEquals(listOf("0-2"), found("(?i)[a-z]+", "sS\u017F\u212A"))
        assertEquals(listOf("0-2"), found("(?i)[^a-f]+", "xYeE"))
        assertEquals(listOf("2-3", "3-4"), found("(?i)[^X]", "xXa\u212A"))
        assertEquals(listOf("0-3"), found("(?i)[\\p{L}_.-]+", "aB_ "))
        assertEquals(listOf("0-4"), found("""(?i)a\x41\u0061\d""", "AaA1"))
    }

    @Test
    fun `letter case asked for as an option is ignored for every letter`() {
        assertEquals(listOf("0-1", "1-2", "2-3"), found("k", "kK\u212A", RegexOption.IGNORE_CASE))
        assertEquals(listOf("0-4"), found("[a-z]+", "sS\u017F\u212A", RegexOption.IGNORE_CASE))
        assertEquals(listOf("0-1"), found("\u00E9", "\u00C9", RegexOption.IGNORE_CASE))
        assertEquals(listOf("0-5"), found("""</?svg""", "</SVG>", RegexOption.IGNORE_CASE))
        assertEquals(listOf("0-6"), found("""(?s)<svg.*?>""", "<SVG\n>", RegexOption.IGNORE_CASE))
    }

    @Test
    fun `a bracket or a brace that closes nothing is itself`() {
        assertEquals(listOf("0-2"), found("""a]""", "a]"))
        assertEquals(listOf("0-3"), found("""\$\{\s*}""", "\${}"))
        assertEquals(listOf("0-3", "3-3"), found("""(?:]]>|$)""", "]]>"))
        assertEquals(listOf("0-1", "1-2"), found("""[]a]""", "]a"))
        assertEquals(listOf("0-3"), found("""x{2}y""", "xxy"))
    }

    @Test
    fun `an escape names the same character`() {
        assertEquals(listOf("0-1", "1-3"), found("""[A-Za-z0-9_$\x{80}-\x{10FFFF}]""", "\u00E9\uD83D\uDE00 "))
        assertEquals(listOf("0-7"), found("""\"\'\<\>\=\:\-""", "\"'<>=:-"))
        assertEquals(listOf("0-3"), found("""[\-\"\`]+""", "-\"`"))
        assertEquals(listOf("0-1"), found("""\d""", "1\u0663"))
        assertEquals(listOf("0-2"), found("""\w+""", "a_\u00E9"))
        assertEquals(listOf("0-2"), found("""[\p{L}\p{Nd}]+""", "\u00E9\u0663 "))
        assertEquals(listOf("0-1"), found("""\p{Lu}""", "Aa"))
        assertEquals(listOf("0-5"), found("""a\x62\t\n\r""", "ab\t\n\r"))
        assertEquals(listOf("0-3"), found(Regex.escape("a.b"), "a.b a-b"))
        assertEquals(listOf("3-4"), found("""(?<![.\w$])b(?=c)(?!d)(?<=b)""", "ab bc"))
    }

    @Test
    fun `where a group stands is known`() {
        val pattern = regex("""(a)?(b+)""")
        val match = pattern.find("xxbb")!!
        assertEquals(null, pattern.groupRange("xxbb", match, 1))
        assertEquals(2..3, pattern.groupRange("xxbb", match, 2))
    }
}
