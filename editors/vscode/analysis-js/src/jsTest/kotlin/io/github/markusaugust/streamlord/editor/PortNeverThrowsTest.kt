package io.github.markusaugust.streamlord.editor

import io.github.markusaugust.streamlord.analysis.CallShapes
import kotlin.test.Test
import kotlin.test.assertEquals

/** The extension asks for diagnostics on every keystroke: no Kotlin source makes the port throw. */
class PortNeverThrowsTest {
    @Test
    fun `no call of a function the analysis looks for makes diagnose throw, however its arguments are written`() {
        val thrown =
            CallShapes.sources(2000, 2).mapNotNull { src ->
                runCatching {
                    for (issue in diagnose(src, true, "data-", arrayOf("A"), arrayOf("count"))) issue.fixes?.forEach { it.text }
                    rewriteAt(src, src.length / 2, src.length / 2, "data-", emptyArray())?.text
                }.exceptionOrNull()?.let { "$it in:\n$src" }
            }
        assertEquals(emptyList(), thrown.take(5).toList())
    }
}
