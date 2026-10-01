package io.github.markusaugust.streamlord.ktor

import io.github.markusaugust.streamlord.core.domain.CspNonce
import io.github.markusaugust.streamlord.html.dataNonce
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.html.body
import kotlinx.html.html
import kotlinx.html.stream.createHTML
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CspNoncePluginTest {
    /** The page a reader would write: the nonce comes from the call, nowhere else. */
    private fun page(nonce: String): String =
        createHTML().html {
            dataNonce(nonce)
            body {}
        }

    private val nonceInMarkup = Regex("""data-nonce="([^"]*)"""")

    @Test
    fun `the nonce in the markup is the nonce in the header, and it is fresh per call`() =
        testApplication {
            install(CspNoncePlugin)
            routing {
                get("/") {
                    call.respondText(page(call.cspNonce), ContentType.Text.Html)
                }
            }

            val seen = mutableSetOf<String>()
            repeat(3) {
                val response = client.get("/")
                val nonce = nonceInMarkup.find(response.bodyAsText())?.groupValues?.get(1)

                assertNotNull(nonce, "the page carries no data-nonce, so Datastar stays on new Function")
                assertEquals(
                    "default-src 'self'; script-src 'self' 'nonce-$nonce'; object-src 'none'; base-uri 'self'",
                    response.headers[CspNonce.HEADER],
                )
                seen += nonce
            }

            assertEquals(3, seen.size, "three calls, three nonces")
        }

    @Test
    fun `a policy of your own is written instead, from the same nonce`() =
        testApplication {
            install(CspNoncePlugin) {
                policy = { nonce -> "script-src 'nonce-$nonce'; img-src https://cdn.example.com" }
            }
            routing { get("/") { call.respondText(page(call.cspNonce)) } }

            val response = client.get("/")
            val nonce = nonceInMarkup.find(response.bodyAsText())!!.groupValues[1]

            assertEquals(
                "script-src 'nonce-$nonce'; img-src https://cdn.example.com",
                response.headers[CspNonce.HEADER],
            )
        }

    @Test
    fun `report-only writes the other header and leaves the enforcing one alone`() =
        testApplication {
            install(CspNoncePlugin) { reportOnly = true }
            routing { get("/") { call.respondText(call.cspNonce) } }

            val response = client.get("/")

            assertNull(response.headers[CspNonce.HEADER])
            assertTrue(response.headers[CspNonce.REPORT_ONLY_HEADER]!!.contains("'nonce-${response.bodyAsText()}'"))
        }

    @Test
    fun `a null policy still generates the nonce and writes no header`() =
        testApplication {
            install(CspNoncePlugin) { policy = null }
            routing { get("/") { call.respondText(call.cspNonce) } }

            val response = client.get("/")

            assertNull(response.headers[CspNonce.HEADER])
            assertNull(response.headers[CspNonce.REPORT_ONLY_HEADER])
            assertEquals(24, response.bodyAsText().length)
        }

    @Test
    fun `a generator that returns something unusable fails the call, not the browser`() =
        testApplication {
            install(CspNoncePlugin) { nonce = { "" } }
            routing { get("/") { call.respondText(call.cspNonce) } }

            val response = client.get("/")

            assertEquals(HttpStatusCode.InternalServerError, response.status)
            assertNull(response.headers[CspNonce.HEADER], "no policy is written from a nonce that was refused")
        }

    /** An application that serves documents from some routes and streams from others. */
    @Test
    fun `installed on a route, it leaves every other route alone`() =
        testApplication {
            routing {
                route("/app") {
                    install(CspNoncePlugin)
                    get("/dashboard") { call.respondText(call.cspNonce) }
                }
                get("/feed") { call.respondText("stream") }
            }

            val document = client.get("/app/dashboard")
            val stream = client.get("/feed")

            assertTrue(document.headers[CspNonce.HEADER]!!.contains("'nonce-${document.bodyAsText()}'"))
            assertNull(stream.headers[CspNonce.HEADER], "a stream response carries no policy")
        }

    @Test
    fun `reading the nonce without the plugin says which plugin is missing`() =
        testApplication {
            routing {
                get("/") {
                    val failure = assertFailsWith<IllegalStateException> { call.cspNonce }
                    call.respondText(failure.message!!)
                }
            }

            assertTrue(client.get("/").bodyAsText().contains("CspNoncePlugin"))
        }
}
