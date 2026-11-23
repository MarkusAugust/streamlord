package io.github.markusaugust.streamlord.analysis

import kotlin.test.Test
import kotlin.test.assertEquals

/** An annotation is read to its end once for the file, as Kotlin reads it, by every reader of it. */
class AnnotationEndTest {
    @Test
    fun `a file annotation may have blanks before its name and its arguments`() {
        for (header in listOf("@file: JvmName(\"X\")", "@file:JvmName (\"X\")", "@file:JvmName\t(\"X\")")) {
            val src = "$header\n\nfun f() = 1\n"
            val edit = importEdits(src, "a.b", "C").single()
            assertEquals(header.length, edit.start, header)
        }
    }
}
