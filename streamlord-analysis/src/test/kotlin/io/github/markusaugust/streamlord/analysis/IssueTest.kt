package io.github.markusaugust.streamlord.analysis

import kotlin.test.Test
import kotlin.test.assertEquals

class IssueTest {
    @Test
    fun `the first character is line 1, column 1`() {
        assertEquals(SourcePosition(1, 1), sourcePosition("abc", 0))
    }

    @Test
    fun `a position after a line break starts the next line`() {
        val source = "one\ntwo\nthree"

        assertEquals(SourcePosition(2, 1), sourcePosition(source, source.indexOf("two")))
        assertEquals(SourcePosition(3, 3), sourcePosition(source, source.indexOf("ree")))
    }

    @Test
    fun `a CRLF line ending is one line break`() {
        val source = "one\r\ntwo"

        assertEquals(SourcePosition(2, 1), sourcePosition(source, source.indexOf("two")))
    }

    @Test
    fun `an offset outside the text is held to its ends`() {
        assertEquals(SourcePosition(1, 1), sourcePosition("ab\ncd", -4))
        assertEquals(SourcePosition(2, 3), sourcePosition("ab\ncd", 99))
    }

    @Test
    fun `an issue formats as file, line, column, severity and message, with its code`() {
        val source = "fun page() {\n    dataOnClick(\"${'$'}count++\")\n}\n"
        val issue = Analyzer().analyzeKotlin(source).single { it.code == "kotlin-interpolation" }

        assertEquals(SourcePosition(2, source.lines()[1].indexOf("${'$'}count") + 1), issue.position(source))
        assertEquals(
            "src/Page.kt:2:${issue.position(source).column}: error: ${issue.message} [kotlin-interpolation]",
            issue.format("src/Page.kt", source),
        )
    }

    @Test
    fun `an issue without a code formats without the brackets`() {
        val issue = Issue(4, 5, "Something.", Severity.WARNING)

        assertEquals("a.html:1:5: warning: Something.", issue.format("a.html", "0123456"))
    }
}
