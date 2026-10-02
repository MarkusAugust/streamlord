package io.github.markusaugust.streamlord.ktor

import io.github.markusaugust.streamlord.core.application.StreamAuthorisation
import io.github.markusaugust.streamlord.core.port.driving.patchSignals
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.nanoseconds

class StreamAuthorisationTest {
    @Test
    fun `a refused stream ends the response rather than failing it`() =
        testApplication {
            routing {
                get("/feed") {
                    call.respondDatastar(
                        authorisation = StreamAuthorisation(every = 1.hours) { false },
                    ) {
                        patchSignals("count" to 1)
                    }
                }
            }

            val response = client.get("/feed")

            // The headers went out when the stream opened, so a refusal cannot become a 500.
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("", response.bodyAsText())
        }

    @Test
    fun `the reader is told before the stream closes`() =
        testApplication {
            routing {
                get("/feed") {
                    call.respondDatastar(
                        authorisation =
                            StreamAuthorisation(
                                every = 1.hours,
                                onRefused = { patchElements("""<p id="why">Your session expired</p>""") },
                            ) { false },
                    ) {
                        patchSignals("count" to 1)
                    }
                }
            }

            val body = client.get("/feed").bodyAsText()

            assertTrue(body.contains("Your session expired"), body)
            assertFalse(body.contains("datastar-patch-signals"), "the handler wrote after the refusal")
        }

    @Test
    fun `a stream that stays authorised is untouched`() =
        testApplication {
            routing {
                get("/feed") {
                    call.respondDatastar(
                        authorisation = StreamAuthorisation(every = 1.nanoseconds) { true },
                    ) {
                        patchSignals("count" to 1)
                        patchSignals("count" to 2)
                    }
                }
            }

            assertEquals(
                "event: datastar-patch-signals\ndata: signals {\"count\":1}\n\n" +
                    "event: datastar-patch-signals\ndata: signals {\"count\":2}\n\n",
                client.get("/feed").bodyAsText(),
            )
        }

    /** The check is ordinary Kotlin, so it can read anything the handler can. */
    @Test
    fun `the check sees what the call sees`() =
        testApplication {
            routing {
                get("/feed") {
                    val token = call.request.headers["Authorization"]
                    call.respondDatastar(
                        authorisation = StreamAuthorisation(every = 1.hours) { token == "Bearer ash" },
                    ) {
                        patchSignals("count" to 1)
                    }
                }
            }

            assertEquals("", client.get("/feed").bodyAsText())
            assertTrue(
                client
                    .get("/feed") { header("Authorization", "Bearer ash") }
                    .bodyAsText()
                    .contains("datastar-patch-signals"),
            )
        }
}
