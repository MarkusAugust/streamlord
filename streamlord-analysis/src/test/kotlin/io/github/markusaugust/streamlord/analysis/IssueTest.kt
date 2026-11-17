package io.github.markusaugust.streamlord.analysis

import kotlin.test.Test
import kotlin.test.assertEquals

/** [Issue.format], as a build or another tool writes an issue out; analyzeProject's issues are in AnalyzeProjectTest. */
class IssueTest {
    @Test
    fun `an issue formats with its code in brackets`() {
        assertEquals(
            "a.html:1:5: warning: Something. [kotlin-interpolation]",
            Issue(4, 5, "Something.", Severity.WARNING, IssueCode.KOTLIN_INTERPOLATION).format("a.html", "0123456"),
        )
    }

    @Test
    fun `an offset outside the text is held to its ends`() {
        assertEquals("a:1:1: warning: x [unclosed]", Issue(-4, -3, "x", Severity.WARNING, IssueCode.UNCLOSED).format("a", "ab\ncd"))
        assertEquals("a:2:3: warning: x [unclosed]", Issue(99, 100, "x", Severity.WARNING, IssueCode.UNCLOSED).format("a", "ab\ncd"))
    }
}
