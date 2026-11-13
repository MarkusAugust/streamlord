package io.github.markusaugust.streamlord.ktor

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * `datastar-view-transition-selector`, which Datastar's client reads from a `text/html` answer from
 * 1.0.5 on (conformance/dom/response-cases.json): [respondElements] takes the selector and sends it.
 */
class ElementsViewTransitionSelectorTest {
    @Test
    fun `an answer of elements carries the view transition's selector`() =
        testApplication {
            routing {
                get("/el") { call.respondElements("<li id=\"a\">x</li>", useViewTransition = true, viewTransitionSelector = "#list") }
                get("/plain") { call.respondElements("<li id=\"a\">x</li>", viewTransitionSelector = "#list") }
            }
            val el = client.get("/el")
            assertEquals("true", el.headers["datastar-use-view-transition"])
            assertEquals("#list", el.headers["datastar-view-transition-selector"])
            assertEquals("<li id=\"a\">x</li>", el.bodyAsText())
            // Without a view transition there is none to select an element for, and the client reads no selector.
            val plain = client.get("/plain")
            assertEquals(HttpStatusCode.OK, plain.status)
            assertNull(plain.headers["datastar-view-transition-selector"])
        }
}
