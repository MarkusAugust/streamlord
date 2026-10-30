package io.github.markusaugust.streamlord.core

import io.github.markusaugust.streamlord.core.client.ClientRequest
import io.github.markusaugust.streamlord.core.json.JsonParser
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [ClientRequest] against `editors/vscode/test/fixtures/request-urls.json`, whose cases the
 * editors' tests read too and the VS Code suite holds to the browser's URLSearchParams.
 */
class ClientRequestTest {
    @Test
    fun `puts the signals where the client puts them`() {
        val cases = JsonParser.parseObject(File("../editors/vscode/test/fixtures/request-urls.json").readText()).array("cases")!!.objects()
        assertTrue(cases.isNotEmpty())
        for (case in cases) {
            val url = case.string("url")!!
            val method = case.string("method")!!
            val sent = if (ClientRequest.bodyless(method)) ClientRequest.withSignals(url, case.string("signals")!!) else url
            assertEquals(case.string("expected"), sent, url)
        }
    }

    @Test
    fun `sends the client's headers`() {
        val get = mapOf("Accept" to "text/event-stream, text/html, application/json", "Datastar-Request" to "true")
        assertEquals(get, ClientRequest.headers("GET"))
        assertEquals(get, ClientRequest.headers("delete"))
        assertEquals(get + ("Content-Type" to "application/json"), ClientRequest.headers("POST"))
    }
}
