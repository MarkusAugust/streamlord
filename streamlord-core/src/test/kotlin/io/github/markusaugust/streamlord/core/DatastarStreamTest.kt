package io.github.markusaugust.streamlord.core

import io.github.markusaugust.streamlord.core.application.Streamlord
import io.github.markusaugust.streamlord.core.domain.ElementPatchMode
import io.github.markusaugust.streamlord.core.domain.PatchElements
import io.github.markusaugust.streamlord.core.domain.PatchSignals
import io.github.markusaugust.streamlord.core.domain.SignalNames
import io.github.markusaugust.streamlord.core.domain.interpolate
import io.github.markusaugust.streamlord.core.port.driven.BufferedSseSink
import io.github.markusaugust.streamlord.core.port.driven.SseSink
import io.github.markusaugust.streamlord.core.port.driving.patchSignals
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DatastarStreamTest {
    @Test
    fun `markup interpolate built is patched without an import of its own`() =
        runTest {
            val sink = BufferedSseSink()
            val stream = Streamlord.Default.stream(sink)

            stream.patchElements(interpolate("""<li id="hit">%s</li>""", "<b>"), selector = "#feed", mode = ElementPatchMode.APPEND)

            assertEquals(
                """
            |event: datastar-patch-elements
            |data: selector #feed
            |data: mode append
            |data: elements <li id="hit">&lt;b&gt;</li>
            |
            |
                """.trimMargin(),
                sink.text(),
            )
        }

    @Test
    fun `every event is flushed and written in call order`() =
        runTest {
            val sink = BufferedSseSink()
            val stream = Streamlord.Default.stream(sink)

            stream.patchElements("""<div id="a">1</div>""")
            stream.patchSignals("count" to 1, "gone" to null)
            stream.removeElements("#old")
            stream.removeSignals("x", "y")
            stream.redirect("/hall?of=kings&x='1'")
            stream.comment()

            assertEquals(
                """
            |event: datastar-patch-elements
            |data: elements <div id="a">1</div>
            |
            |event: datastar-patch-signals
            |data: signals {"count":1,"gone":null}
            |
            |event: datastar-patch-elements
            |data: selector #old
            |data: mode remove
            |
            |event: datastar-patch-signals
            |data: signals {"x":null,"y":null}
            |
            |event: datastar-patch-elements
            |data: selector body
            |data: mode append
            |data: elements <script data-effect="el.remove()">setTimeout(() => { window.location.href = "/hall?of=kings&x='1'" })</script>
            |
            |: keep-alive
            |
            |
                """.trimMargin(),
                sink.text(),
            )
            assertEquals(6, sink.flushCount)
        }

    @Test
    fun `typed patchSignals uses the codec for maps without an adapter`() =
        runTest {
            val sink = BufferedSseSink()
            val stream = Streamlord.Default.stream(sink)
            stream.patchSignals(mapOf("user" to mapOf("name" to "Gorvek")))
            assertEquals("event: datastar-patch-signals\ndata: signals {\"user\":{\"name\":\"Gorvek\"}}\n\n", sink.text())
        }

    // The client stores "form.email" as one key that $form.email does not reach, so a dotted name nests.
    @Test
    fun `a dotted name in the pairs reaches a nested signal`() =
        runTest {
            val sink = BufferedSseSink()
            val stream = Streamlord.Default.stream(sink)
            stream.patchSignals("form.email" to "g@x", "form.name" to "G", "form" to mapOf("age" to 3), "n" to 1)
            stream.removeSignals("form.email", "n")
            stream.removeSignals("x.y", "x", "x")
            assertEquals(
                "event: datastar-patch-signals\ndata: signals {\"form\":{\"email\":\"g@x\",\"name\":\"G\",\"age\":3},\"n\":1}\n\n" +
                    "event: datastar-patch-signals\ndata: signals {\"form\":{\"email\":null},\"n\":null}\n\n" +
                    "event: datastar-patch-signals\ndata: signals {\"x\":null}\n\n",
                sink.text(),
            )
            val refused =
                listOf(
                    arrayOf("a" to 1, "a.b" to 2),
                    arrayOf("a" to 1, "a" to 2),
                    arrayOf("constructor.prototype.x" to 1),
                    arrayOf("toString.name" to 1),
                    arrayOf("constructor" to 1),
                    arrayOf("a..b" to 1),
                    arrayOf("a." to 1),
                )
            for (pairs in refused) {
                assertFailsWith<DatastarEventValidationException>(pairs.joinToString()) { stream.patchSignals(*pairs) }
            }
            val clash = assertFailsWith<DatastarEventValidationException> { stream.patchSignals("n" to 1, "a" to 1, "a.b" to 2) }
            assertEquals("signal a is given a value and also names signals under it", clash.message)
        }

    // dataSignals("foo-bar" to 1) would be $fooBar on the page and this the signal foo-bar, so neither takes a hyphen.
    @Test
    fun `a name with a hyphen is refused, and a key with one inside a value is data`() =
        runTest {
            val sink = BufferedSseSink()
            val stream = Streamlord.Default.stream(sink)
            val patch = assertFailsWith<DatastarEventValidationException> { stream.patchSignals("form.first-name" to "") }
            assertEquals(
                "signal form.first-name has a hyphen, which Datastar's client reads as a capital in an attribute key and as a " +
                    "hyphen in an expression or a patch, so it would name two signals; write form.firstName",
                patch.message,
            )
            assertFailsWith<DatastarEventValidationException> { stream.removeSignals("foo-bar") }
            stream.patchSignals("form" to mapOf("first-name" to ""))
            assertEquals("event: datastar-patch-signals\ndata: signals {\"form\":{\"first-name\":\"\"}}\n\n", sink.text())
            assertEquals("aB1C", SignalNames.withoutHyphens("a-b-1--c"))
        }

    @Test
    fun `sendAll drains a flow in order`() =
        runTest {
            val sink = BufferedSseSink()
            val stream = Streamlord.Default.stream(sink)
            stream.sendAll((1..3).map { PatchSignals("""{"n":$it}""") }.asFlow())
            assertEquals(3, sink.flushCount)
            assertEquals(listOf(1, 2, 3), Regex("\"n\":(\\d)").findAll(sink.text()).map { it.groupValues[1].toInt() }.toList())
        }

    @Test
    fun `concurrent writers never interleave frames`() =
        runTest {
            val buffer = StringBuilder()
            val sink =
                object : SseSink {
                    override suspend fun write(text: String) {
                        // Write character by character with yields, inviting interleaving.
                        for (ch in text) {
                            buffer.append(ch)
                            yield()
                        }
                    }

                    override suspend fun flush() = yield()
                }
            val stream = Streamlord.Default.stream(sink)
            (1..20)
                .map { n ->
                    async { stream.send(PatchElements("""<div id="$n">$n</div>""", mode = ElementPatchMode.REPLACE)) }
                }.awaitAll()

            val frames = buffer.toString().split("\n\n").filter { it.isNotBlank() }
            assertEquals(20, frames.size)
            frames.forEach {
                assertTrue(
                    it.matches(Regex("event: datastar-patch-elements\ndata: mode replace\ndata: elements <div id=\"\\d+\">\\d+</div>")),
                    it,
                )
            }
        }
}
