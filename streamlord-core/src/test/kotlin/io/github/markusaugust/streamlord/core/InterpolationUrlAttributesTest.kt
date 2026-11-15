package io.github.markusaugust.streamlord.core

import io.github.markusaugust.streamlord.core.domain.UnsafeInterpolationException
import io.github.markusaugust.streamlord.core.domain.interpolate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Every URL attribute the Strings page names takes a safe URL and refuses `javascript:`, as the page says. */
class InterpolationUrlAttributesTest {
    @Test
    fun `each URL attribute the strings page names refuses a URL that runs`() {
        val formats =
            listOf(
                """<a href="%s">x</a>""",
                """<img src="%s">""",
                """<form action="%s"></form>""",
                """<button formaction="%s">x</button>""",
                """<svg><a xlink:href="%s"><text>x</text></a></svg>""",
                """<video poster="%s"></video>""",
                """<q cite="%s">x</q>""",
                """<blockquote cite="%s">x</blockquote>""",
                """<table background="%s"></table>""",
                """<a href="/x" ping="%s">x</a>""",
                """<html manifest="%s"></html>""",
                """<svg><animate attributeName="href" values="%s"/><set attributeName="href" to="%s"/></svg>""",
                """<svg><animate attributeName="href" from="%s"/></svg>""",
            )
        for (format in formats) {
            val count = Regex("%s").findAll(format).count()
            assertEquals(format.replace("%s", "/heads/1"), interpolate(format, *Array(count) { "/heads/1" }).text, format)
            assertFailsWith<UnsafeInterpolationException>(format) { interpolate(format, *Array(count) { "javascript:alert(1)" }) }
        }
    }
}
