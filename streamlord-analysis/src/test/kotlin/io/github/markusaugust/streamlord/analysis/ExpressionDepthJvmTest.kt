package io.github.markusaugust.streamlord.analysis

import kotlin.test.Test
import kotlin.test.assertTrue

/** How deep an expression is judged on a thread of a fixed stack, which only the JVM lets a test choose. */
class ExpressionDepthJvmTest {
    private val analyzer = Analyzer()

    // As deep as the readers before the parser fed them read, on a thread of 1 MB.
    @Test
    fun `nested actions and options objects as deep as before`() {
        var said = emptyList<String>()
        val nested = "@get(".repeat(600) + "1" + ")".repeat(600)
        val options = "@get('/x', {headers: " + "{a: ".repeat(400) + "1" + "}".repeat(400) + ", retyr: 1})"
        val thread =
            Thread(null, {
                said = listOf(nested, options).flatMap { v -> analyzer.analyzeHtml("<div data-on:click=\"$v\"></div>").map { it.message } }
            }, "deep", 1L shl 20)
        thread.start()
        thread.join()
        assertTrue(said.none { "stack" in it }, said.toString())
        assertTrue(said.any { "has no option retyr" in it }, said.toString())
    }
}
