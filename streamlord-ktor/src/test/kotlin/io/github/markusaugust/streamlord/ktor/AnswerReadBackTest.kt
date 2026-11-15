package io.github.markusaugust.streamlord.ktor

import io.github.markusaugust.streamlord.core.domain.ElementPatchMode
import io.github.markusaugust.streamlord.test.datastarEvents
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test

/** The Testing page's reading of an answer that is no stream, run against Ktor's own answers. */
class AnswerReadBackTest {
    @Test
    fun `each answer that is no stream reads back as the client reads it`() =
        testApplication {
            routing {
                get("/search") { call.respondElements("<li>Ashes</li>", selector = "#results", mode = ElementPatchMode.INNER) }
                get("/count") { call.respondSignals("""{"heads":13}""", onlyIfMissing = true) }
                get("/go") { call.respondScript("go()", mapOf("type" to "module")) }
            }

            val response = client.get("/search")
            datastarEvents(response.bodyAsText(), response.headers::get)
                .assertPatchElements(selector = "#results", mode = ElementPatchMode.INNER, containing = "Ashes")

            val count = client.get("/count")
            datastarEvents(count.bodyAsText(), count.headers::get).run {
                assertPatchSignals(onlyIfMissing = true)
                assertSignal("heads", 13)
            }

            val go = client.get("/go")
            datastarEvents(go.bodyAsText(), go.headers::get)
                .assertPatchElements(
                    selector = "head",
                    mode = ElementPatchMode.APPEND,
                    elements = """<script type="module">go()</script>""",
                )
        }
}
