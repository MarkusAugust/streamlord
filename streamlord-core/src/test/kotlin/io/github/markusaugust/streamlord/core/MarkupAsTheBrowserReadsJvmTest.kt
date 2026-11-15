package io.github.markusaugust.streamlord.core

import io.github.markusaugust.streamlord.core.application.Streamlord
import io.github.markusaugust.streamlord.core.domain.ElementNamespace
import io.github.markusaugust.streamlord.core.domain.InterpolatedExpressionException
import io.github.markusaugust.streamlord.core.domain.PatchElements
import kotlin.test.Test
import kotlin.test.assertFailsWith

class MarkupAsTheBrowserReadsJvmTest {
    @Test
    fun `the guard reads a patch with an SVG namespace as svg`() {
        val streamlord = Streamlord(guardElements = true)
        assertFailsWith<InterpolatedExpressionException> {
            streamlord.guard(PatchElements("""<title><b data-text="++"></b></title>""", namespace = ElementNamespace.SVG))
        }
    }
}
