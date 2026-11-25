package io.github.markusaugust.streamlord.core

import io.github.markusaugust.streamlord.core.domain.Position
import io.github.markusaugust.streamlord.core.domain.Trusted
import io.github.markusaugust.streamlord.core.domain.UnsafeInterpolationException
import io.github.markusaugust.streamlord.core.domain.interpolate
import io.github.markusaugust.streamlord.core.domain.joinToHtml
import io.github.markusaugust.streamlord.fixtures.assertGrowsLinearly
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The block form of interpolate: each value marked with `+` where it stands, and judged there as a `%s` is. */
private const val MARK_OPENS = '\uFDD0'
private const val MARK_CLOSES = '\uFDD1'
private const val MARK_IN_HTML = '\uFDD2'
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
                interpolate { "<p>${MARK_IN_HTML}0$MARK_CLOSES</p>${+"a${MARK_IN_HTML}b"}" }.text,
                interpolate("<p>%s</p>", "a${MARK_IN_HTML}b").text,
            )
        for (out in shapes) {
            assertEquals(false, MARK_OPENS in out || MARK_IN_HTML in out || MARK_CLOSES in out, out)
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
    fun `a value holding a mark of a block being built is refused where it would be escaped, since its value would be lost`() {
        val name = "Gorvek"
        val refused =
            listOf<() -> Any>(
                { interpolate { "<p>${interpolate("<b>%s</b>", +name)}</p>" } },
                {
                    interpolate {
                        val h = interpolate("<b title=\"%s\">b</b>", +name)
                        "<p>$h</p>"
                    }
                },
                { interpolate { "<p>${interpolate("<i title=\"%s\">i</i>", interpolate("<b>${+name}</b>"))}</p>" } },
                { interpolate { "<p>${interpolate("<b>%s</b>", "Hi ${+name}")}</p>" } },
                { interpolate { "<p>${interpolate("<b>%s</b>", listOf(+name))}</p>" } },
                { interpolate { "<p>${+"Hi ${+name}"}</p>" } },
                // A block's mark in the placeholder form of a block inside it.
                {
                    interpolate {
                        val n = +name
                        interpolate { "<p>${interpolate("<b>%s</b>", n)}</p>" }.text
                    }
                },
            )
        for (shape in refused) {
            val e = assertFailsWith<IllegalArgumentException> { shape() }
            assertEquals(true, "+" in e.message.orEmpty() && "lost" in e.message.orEmpty(), e.message)
        }
        // Where it would not be escaped, it is refused as any value there.
        assertEquals(
            Position.CODE,
            assertFailsWith<UnsafeInterpolationException> {
                interpolate { "<p>${interpolate("<b onclick=\"%s\">b</b>", +name)}</p>" }
            }.position,
        )
        assertEquals(
            Position.URL,
            assertFailsWith<UnsafeInterpolationException> {
                interpolate { "<p>${interpolate("<a href=\"%s\">x</a>", "javascript:${+name}")}</p>" }
            }.position,
        )
        assertEquals(
            "Value 1 holds a mark",
            assertFailsWith<IllegalArgumentException> { interpolate { interpolate("<b>%s %s</b>", "a", +name).text } }.message?.take(20),
        )
    }

    @Test
    fun `a value holding what a mark writes stops no page when no block being built made it`() {
        // Made up, or kept past the block that made it, finished or failed.
        var kept = ""
        interpolate { "<p>${+"first"}</p>".also { kept = it } }
        var failed = ""
        runCatching<Unit> {
            interpolate {
                failed = "${+"x"}"
                error("the block fails")
            }
        }
        for (text in listOf("${MARK_OPENS}0.0$MARK_CLOSES", "${MARK_IN_HTML}abc.0$MARK_CLOSES", kept, failed)) {
            assertEquals(false, MARK_OPENS in interpolate("""<p title="%s">%s</p>""", text, text).text)
            assertEquals(false, MARK_OPENS in interpolate { "<p>${+text}</p>" }.text)
        }
    }

    @Test
    fun `a value holding what opens a mark costs what its length does, while a block is being built`() {
        val openings = listOf("$MARK_OPENS", "$MARK_IN_HTML$MARK_CLOSES", "${MARK_OPENS}a$MARK_CLOSES")
        for (opening in openings) {
            assertGrowsLinearly("a value of stray openings", 2_000, { n -> opening.repeat(n) }) { text ->
                interpolate { "<p title=\"${+text}\">${+text}</p>" }
            }
        }
    }

    @Test
    fun `a value marked in the text of a Trusted is the block's, whether the Trusted is marked or not`() {
        val name = "<script>"
        val expected = "<li>Hi <i>&lt;script&gt;</i></li>"
        assertEquals(expected, interpolate { "<li>${Trusted("Hi <i>${+name}</i>")}</li>" }.text)
        assertEquals(expected, interpolate { "<li>${+Trusted("Hi <i>${+name}</i>")}</li>" }.text)
        assertEquals(
            expected,
            interpolate {
                val t = Trusted("Hi <i>${+name}</i>")
                "<li>${+t}</li>"
            }.text,
        )
        // A Trusted made in another block holds no mark of this one.
        var elsewhere = Trusted("")
        interpolate {
            elsewhere = Trusted("Hi <i>${+name}</i>")
            ""
        }
        val out = interpolate { "<li>${+elsewhere}</li>" }.text
        assertEquals(true, out.startsWith("<li>Hi <i>$REPLACED") && "script" !in out && MARK_OPENS !in out, out)
    }

    @Test
    fun `a Trusted holds no mark of its own or of a value marked after it, and one inside many is read without overflowing the stack`() {
        // Its own mark, made up from what a mark writes, is no mark of the block: it is read once, as text.
        val own =
            interpolate {
                val mark = (+"a").toString()
                "<p>${+Trusted("x" + mark.substringBeforeLast('.') + ".1" + MARK_CLOSES)}</p>"
            }.text
        assertEquals(true, own.startsWith("<p>x$REPLACED") && MARK_OPENS !in own && MARK_CLOSES !in own, own)
        // Nor one of a value marked after it.
        val later =
            interpolate {
                val mark = (+"a").toString()
                val t = Trusted("y" + mark.substringBeforeLast('.') + ".2" + MARK_CLOSES)
                "<p>${+t}${+"b"}</p>"
            }.text
        assertEquals(true, later.startsWith("<p>y$REPLACED") && later.endsWith("b</p>") && MARK_OPENS !in later, later)
        val deep =
            interpolate {
                var t = Trusted("<b>${+"<i>"}</b>")
                repeat(20_000) { t = Trusted("${+t}") }
                "${+t}"
            }.text
        assertEquals("<b>&lt;i&gt;</b>", deep)
    }

    @Test
    fun `a refusal counts the marks as they stand in the markup, a marked Trusted's and those in its text among them`() {
        val refused = assertFailsWith<UnsafeInterpolationException> { interpolate { """${+Trusted("<b>")}<a onclick="${+"x"}">""" } }
        assertEquals(Position.CODE to 1, refused.position to refused.index)
        val inside = assertFailsWith<UnsafeInterpolationException> { interpolate { """${+"a"}${+Trusted("<a onclick=\"${+"x"}\">")}""" } }
        assertEquals(Position.CODE to 2, inside.position to inside.index)
        // The placeholder form counts a Trusted as a value, so the two forms name the same value.
        val placed = assertFailsWith<UnsafeInterpolationException> { interpolate("""%s<a onclick="%s">""", Trusted("<b>"), "x") }
        assertEquals(refused.index, placed.index)
    }

    @Test
    fun `a value marked in a Trusted handed to the placeholder form in the block is the block's, the Html marked or not`() {
        val name = "<script>"
        val expected = "<li><b><i>&lt;script&gt;</i></b></li>"
        assertEquals(expected, interpolate { "<li>${interpolate("<b>%s</b>", Trusted("<i>${+name}</i>"))}</li>" }.text)
        assertEquals(expected, interpolate { "<li>${+interpolate("<b>%s</b>", Trusted("<i>${+name}</i>"))}</li>" }.text)
        // An Html of another block holds no mark of this one, and one with none is written as it was.
        var elsewhere = interpolate("")
        interpolate {
            elsewhere = interpolate("<b>%s</b>", Trusted("<i>${+name}</i>"))
            ""
        }
        val out = interpolate { "<li>${+elsewhere}</li>" }.text
        assertEquals(true, out.startsWith("<li><b><i>$REPLACED") && "script" !in out && MARK_OPENS !in out, out)
        assertEquals("<li><b>a &lt;b&gt;</b></li>", interpolate { "<li>${+interpolate("<b>%s</b>", "a <b>")}</li>" }.text)
    }

    @Test
    fun `a marked Html holding the block's marks keeps the rules of an Html, its values escaped where they land in it`() {
        val n = "<x>"
        // In an attribute an Html is characters, and one that does not close what it opens is escaped like a string.
        assertEquals(
            """<a title="&lt;i class=&quot;c&quot;&gt;&amp;lt;x&amp;gt;&lt;/i&gt;">t</a>""",
            interpolate { "<a title=\"${+interpolate("<i class=\"c\">%s</i>", Trusted("${+n}"))}\">t</a>" }.text,
        )
        assertEquals("<p>&lt;b&gt;&amp;lt;x&amp;gt;</p>", interpolate { "<p>${+interpolate("<b>%s", Trusted("${+n}"))}</p>" }.text)
        // A value outside its element text is refused, counted where its mark stands.
        val refused =
            assertFailsWith<UnsafeInterpolationException> {
                interpolate { "<p>${+"a"}${+interpolate("%s", Trusted("<b onclick=\"${+n}\">x</b>"))}</p>" }
            }
        assertEquals(Position.CODE to 2, refused.position to refused.index)
        // An Html inside thousands is read without overflowing the stack.
        val deep =
            interpolate {
                var h = interpolate("<b>%s</b>", Trusted("${+n}"))
                repeat(20_000) { h = interpolate("%s", Trusted("${+h}")) }
                "${+h}"
            }.text
        assertEquals("<b>&lt;x&gt;</b>", deep)
    }

    @Test
    fun `an Html holding the block's marks is judged where it lands as any Html is, and a Trusted marked in it is text`() {
        val user = "');alert(1);('"
        val js = "javascript:alert(1)"
        val refusals =
            listOf(
                Position.DATASTAR to {
                    interpolate {
                        "<button data-on:click=\"@get('/x?q=${+interpolate("%s%s", user, Trusted("${+""}"))}')\">b</button>"
                    }
                },
                Position.URL to { interpolate { "<a href=\"${+interpolate("%s%s", js, Trusted("${+""}"))}\">x</a>" } },
                Position.CODE to { interpolate { "<script>${+interpolate("%s%s", "alert(1)", Trusted("${+""}"))}</script>" } },
                Position.STYLE to { interpolate { "<p style=\"${+interpolate("%s%s", "color: red", Trusted("${+""}"))}\">x</p>" } },
            )
        for ((position, page) in refusals) {
            val refused = assertFailsWith<UnsafeInterpolationException>(position.name) { page() }
            assertEquals(position, refused.position)
        }
        // The placeholder form judged its values with the mark unread, so markup a mark would bring there could move them:
        // a Trusted marked in the Html is written as text, escaped where it lands.
        assertEquals(
            "<div>&lt;script&gt;alert(1)//</div>",
            interpolate { "<div>${+interpolate("%s%s", Trusted("${+Trusted("<script>")}"), "alert(1)//")}</div>" }.text,
        )
    }

    @Test
    fun `a value marked in an Html lands in its element text alone, since the Html's prefixes are not the block's to know`() {
        val user = "');alert(1);('"
        val pages =
            listOf(
                { interpolate { "<p>${+interpolate(listOf("x-"), "<b x-on:click=\"%s\">b</b>", Trusted("${+user}"))}</p>" } },
                { interpolate(listOf("x-")) { "<p>${+interpolate("<b data-on:click=\"%s\">b</b>", Trusted("${+user}"))}</p>" } },
                { interpolate { "<p>${+interpolate("<b title=\"%s\">b</b>", Trusted("${+user}"))}</p>" } },
            )
        for (page in pages) {
            val refused = assertFailsWith<UnsafeInterpolationException> { page() }
            assertEquals(Position.STRUCTURE, refused.position)
            // It names why and what works: the value handed to that placeholder form as one of its own.
            val message = refused.message.orEmpty()
            assertEquals(true, "prefixes" in message && "placeholder form" in message && "Trusted" !in message, message)
        }
        // A tag name keeps its own message.
        val tag = assertFailsWith<UnsafeInterpolationException> { interpolate("<%s>x", "b") }.message.orEmpty()
        assertEquals(true, "Html" !in tag, tag)
    }

    @Test
    fun `an Html written unmarked keeps the rules of one marked, so no mark in it moves the values it was judged with`() {
        val user = "');alert(1);('"
        // The placeholder form escaped the second value for element text: markup a mark brings cannot move it into a script.
        assertEquals(
            "<div>&lt;script&gt;alert(1)//</div>",
            interpolate { "<div>${interpolate("%s%s", Trusted("${+Trusted("<script>")}"), "alert(1)//")}</div>" }.text,
        )
        assertEquals(
            "<div>&lt;script&gt;alert(1)//</div>",
            interpolate { "<div>${interpolate("${+Trusted("<script>")}%s", "alert(1)//")}</div>" }.text,
        )
        // Nor may a value marked in it land anywhere but its element text, since its prefixes are not the block's to know.
        val pages =
            listOf(
                { interpolate { "<p>${interpolate(listOf("x-"), "<b x-on:click=\"%s\">b</b>", Trusted("${+user}"))}</p>" } },
                { interpolate { "<p>${interpolate("<b x-on:click=\"${+user}\">%s</b>", "b")}</p>" } },
                { interpolate { "<p>${listOf(interpolate("<b title=\"${+user}\">b</b>")).joinToHtml()}</p>" } },
            )
        for (page in pages) {
            val refused = assertFailsWith<UnsafeInterpolationException> { page() }
            assertEquals(Position.STRUCTURE, refused.position)
            val message = refused.message.orEmpty()
            assertEquals(true, "prefixes" in message && "placeholder form built" in message, message)
        }
        // A Trusted marked there is markup where an Html would be, the block's marks in it in element text alone too, and
        // counted where they stand.
        assertEquals(
            "<p><b><i>&lt;n&gt;</i></b></p>",
            interpolate { "<p>${interpolate("<b>${+Trusted("<i>${+"<n>"}</i>")}</b>")}</p>" }.text,
        )
        assertFailsWith<UnsafeInterpolationException> {
            interpolate { "<p>${interpolate("<b>${+Trusted("<i title=\"${+"n"}\">i</i>")}</b>")}</p>" }
        }
        val counted =
            assertFailsWith<UnsafeInterpolationException> {
                interpolate { "<p>${interpolate("${+Trusted("${+"a"}")}<b title=\"${+"t"}\">b</b>")}</p>" }
            }
        assertEquals(Position.STRUCTURE to 2, counted.position to counted.index)
        // Where no value is let in, the refusal says so as it would of any value.
        val script = assertFailsWith<UnsafeInterpolationException> { interpolate { "<p>${interpolate("<script>${+"x"}</script>")}</p>" } }
        assertEquals(Position.CODE, script.position)
        // In its element text a value is escaped, and an Html is written as any Html is.
        assertEquals(
            "<ul><li>&lt;x&gt;</li><li><b>b</b></li></ul>",
            interpolate {
                "<ul>${listOf(interpolate("<li>${+"<x>"}</li>"), interpolate("<li>${+interpolate("<b>b</b>")}</li>")).joinToHtml()}</ul>"
            }.text,
        )
    }

    @Test
    fun `a marked Trusted writes the characters of a mark it holds no mark of as U+FFFD, and its per cent signs as they are`() {
        assertEquals("<p>a${REPLACED}b$REPLACED</p>", interpolate { "<p>${+Trusted("a${MARK_OPENS}b$MARK_CLOSES")}</p>" }.text)
        assertEquals("<p>100% %s</p>", interpolate { "<p>${+Trusted("100% %s")}</p>" }.text)
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
