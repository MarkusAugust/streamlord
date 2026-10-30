package io.github.markusaugust.streamlord.core

import io.github.markusaugust.streamlord.core.domain.Position
import io.github.markusaugust.streamlord.core.domain.Trusted
import io.github.markusaugust.streamlord.core.domain.UnsafeInterpolationException
import io.github.markusaugust.streamlord.core.domain.holeLandings
import io.github.markusaugust.streamlord.core.domain.interpolate
import io.github.markusaugust.streamlord.core.domain.urlSchemeFixedBy
import io.github.markusaugust.streamlord.core.json.JsonParser
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * [holeLandings] and [urlSchemeFixedBy] against `editors/vscode/test/fixtures/twins/hole-landings.json`,
 * which the VS Code analysis reads too, and each case against [interpolate] itself, so the editors
 * cannot be held to a position that run time does not act on.
 */
class HoleLandingsTest {
    private val fixture = JsonParser.parseObject(File("../editors/vscode/test/fixtures/twins/hole-landings.json").readText())

    private fun holesOf(markup: String): List<IntRange> = Regex("%s").findAll(markup).map { it.range }.toList()

    @Test
    fun `lands each hole where the fixture says`() {
        val cases = fixture.array("cases")!!.objects()
        assertTrue(cases.isNotEmpty())
        for (case in cases) {
            val markup = case.string("markup")!!
            val expected = case.array("landings")!!.objects().map { it.string("position") to it.string("attribute") }
            val actual = holeLandings(markup, holesOf(markup)).map { it.position.name to it.attribute }
            assertEquals(expected, actual, case.string("name"))
        }
    }

    @Test
    fun `interpolate refuses exactly what the position refuses`() {
        for (case in fixture.array("cases")!!.objects()) {
            val markup = case.string("markup")!!
            val landings = holeLandings(markup, holesOf(markup))
            for ((index, landing) in landings.withIndex()) {
                for (probe in listOf<Any>("x", 7, true)) {
                    // Trusted everywhere else, which no position refuses, so only this hole decides.
                    val values = landings.indices.map { if (it == index) probe else Trusted("x") }
                    val admitted =
                        when (landing.position) {
                            Position.TEXT, Position.ATTRIBUTE, Position.URL -> true
                            Position.DATASTAR -> probe !is String
                            Position.STYLE -> probe is Number
                            Position.STRUCTURE, Position.CODE -> false
                        }
                    val refused =
                        try {
                            interpolate(markup, values)
                            null
                        } catch (e: UnsafeInterpolationException) {
                            e
                        }
                    val name = "${case.string("name")}, hole $index, $probe"
                    if (admitted && refused != null && refused.index == index) fail("$name: interpolate refused it")
                    if (!admitted && (refused == null || refused.index != index)) fail("$name: interpolate let it in")
                    if (!admitted) assertEquals(landing.position, refused!!.position, name)
                }
            }
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
}
