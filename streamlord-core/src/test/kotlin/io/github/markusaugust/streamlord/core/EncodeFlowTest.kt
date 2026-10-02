package io.github.markusaugust.streamlord.core

import io.github.markusaugust.streamlord.core.application.Streamlord
import io.github.markusaugust.streamlord.core.domain.ElementPatchMode
import io.github.markusaugust.streamlord.core.domain.InterpolatedExpressionException
import io.github.markusaugust.streamlord.core.domain.PatchElements
import io.github.markusaugust.streamlord.core.domain.PatchSignals
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * `Streamlord.encode` is the facade a realm without a sink reaches for: hand it a flow of events
 * and it hands back the frames, guard applied. The Spring reactive adapter takes a different
 * route, through `toServerSentEvent`, so nothing in this repository exercised it until now.
 */
class EncodeFlowTest {
    @Test
    fun `each event becomes one frame, in order`() =
        runTest {
            val frames =
                Streamlord()
                    .encode(
                        flowOf(
                            PatchElements("<li>one</li>", selector = "#feed", mode = ElementPatchMode.APPEND),
                            PatchSignals("""{"count":1}"""),
                        ),
                    ).toList()

            assertEquals(
                listOf(
                    "event: datastar-patch-elements\n" +
                        "data: selector #feed\n" +
                        "data: mode append\n" +
                        "data: elements <li>one</li>\n\n",
                    "event: datastar-patch-signals\ndata: signals {\"count\":1}\n\n",
                ),
                frames,
            )
        }

    @Test
    fun `an empty flow encodes to nothing`() =
        runTest {
            assertEquals(emptyList(), Streamlord().encode(flowOf()).toList())
        }

    /** The guard is the reason to pass your own instance rather than the default. */
    @Test
    fun `with the guard on, an eaten expression never reaches a frame`() =
        runTest {
            val eaten = PatchElements("""<div data-text="++"></div>""")

            assertEquals(1, Streamlord().encode(flowOf(eaten)).toList().size)
            assertFailsWith<InterpolatedExpressionException> {
                Streamlord(guardElements = true).encode(flowOf(eaten)).toList()
            }
        }
}
