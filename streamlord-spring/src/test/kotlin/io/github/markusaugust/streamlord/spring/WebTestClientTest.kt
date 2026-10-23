package io.github.markusaugust.streamlord.spring

import io.github.markusaugust.streamlord.core.domain.DatastarEvent
import io.github.markusaugust.streamlord.core.domain.ElementPatchMode
import io.github.markusaugust.streamlord.core.domain.PatchElements
import io.github.markusaugust.streamlord.core.domain.PatchSignals
import io.github.markusaugust.streamlord.test.datastarEvents
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.springframework.http.ResponseEntity
import org.springframework.http.codec.ServerSentEvent
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import kotlin.test.Test

/** The WebFlux recipe the testing page shows, held to what it says. */
class WebTestClientTest {
    @RestController
    class Feed {
        @GetMapping("/feed")
        fun feed(): ResponseEntity<Flow<ServerSentEvent<String>>> =
            flowOf<DatastarEvent>(
                PatchElements("<li>A new head</li>", selector = "#feed", mode = ElementPatchMode.APPEND),
                PatchSignals("""{"heads":14}"""),
            ).asDatastarResponse()
    }

    @Test
    fun `the body reads back into events`() {
        val body =
            WebTestClient
                .bindToController(Feed())
                .build()
                .get()
                .uri("/feed")
                .header("Datastar-Request", "true")
                .exchange()
                .expectStatus()
                .isOk
                .expectHeader()
                .valueEquals("X-Accel-Buffering", "no")
                .expectBody(String::class.java)
                .returnResult()
                .responseBody!!

        val events = datastarEvents(body)
        events.assertPatchElements(selector = "#feed", containing = "A new head")
        events.assertSignal("heads", 14)
    }
}
