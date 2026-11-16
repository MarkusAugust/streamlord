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
private const val MARK_OPENS = '\uFDD0'
private const val MARK_CLOSES = '\uFDD1'
private const val REPLACED = '\uFFFD'

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
        // Nor does the page carry what a mark is made of.
        assertEquals(false, '\uFDD0' in out || '\uFDD1' in out, out)
    }

    @Test
    fun `a value marked and marked again is marked once`() {
        assertEquals(
            "<p>a&amp;b a&amp;b</p>",
            interpolate {
                val marked = +"a&b"
                "<p>${+marked} ${+(+"a&b")}</p>"
            }.text,
        )
    }

    @Test
    fun `a dozen marks are a dozen values, each in its place`() {
        val names = (0 until 12).map { "n$it" }
        assertEquals(names.joinToString("") { "<i>$it</i>" }, interpolate { names.joinToString("") { "<i>${+it}</i>" } }.text)
    }

    @Test
    fun `a mark is its own block's alone`() {
        // Written in a block inside the one that made it: no value of either block is pulled in by it.
        val nested =
            interpolate {
                val title = +"outer"
                val row = interpolate { "<li>$title ${+"inner"}</li>" }
                "<ul>$row</ul>"
            }.text
        assertEquals(true, "inner" in nested && "outer" !in nested, nested)
        assertEquals(false, MARK_OPENS in nested || MARK_CLOSES in nested, nested)
        assertEquals(true, REPLACED in nested, nested)
        // Kept past its block and written in the next.
        var kept = ""
        interpolate { "<p>${+"first"}</p>".also { kept = it } }
        val next = interpolate { "$kept<i>${+"second"}</i>" }.text
        assertEquals(true, next.endsWith("<i>second</i>") && "first" !in next, next)
        assertEquals(false, MARK_OPENS in next || MARK_CLOSES in next, next)
    }

    @Test
    fun `what is not a mark of the block is never read as one, and the page carries no character of a mark`() {
        // A string cut or changed after its values were marked, and a mark the markup makes up.
        val shapes =
            listOf(
                interpolate { "<p>${+"value"}</p>".take(5) }.text,
                interpolate { "<p>${+"value"}</p>".reversed() }.text,
                interpolate { "<p>${+"value"}</p>".uppercase() }.text,
                interpolate { "<p>${MARK_OPENS}0$MARK_CLOSES</p>${+"value"}" }.text,
                interpolate { "<p>$MARK_CLOSES</p>" }.text,
            )
        for (out in shapes) {
            assertEquals(false, MARK_OPENS in out || MARK_CLOSES in out, out)
            assertEquals(true, REPLACED in out, out)
        }
        assertEquals("<p>${REPLACED}0$REPLACED</p>value", shapes[3])
        assertEquals("<p>$REPLACED</p>", shapes[4])
    }

    @Test
    fun `text another escaper wrote into the block does not stop the page, whatever it holds`() {
        // As kotlinx.html or the author's own escaper leaves text from outside: escaped, and with the two characters in it.
        val stored = "a${MARK_OPENS}b${MARK_CLOSES}c ${MARK_OPENS}0.0$MARK_CLOSES"
        val out = interpolate { "<li>$stored</li><li>${+"marked"}</li>" }.text
        assertEquals("<li>a${REPLACED}b${REPLACED}c ${REPLACED}0.0$REPLACED</li><li>marked</li>", out)
    }

    @Test
    fun `markup that was built holds no mark, whatever its values held, so it pulls no value where it is written unmarked`() {
        val secret = "s3cret"
        val forged = "﷐0﷑"
        for (comment in listOf(interpolate("<li>%s</li>", forged), interpolate { "<li>${+forged}</li>" })) {
            val out = interpolate { """<input type="hidden" value="${+secret}"><ul>$comment</ul>""" }.text
            assertEquals("""<input type="hidden" value="s3cret"><ul><li>�0�</li></ul>""", out)
        }
    }

    @Test
    fun `a refusal counts the marks as they stand in the markup`() {
        val refused =
            assertFailsWith<UnsafeInterpolationException> {
                interpolate {
                    val first = +"first"
                    val second = +"second"
                    """<p data-text="$second">$first</p>"""
                }
            }
        assertEquals(Position.DATASTAR to 0, refused.position to refused.index)
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
