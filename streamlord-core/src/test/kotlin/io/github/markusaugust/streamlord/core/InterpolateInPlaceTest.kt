package io.github.markusaugust.streamlord.core

import io.github.markusaugust.streamlord.core.domain.Position
import io.github.markusaugust.streamlord.core.domain.Trusted
import io.github.markusaugust.streamlord.core.domain.UnsafeInterpolationException
import io.github.markusaugust.streamlord.core.domain.interpolate
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The block form of interpolate: each value marked with `+` where it stands, and judged there as a `%s` is. */
class InterpolateInPlaceTest {
    private val hostile = """Gorvek" onmouseover="alert(1)" x="<script>alert(1)</script>"""

    @Test
    fun `a marked value is written as the placeholder form writes it`() {
        val id = "a&b"
        assertEquals(
            interpolate("""<p id="%s" title="%s">%s</p>""", id, hostile, hostile),
            interpolate { """<p id="${+id}" title="${+hostile}">${+hostile}</p>""" },
        )
    }

    @Test
    fun `a marked value is refused where a placeholder is`() {
        val refused =
            mapOf<Position, () -> Any>(
                Position.URL to { interpolate { """<a href="${+"javascript:alert(1)"}">x</a>""" } },
                Position.DATASTAR to { interpolate { """<p data-text="${+"name"}"></p>""" } },
                Position.STYLE to { interpolate { """<p style="${+"color: red"}"></p>""" } },
                Position.CODE to { interpolate { """<script>${+"alert(1)"}</script>""" } },
                Position.STRUCTURE to { interpolate { """<p title=${+"x"}></p>""" } },
            )
        for ((position, write) in refused) {
            assertEquals(position, assertFailsWith<UnsafeInterpolationException> { write() }.position, position.name)
        }
    }

    @Test
    fun `a URL is judged whole, the marked values and the markup together`() {
        assertEquals("""<a href="/heads/1?a=1&amp;b=2">x</a>""", interpolate { """<a href="/heads/${+"1?a=1&b=2"}">x</a>""" }.text)
        assertFailsWith<UnsafeInterpolationException> { interpolate { """<a href="${+"java"}${+"script:alert(1)"}">x</a>""" } }
    }

    @Test
    fun `markup that was built and markup that is vouched for stand as they are`() {
        val row = interpolate { "<li>${+"a<b"}</li>" }
        assertEquals("<ul><li>a&lt;b</li></ul>", interpolate { "<ul>${+row}</ul>" }.text)
        assertEquals("<style>a > b { color: red }</style>", interpolate { "<style>${+Trusted("a > b { color: red }")}</style>" }.text)
        // In an attribute markup is only characters.
        assertEquals("""<p title="&lt;li&gt;a&amp;lt;b&lt;/li&gt;"></p>""", interpolate { """<p title="${+row}"></p>""" }.text)
    }

    @Test
    fun `a number and a boolean stand where a string is refused, marked or not`() {
        val count = 3
        val open = true
        assertEquals(
            """<p data-signals="{count: 3, open: true}" style="width: 3px"></p>""",
            interpolate { """<p data-signals="{count: ${+count}, open: ${+open}}" style="width: ${count}px"></p>""" }.text,
        )
    }

    @Test
    fun `null writes nothing`() {
        val missing: String? = null
        assertEquals("""<p title=""></p>""", interpolate { """<p title="${+missing}">${+missing}</p>""" }.text)
    }

    @Test
    fun `a percent sign of the markup is one of the markup`() {
        assertEquals(
            """<p style="width: 50%" title="%s %% a%20b">100%</p>""",
            interpolate { """<p style="width: 50%" title="%s %% a%20b">${+"100%"}</p>""" }.text,
        )
    }

    @Test
    fun `one block holds a loop, each row's value where it stands`() {
        val names = listOf("Gorvek", "<Merigall>")
        assertEquals(
            "<ul><li>Gorvek</li><li>&lt;Merigall&gt;</li></ul>",
            interpolate { "<ul>${names.joinToString("") { "<li>${+it}</li>" }}</ul>" }.text,
        )
    }

    @Test
    fun `a mark written twice is the value twice, and one never written is no value`() {
        assertEquals(
            "<p>a&amp;b a&amp;b</p>",
            interpolate {
                val twice = +"a&b"
                +"never written"
                "<p>$twice $twice</p>"
            }.text,
        )
    }

    @Test
    fun `the markup is trimmed before the values are in, so a value of many lines moves nothing`() {
        val lines = "one\n          two"
        assertEquals(
            "<div>\n    <p>one\n          two</p>\n</div>",
            interpolate {
                """
                <div>
                    <p>${+lines}</p>
                </div>
                """.trimIndent()
            }.text,
        )
    }

    @Test
    fun `a value cannot stand in for another by holding what a mark writes`() {
        val secret = "s3cret"
        val forged = "﷐0﷑"
        val out = interpolate { """<p data-id="${+secret}">${+forged}</p>""" }.text
        assertEquals(1, Regex("s3cret").findAll(out).count(), out)
    }

    @Test
    fun `an aliased bundle's attributes are refused with its prefixes`() {
        assertFailsWith<UnsafeInterpolationException> { interpolate(listOf("data-x-")) { """<p data-x-text="${+"name"}"></p>""" } }
        assertEquals("""<p data-x-text="name"></p>""", interpolate { """<p data-x-text="${+"name"}"></p>""" }.text)
    }

    @Test
    fun `a block may suspend`() {
        suspend fun name(): String = "<b>"
        var out = ""
        suspend { interpolate { "<p>${+name()}</p>" }.text }.startCoroutine(Continuation(EmptyCoroutineContext) { out = it.getOrThrow() })
        assertEquals("<p>&lt;b&gt;</p>", out)
    }
}
