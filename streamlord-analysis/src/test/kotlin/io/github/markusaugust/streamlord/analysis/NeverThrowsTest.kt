package io.github.markusaugust.streamlord.analysis

import kotlin.test.Test
import kotlin.test.assertEquals

/** No source makes the analysis throw: a call it cannot read is one it says nothing of. */
class NeverThrowsTest {
    private val analyzer = Analyzer()

    @Test
    fun `a helper whose name is given by name and is no literal is left alone`() {
        val src = "fun f() = div { dataAttr(name = \"tab-index\".uppercase(), expression = \"0\") }"
        assertEquals(emptyList(), analyzer.analyzeKotlin(src).filter { it.code == "attribute-case" })
    }
}
