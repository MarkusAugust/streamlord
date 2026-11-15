package io.github.markusaugust.streamlord.spring

import io.github.markusaugust.streamlord.core.SignalsMediaTypeException
import io.github.markusaugust.streamlord.core.SignalsTooLargeException
import io.github.markusaugust.streamlord.core.application.Streamlord
import io.github.markusaugust.streamlord.core.domain.DatastarEvent
import io.github.markusaugust.streamlord.core.domain.ExecuteScript
import io.github.markusaugust.streamlord.core.domain.PatchElements
import io.github.markusaugust.streamlord.core.domain.PatchSignals
import io.github.markusaugust.streamlord.core.domain.interpolate
import io.github.markusaugust.streamlord.json.jackson.JacksonSignalsCodec
import io.github.markusaugust.streamlord.test.datastarEvents
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.codec.ServerSentEvent
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebExchange
import kotlin.test.Test
import kotlin.test.assertEquals

/** WebFlux reads signals from the exchange as WebMVC reads them from the request, and a flow can redirect and remove signals. */
class WebFluxSignalsTest {
    data class Search(
        val query: String = "",
    )

    data class Quest(
        val title: String,
    )

    @RestController
    class Searches {
        @GetMapping("/search")
        suspend fun get(exchange: ServerWebExchange): String = exchange.readSignals().string("query") ?: "none"

        @PostMapping("/search")
        suspend fun post(exchange: ServerWebExchange): String = exchange.readSignalsJson() ?: "none"

        @PostMapping("/typed")
        suspend fun typed(exchange: ServerWebExchange): String =
            exchange.readSignalsOr(Search("default"), Streamlord(codec = JacksonSignalsCodec.default())).query

        @PostMapping("/small")
        suspend fun small(exchange: ServerWebExchange): String = exchange.readSignalsJson(Streamlord(maxSignalsSize = 8)) ?: "none"

        // A form sent with contentType: 'form', as signals.md shows it on WebFlux.
        @PostMapping("/quests")
        fun add(
            @ModelAttribute quest: Quest,
        ): ResponseEntity<Flow<ServerSentEvent<String>>> =
            flowOf(PatchElements(interpolate("""<li id="quest">%s</li>""", quest.title))).asDatastarResponse()

        // What the page does not show: on WebFlux a @RequestParam is read from the query alone.
        @PostMapping("/param")
        fun param(
            @RequestParam title: String,
        ): String = title

        @GetMapping("/done")
        fun done(): ResponseEntity<Flow<ServerSentEvent<String>>> =
            flowOf<DatastarEvent>(
                PatchElements("<p id=\"saved\">Saved</p>"),
                PatchSignals.remove("draft"),
                ExecuteScript.replaceUrl("/quests/7"),
                ExecuteScript.pushUrl("/quests/8"),
                ExecuteScript.redirect("/quests"),
            ).asDatastarResponse()

        @ExceptionHandler(SignalsMediaTypeException::class)
        fun mediaType(e: SignalsMediaTypeException): ResponseEntity<String> = ResponseEntity.status(415).body(e.message)

        @ExceptionHandler(SignalsTooLargeException::class)
        fun tooLarge(e: SignalsTooLargeException): ResponseEntity<String> = ResponseEntity.status(413).body(e.message)
    }

    private val client = WebTestClient.bindToController(Searches()).build()

    private fun body(spec: WebTestClient.RequestHeadersSpec<*>): String =
        spec
            .exchange()
            .expectBody(String::class.java)
            .returnResult()
            .responseBody
            .orEmpty()

    @Test
    fun `signals are read from the query on a GET and from the body on a POST`() {
        assertEquals("ash", body(client.get().uri("/search?datastar={query}", """{"query":"ash"}""")))
        assertEquals("none", body(client.get().uri("/search")))
        assertEquals(
            """{"query":"ash"}""",
            body(
                client
                    .post()
                    .uri("/search")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue("""{"query":"ash"}"""),
            ),
        )
        assertEquals("none", body(client.post().uri("/search").contentType(MediaType.APPLICATION_JSON)))
        // payload: null, as the client sends it in the query and in the body.
        assertEquals("none", body(client.get().uri("/search?datastar={n}", "null")))
        assertEquals(
            "none",
            body(
                client
                    .post()
                    .uri("/search")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue("null"),
            ),
        )
        assertEquals(
            "ash",
            body(
                client
                    .post()
                    .uri("/typed")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue("""{"query":"ash"}"""),
            ),
        )
        assertEquals("default", body(client.post().uri("/typed")))
    }

    @Test
    fun `a form is refused with a 415 that says how to read one, and a body over the limit with a 413`() {
        val form =
            body(
                client
                    .post()
                    .uri("/search")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .bodyValue("query=ash"),
            )
        assertEquals(true, "contentType: 'form'" in form, form)
        val large =
            body(
                client
                    .post()
                    .uri("/small")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue("""{"query":"ash"}"""),
            )
        assertEquals(true, "limit is 8" in large, large)
    }

    @Test
    fun `a form is read by a bound object on WebFlux, where a request parameter is not`() {
        val form = { uri: String ->
            client
                .post()
                .uri(uri)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .bodyValue("title=Ash+%26+Gorvek")
        }
        datastarEvents(body(form("/quests"))).assertPatchElements(elements = """<li id="quest">Ash &amp; Gorvek</li>""")
        form("/param").exchange().expectStatus().isBadRequest
    }

    @Test
    fun `a flow redirects, moves the address and removes signals as a stream does`() {
        val events = datastarEvents(body(client.get().uri("/done")))
        events.assertNoSignal("draft")
        events.assertPatchSignals(signals = """{"draft":null}""")
        events.assertPatchElements(containing = "window.history.replaceState(window.history.state, '', \"/quests/7\")")
        events.assertPatchElements(containing = "window.history.pushState(null, '', \"/quests/8\")")
        events.assertPatchElements(containing = "window.location.href = \"/quests\"")
    }
}
