package io.github.markusaugust.streamlord.core

import io.github.markusaugust.streamlord.core.domain.Position
import io.github.markusaugust.streamlord.core.domain.Trusted
import io.github.markusaugust.streamlord.core.domain.UnsafeInterpolationException
import io.github.markusaugust.streamlord.core.domain.interpolate
import io.github.markusaugust.streamlord.core.domain.landingAsParsed
import io.github.markusaugust.streamlord.core.domain.urlHostFixedBy
import io.github.markusaugust.streamlord.core.domain.urlSchemeFixedBy
import io.github.markusaugust.streamlord.core.domain.valueLandings
import io.github.markusaugust.streamlord.core.json.JsonObject
import io.github.markusaugust.streamlord.core.json.JsonParser
import io.github.markusaugust.streamlord.core.json.JsonString
import io.github.markusaugust.streamlord.fixtures.assertGrowsLinearly
import io.github.markusaugust.streamlord.fixtures.fixtureText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * [valueLandings] and [urlSchemeFixedBy] against `editors/vscode/test/fixtures/cases/value-landings.json`,
 * which the VS Code analysis reads too, and each case against [interpolate] itself, so the editors
 * cannot be held to a position that run time does not act on.
 */
class ValueLandingsTest {
    private val fixture = JsonParser.parseObject(fixtureText("editors/vscode/test/fixtures/cases/value-landings.json"))

    private fun placeholdersOf(markup: String): List<IntRange> = Regex("%s").findAll(markup).map { it.range }.toList()

    // The text of the placeholders whose content the case gives, as a Trusted value's is known.
    private fun knownOf(case: JsonObject): Map<Int, String> =
        case
            .obj("known")
            ?.entries
            ?.associate { (index, text) -> index.toInt() to (text as JsonString).value }
            .orEmpty()

    @Test
    fun `lands each placeholder where the fixture says`() {
        val cases = fixture.array("cases")!!.objects()
        assertTrue(cases.isNotEmpty())
        for (case in cases) {
            val markup = case.string("markup")!!
            val expected =
                case.array("landings")!!.objects().map {
                    Triple(it.string("position"), it.string("attribute"), it.boolean("loadsCode") ?: false)
                }
            val actual =
                valueLandings(
                    markup,
                    placeholdersOf(markup),
                    known = knownOf(case),
                ).map { Triple(it.position.name, it.attribute, it.loadsCode) }
            assertEquals(expected, actual, case.string("name"))
        }
    }

    @Test
    fun `interpolate refuses exactly what the position refuses`() {
        for (case in fixture.array("cases")!!.objects()) {
            val markup = case.string("markup")!!
            val placeholders = placeholdersOf(markup)
            val known = knownOf(case)
            for (index in placeholders.indices) {
                if (index in known) continue
                // Trusted everywhere else, which no position refuses, so only this placeholder decides: the
                // case's own text where it gives one, and a letter, as a placeholder is read, where not.
                val others = placeholders.indices.filter { it != index }.associateWith { known[it] ?: "x" }
                val landing = valueLandings(markup, placeholders, known = others)[index]
                for (probe in listOf<Any>("x", 7, true)) {
                    val values = placeholders.indices.map { if (it == index) probe else Trusted(others.getValue(it)) }
                    // A URL that loads code takes nothing unless the literal before the placeholder fixes its host.
                    val hostFixed =
                        !landing.loadsCode ||
                            urlHostFixedBy(landing.attribute!!, markup.substring(landing.valueStart, placeholders[index].first))
                    val admitted =
                        when (landing.position) {
                            Position.TEXT, Position.ATTRIBUTE -> true
                            Position.URL -> hostFixed
                            Position.DATASTAR -> probe !is String
                            Position.STYLE -> probe is Number
                            Position.STRUCTURE, Position.CODE -> false
                        }
                    val refused =
                        try {
                            interpolate(markup, *values.toTypedArray())
                            null
                        } catch (e: UnsafeInterpolationException) {
                            e
                        }
                    val name = "${case.string("name")}, placeholder $index, $probe"
                    if (admitted && refused != null && refused.index == index) fail("$name: interpolate refused it")
                    if (!admitted && (refused == null || refused.index != index)) fail("$name: interpolate let it in")
                    if (!admitted) assertEquals(landing.position, refused!!.position, name)
                }
            }
        }
    }

