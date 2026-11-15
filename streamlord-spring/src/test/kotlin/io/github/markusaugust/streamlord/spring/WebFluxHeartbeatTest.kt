package io.github.markusaugust.streamlord.spring

import io.github.markusaugust.streamlord.core.application.Streamlord
import io.github.markusaugust.streamlord.core.domain.DatastarEvent
import io.github.markusaugust.streamlord.core.domain.PatchSignals
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.codec.ServerSentEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/** What the WebFlux page says of heartbeats, held to it: the bean's writes none, and the page's ticker does. */
class WebFluxHeartbeatTest {
    private fun quiet(gap: Duration = 300.milliseconds) =
        flow<DatastarEvent> {
            emit(PatchSignals("""{"n":1}"""))
            delay(gap)
            emit(PatchSignals("""{"n":2}"""))
        }

    @Test
    fun `the bean's heartbeat writes nothing into a quiet flow`() =
        runBlocking {
            val sent = quiet().asDatastarResponse(Streamlord(heartbeat = 50.milliseconds)).body!!.toList()
            assertEquals(listOf("datastar-patch-signals", "datastar-patch-signals"), sent.map { it.event() })
            assertEquals(listOf(null, null), sent.map { it.comment() })
        }

    @Test
    fun `a ticker merged into the flow keeps it from being silent`() =
        runBlocking {
            val keepAlive =
                flow {
                    while (true) {
                        delay(50.milliseconds)
                        emit(ServerSentEvent.builder<String>().comment("keep-alive").build())
                    }
                }
            // A gap no busy machine outlasts: three ticks come first, and taking four ends the flow.
            val response = quiet(gap = 1.minutes).asDatastarResponse()
            val merged = ResponseEntity.ok().headers(response.headers).body(merge(response.body!!, keepAlive))
            assertEquals(MediaType.TEXT_EVENT_STREAM, merged.headers.contentType)
            assertEquals("no", merged.headers.getFirst("X-Accel-Buffering"))
            val sent = merged.body!!.take(4).toList()
            assertEquals("datastar-patch-signals", sent.first().event())
            assertEquals(listOf("keep-alive", "keep-alive", "keep-alive"), sent.drop(1).map { it.comment() })
        }
}
