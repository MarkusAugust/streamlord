package io.github.markusaugust.streamlord.demo

import io.github.markusaugust.streamlord.core.domain.CspNonce
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The CSP page, against the service as it is assembled in Main.kt.
 *
 * What a browser confirms and this cannot is that Datastar accepts the result. What this holds is
 * the part that would break silently: the nonce in the markup being the nonce in the header, and
 * the policy staying off the stream endpoints.
 */
class CspTest {
    private val nonceInMarkup = Regex("""data-nonce="([^"]*)"""")

    @Test
    fun `the page carries a nonce the header blesses, and a fresh one per request`() =
        testApplication {
            application { live() }

            val seen = mutableSetOf<String>()
            repeat(3) {
                val response = client.get("/csp")
                val nonce = nonceInMarkup.find(response.bodyAsText())?.groupValues?.get(1)

                assertNotNull(nonce, "no data-nonce on the page, so Datastar would stay on new Function")
                assertEquals(policy(nonce), response.headers[CspNonce.HEADER])
                seen += nonce
            }

            assertEquals(3, seen.size, "three requests, three nonces")
        }

    @Test
    fun `the client and the style element carry the same nonce`() =
        testApplication {
            application { live() }

            val response = client.get("/csp")
            val body = response.bodyAsText()
            val nonce = nonceInMarkup.find(body)!!.groupValues[1]

            assertTrue(body.contains("""src="$DATASTAR_URL" nonce="$nonce""""), "the client script is not nonced")
            assertTrue(body.contains("""<style nonce="$nonce""""), "the style element is not nonced")

            // Without this the page proves nothing: the expressions would compile either way.
            val header = response.headers[CspNonce.HEADER]!!
            assertFalse(header.contains("unsafe-inline"), header)
            assertFalse(header.contains("unsafe-eval"), header)
        }

    /** The plugin is route-scoped here, and a policy on a stream response governs nothing. */
    @Test
    fun `the stream endpoints carry no policy`() =
        testApplication {
            application { live() }

            for (path in listOf("/health", "/modes", "/counter")) {
                assertNull(client.get(path).headers[CspNonce.HEADER], path)
            }
        }

    private companion object {
        const val DATASTAR_URL = "https://cdn.jsdelivr.net/gh/starfederation/datastar@v1.0.4/bundles/datastar.js"
    }
}
