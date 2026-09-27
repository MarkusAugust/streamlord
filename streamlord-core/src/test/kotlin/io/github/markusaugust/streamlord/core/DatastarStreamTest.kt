package io.github.markusaugust.streamlord.core

import io.github.markusaugust.streamlord.core.application.Streamlord
import io.github.markusaugust.streamlord.core.domain.ElementPatchMode
import io.github.markusaugust.streamlord.core.domain.PatchElements
import io.github.markusaugust.streamlord.core.domain.PatchSignals
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
import kotlin.test.assertTrue

class DatastarStreamTest {
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
