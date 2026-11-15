package io.github.markusaugust.streamlord.analysis

import kotlin.test.Test
import kotlin.test.assertEquals

class SignalRenamesTest {
    @Test
    fun `what a rename writes at each place`() {
        for (case in sharedCases("signal-renames.json")) {
            val name = case.string("name")
            val rename = case.string("rename")!!
            val expected = case.string("text")
            val read = case.string("read")
            if (read != null) {
                assertEquals(expected, signalReadRenameText(read, rename), name)
                continue
            }
            val src = case.string("src")!!
            val language = SourceLanguage.valueOf(case.string("language")!!.uppercase())
            val at = case.string("at")!!
            val d = signalDeclarations(Source(src, language)).first { src.substring(it.start, it.end) == at }
            assertEquals(expected, signalRenameText(src, d, rename), name)
        }
    }
}
