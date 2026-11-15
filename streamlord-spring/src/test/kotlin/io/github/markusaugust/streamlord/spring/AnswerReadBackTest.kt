package io.github.markusaugust.streamlord.spring

import io.github.markusaugust.streamlord.core.domain.ElementPatchMode
import io.github.markusaugust.streamlord.test.datastarEvents
import org.springframework.http.ResponseEntity
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.test.web.servlet.assertj.MockMvcTester
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import kotlin.test.Test

/** The Testing page's reading of an answer that is no stream, with the headers Spring's test clients hand over. */
class AnswerReadBackTest {
    @RestController
    class Search {
        @GetMapping("/search")
        fun search(): ResponseEntity<String> = datastarElements("<li>Ashes</li>", selector = "#results", mode = ElementPatchMode.INNER)
    }

    @Test
    fun `MockMvcTester's response and WebTestClient's headers read back as the client reads them`() {
        val response =
            MockMvcTester
                .of(Search())
                .get()
                .uri("/search")
                .exchange()
                .response
        datastarEvents(response.contentAsString, response::getHeader)
            .assertPatchElements(selector = "#results", mode = ElementPatchMode.INNER, containing = "Ashes")

        val result =
            WebTestClient
                .bindToController(Search())
                .build()
                .get()
                .uri("/search")
                .exchange()
                .expectBody(String::class.java)
                .returnResult()
        datastarEvents(result.responseBody!!, result.responseHeaders::getFirst)
            .assertPatchElements(selector = "#results", mode = ElementPatchMode.INNER, containing = "Ashes")
    }
}
