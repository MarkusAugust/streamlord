package io.github.markusaugust.streamlord.core

import io.github.markusaugust.streamlord.core.application.Streamlord
import io.github.markusaugust.streamlord.core.application.readSignals
import io.github.markusaugust.streamlord.core.domain.ElementsResponse
import io.github.markusaugust.streamlord.core.domain.ScriptResponse
import io.github.markusaugust.streamlord.core.domain.SignalsResponse
import io.github.markusaugust.streamlord.core.domain.ElementPatchMode
import io.github.markusaugust.streamlord.core.json.JsonObject
import io.github.markusaugust.streamlord.core.port.driven.IncomingRequest
import io.github.markusaugust.streamlord.core.port.driven.isDatastarRequest
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReadSignalsTest {

    private class FakeRequest(
        override val method: String,
        private val query: Map<String, String> = emptyMap(),
        private val headers: Map<String, String> = emptyMap(),
        private val body: String = "",
    ) : IncomingRequest {
        var bodyReads = 0
        var lastLimit = -1
        override fun queryParameter(name: String): String? = query[name]
        override fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, true) }?.value
        override suspend fun bodyText(maxBytes: Int): String {
            bodyReads++
            lastLimit = maxBytes
            return body
        }
    }

    @Test
    fun `GET and DELETE read the datastar query parameter`() = runTest {
        for (method in listOf("GET", "delete")) {
            val req = FakeRequest(method, query = mapOf("datastar" to """{"q":"ash"}"""), body = """{"q":"body"}""")
            assertEquals("ash", Streamlord.Default.readSignals(req).string("q"))
            assertEquals(0, req.bodyReads)
        }
    }

    @Test
    fun `POST PUT PATCH and QUERY read the body`() = runTest {
        for (method in listOf("POST", "PUT", "PATCH", "QUERY")) {
            val req = FakeRequest(method, query = mapOf("datastar" to """{"q":"query"}"""), body = """{"q":"body"}""")
            assertEquals("body", Streamlord.Default.readSignals(req).string("q"))
        }
    }

    @Test
    fun `absent signals yield the empty object and null for typed reads`() = runTest {
        val req = FakeRequest("GET")
        assertEquals(JsonObject.EMPTY, Streamlord.Default.readSignals(req))
        assertNull(Streamlord.Default.readSignalsJson(req))
        assertNull(Streamlord.Default.readSignals<Map<String, Any?>>(req))
    }

    @Test
    fun `typed reads through the built-in codec`() = runTest {
        val req = FakeRequest("POST", body = """{"count":2,"name":"x"}""")
        assertEquals(mapOf("count" to 2L, "name" to "x"), Streamlord.Default.readSignals<Map<String, Any?>>(req))
        assertEquals("""{"count":2,"name":"x"}""", Streamlord.Default.readSignals<String>(req))
        assertFailsWith<SignalsCodecException> { Streamlord.Default.readSignals<ReadSignalsTest>(req) }
    }

    @Test
    fun `oversized payloads are rejected before parsing and the limit reaches the adapter`() = runTest {
        val small = Streamlord(maxSignalsSize = 10)
        val req = FakeRequest("POST", body = """{"a":"${"x".repeat(20)}"}""")
        assertFailsWith<SignalsTooLargeException> { small.readSignals(req) }
        assertEquals(10, req.lastLimit)
        val query = FakeRequest("GET", query = mapOf("datastar" to """{"a":"${"x".repeat(20)}"}"""))
        assertFailsWith<SignalsTooLargeException> { small.readSignals(query) }
    }

    @Test
    fun `invalid JSON is a parse error`() = runTest {
        val req = FakeRequest("POST", body = "{oops}")
        assertFailsWith<JsonParseException> { Streamlord.Default.readSignals(req) }
    }

    @Test
    fun `datastar request header is detected case-insensitively`() {
        assertTrue(FakeRequest("GET", headers = mapOf("datastar-request" to "TRUE")).isDatastarRequest)
        assertFalse(FakeRequest("GET").isDatastarRequest)
    }

    @Test
    fun `non-sse responses carry their options as headers`() {
        val el = ElementsResponse("<li>x</li>", selector = "#list", mode = ElementPatchMode.APPEND, useViewTransition = true)
        assertEquals(
            mapOf("datastar-selector" to "#list", "datastar-mode" to "append", "datastar-use-view-transition" to "true"),
            el.headers,
        )
        assertEquals(emptyMap(), ElementsResponse("""<div id="a"></div>""").headers)
        assertEquals(mapOf("datastar-only-if-missing" to "true"), SignalsResponse("{}", onlyIfMissing = true).headers)
        assertEquals(mapOf("datastar-script-attributes" to """{"type":"module"}"""), ScriptResponse("1", mapOf("type" to "module")).headers)
        assertFailsWith<DatastarEventValidationException> { ElementsResponse("<li>x</li>", mode = ElementPatchMode.APPEND) }
        assertFailsWith<DatastarEventValidationException> { ScriptResponse("1", mapOf("nonce" to "a\nb")) }
    }
}
