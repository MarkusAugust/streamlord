package io.github.markusaugust.streamlord.analysis

import java.net.URI
import java.net.http.HttpRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RequestsJvmTest {
    // The rule is written out by hand so it holds where there is no JDK; this holds the shared
    // verdicts to the JDK, which is the client that has to accept what we let through.
    @Test
    fun `refuses exactly the hosts and ports the HTTP client refuses`() {
        val cases = sharedCases("requests.json").filter { it.string("call") == "sends" }
        assertTrue(cases.isNotEmpty())
        for (c in cases) {
            val url = c.string("url")!!
            val jdk = runCatching { HttpRequest.newBuilder(URI(url)).build() }.isSuccess
            assertEquals(jdk, c.boolean("expected"), "$url: the JDK ${if (jdk) "sends" else "refuses"} it")
        }
    }
}