    @Test
    fun `gives the start of a value in the markup it was handed, known text or not`() {
        val (name, url) = valueLandings("<a %s=\"x%s\">", listOf(3..4, 8..9), known = mapOf(0 to "href"))
        assertEquals(Position.TEXT, name.position)
        assertEquals(null, name.attribute)
        assertEquals(listOf<Any>(-1, false, -1), listOf(name.valueStart, name.loadsCode, name.tagStart))
        assertEquals(Position.URL, url.position)
        assertEquals(7, url.valueStart)
        // Known text with a quote in it ends the value, so what was a URL is text to the browser.
        assertEquals(Position.TEXT, valueLandings("<a href=\"%s\">", listOf(9..10), known = mapOf(0 to "/x\" title=\"")).single().position)
    }

    @Test
    fun `gives the start of the tag a placeholder lands in, at its opening bracket, or -1 outside a tag`() {
        fun tagStart(markup: String): Int = valueLandings(markup, placeholdersOf(markup)).single().tagStart
        val overLines = "<p>x</p>\n<div\n  id=\"a\"\n  %s>"
        assertEquals(overLines.indexOf("<div"), tagStart(overLines), "an attribute name in a tag that starts lines above")
        val quoted = "<p>x</p><a title=\"%s\">"
        assertEquals(quoted.indexOf("<a"), tagStart(quoted), "a quoted value")
        for (outside in listOf(
            "<p>%s</p>",
            "<!-- <div %s -->",
            "<script>if (a<b) %s</script>",
        )) {
            assertEquals(-1, tagStart(outside), outside)
        }
        // The strictest reading takes a textarea's text as markup too; the browser's reads it as text.
        val textarea = "<textarea><i %s</textarea>"
        assertEquals(-1, landingAsParsed(textarea, placeholdersOf(textarea).single()).tagStart, textarea)
        val markup = "<p>%s</p><a title=\"%s\">"
        val title = valueLandings(markup, placeholdersOf(markup), known = mapOf(0 to "a text longer than its placeholder"))[1]
        assertEquals(markup.indexOf("<a"), title.tagStart, "an offset into the markup as written, after known text")
    }

    @Test
    fun `gives the start of the attribute name a placeholder lands on, as the browser reads names, or -1`() {
        fun nameStart(markup: String): Int = landingAsParsed(markup, placeholdersOf(markup).single()).nameStart
        for ((markup, name) in listOf(
            "<div data-on:click__%s" to "data-on:click__",
            "<div id=x title=\"a b\" data-x='c' hidden data-%s" to "data-",
            "<div a=\"x\"data-%s" to "data-",
            "<div/data-%s" to "data-",
            "<div a = \"x\" data-%s" to "data-",
            "<div\n  id=\"a\"\n  data-on:%s>" to "data-on:",
            "<div =data-%s" to "=data-",
        )) {
            assertEquals(markup.lastIndexOf(name), nameStart(markup), markup)
        }
        // A value after the equals sign and white space, the tag's name, its text: no name.
        for (markup in listOf("<div a= data-%s", "<div a = data-%s", "<div data-%s", "<div title=\"data-%s\">", "<p>data-%s</p>")) {
            assertEquals(-1, nameStart(markup), markup)
        }
    }

    @Test
    fun `knows when the start of a URL settles its scheme`() {
        val urls = fixture.array("urls")!!.objects()
        assertTrue(urls.isNotEmpty())
        for (url in urls) {
            val attribute = url.string("attribute")!!
            val prefix = url.string("prefix")!!
            assertEquals(url.boolean("fixed"), urlSchemeFixedBy(attribute, prefix), "$attribute=\"$prefix…\"")
        }
    }

    @Test
    fun `knows when the start of a code-loading URL fixes its host`() {
        val hosts = fixture.array("hosts")!!.objects()
        assertTrue(hosts.isNotEmpty())
        for (host in hosts) {
            val attribute = host.string("attribute")!!
            val prefix = host.string("prefix")!!
            assertEquals(host.boolean("fixed"), urlHostFixedBy(attribute, prefix), "$attribute=\"$prefix…\"")
        }
    }

    // Each placeholder is looked up among spans pushed in order, so the cost grows with the markup, not
    // with the markup times the placeholders: 15000 placeholders took seconds when every span was tried.
    @Test
    fun `many placeholders are judged in time that grows with the markup`() {
        assertGrowsLinearly("judging placeholders", 80, { n -> "<li title=\"%s\"><a href=\"/x/%s\">%s</a></li>".repeat(n) }) { markup ->
            val placeholders = placeholdersOf(markup)
            valueLandings(markup, placeholders)
            interpolate(markup, *Array(placeholders.size) { "v" })
        }
    }
}
