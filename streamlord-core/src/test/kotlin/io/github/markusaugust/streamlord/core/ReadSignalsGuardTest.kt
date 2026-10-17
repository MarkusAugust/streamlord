package io.github.markusaugust.streamlord.core

import io.github.markusaugust.streamlord.core.application.Streamlord
import io.github.markusaugust.streamlord.core.application.readSignals
import io.github.markusaugust.streamlord.core.json.JsonObject
import io.github.markusaugust.streamlord.core.port.driven.IncomingRequest
import io.github.markusaugust.streamlord.core.port.driven.SignalsCodec
import kotlinx.coroutines.test.runTest
import kotlin.reflect.KType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

// What readSignals refuses before a codec sees the body: a type a cross-site form can send, and depth.
class ReadSignalsGuardTest {
    private class Request(
        override val method: String,
        private val contentType: String?,
        private val body: String = "",
        private val query: String? = null,
    ) : IncomingRequest {
        var bodyReads = 0

        override fun queryParameter(name: String): String? = query

        override fun header(name: String): String? = contentType?.takeIf { name.equals("Content-Type", ignoreCase = true) }

        override suspend fun bodyText(maxBytes: Int): String {
            bodyReads++
            return body
        }
    }

    private fun json(body: String) = Request("POST", "application/json", body)

    // A <form enctype="text/plain"> on another site posts this without a preflight.
    @Test
    fun `a body not declared application json is refused before it is parsed`() =
        runTest {
            val body = """{"amount":1000,"to":"gorvek","pad":"="}"""
            val types = listOf("text/plain", "application/x-www-form-urlencoded", "multipart/form-data; boundary=x", "text/json", null)
            for (contentType in types) {
                for (method in listOf("POST", "PUT", "PATCH")) {
                    val request = Request(method, contentType, body)
                    val refused =
                        assertFailsWith<SignalsMediaTypeException>("$method $contentType") { Streamlord.Default.readSignals(request) }
                    assertEquals(contentType, refused.contentType)
                    assertFailsWith<SignalsMediaTypeException> { Streamlord.Default.readSignals<Map<String, Any?>>(request) }
                    assertFailsWith<SignalsMediaTypeException> { Streamlord.Default.readSignalsJson(request) }
                }
            }
        }

    @Test
    fun `application json passes in any case and with parameters`() =
        runTest {
            for (contentType in listOf(
                "application/json",
                "Application/JSON",
                "application/json; charset=utf-8",
                " application/json ;charset=UTF-8",
            )) {
                assertEquals(1, Streamlord.Default.readSignals(Request("POST", contentType, """{"q":1}""")).int("q"), contentType)
            }
        }

    @Test
    fun `a declared type is refused before the body is read`() =
        runTest {
            val form = Request("POST", "multipart/form-data; boundary=x")
            assertFailsWith<SignalsMediaTypeException> { Streamlord.Default.readSignals(form) }
            assertEquals(0, form.bodyReads)
        }

    @Test
    fun `the check spares the query string and a request with neither body nor type, and can be turned off`() =
        runTest {
            val query = Request("GET", "text/plain", query = """{"q":"ash"}""")
            assertEquals("ash", Streamlord.Default.readSignals(query).string("q"))
            assertEquals(JsonObject.EMPTY, Streamlord.Default.readSignals(Request("POST", null, "  ")))

            val off = Streamlord(requireJsonContentType = false)
            assertEquals(1, off.readSignals(Request("POST", "text/plain", """{"q":1}""")).int("q"))
            assertEquals(1, off.readSignals(Request("POST", null, """{"q":1}""")).int("q"))
        }

    private fun nested(depth: Int) = "{\"x\":" + "[".repeat(depth - 1) + "]".repeat(depth - 1) + "}"

    @Test
    fun `signals nested past the limit never reach the codec`() =
        runTest {
            var decoded = 0
            val counting =
                object : SignalsCodec {
                    override fun encode(
                        value: Any?,
                        type: KType,
                    ): String = "{}"

                    override fun <T : Any> decode(
                        json: String,
                        type: KType,
                    ): T {
                        decoded++
                        @Suppress("UNCHECKED_CAST")
                        return emptyMap<String, Any?>() as T
                    }
                }
            val streamlord = Streamlord(codec = counting)

            streamlord.readSignals<Map<String, Any?>>(json(nested(Streamlord.MAX_SIGNALS_DEPTH)))
            assertEquals(1, decoded)
            for (depth in listOf(Streamlord.MAX_SIGNALS_DEPTH + 1, 10_000)) {
                assertFailsWith<SignalsDecodeException> { streamlord.readSignals<Map<String, Any?>>(json(nested(depth))) }
            }
            val objects = "{" + "\"a\":{".repeat(100) + "}".repeat(100) + "}"
            assertFailsWith<SignalsDecodeException> { streamlord.readSignals<Map<String, Any?>>(json(objects)) }
            assertEquals(1, decoded)

            // Brackets inside a string, an escaped quote before them, are text.
            streamlord.readSignals<Map<String, Any?>>(json("{\"s\":\"\\\"" + "[".repeat(100) + "\"}"))
            assertEquals(2, decoded)
        }

    // Depth falls again at each close: siblings, however many, are one level.
    @Test
    fun `many shallow siblings pass`() =
        runTest {
            val siblings = "{\"xs\":[" + List(150) { "[],{},[{}]" }.joinToString(",") + "]}"
            val read = Streamlord.Default.readSignals<Map<String, Any?>>(json(siblings))
            assertEquals(450, (read?.get("xs") as List<*>).size)
        }

    @Test
    fun `the built-in reader refuses the same depth`() =
        runTest {
            assertEquals(64, Streamlord.MAX_SIGNALS_DEPTH)
            Streamlord.Default.readSignals(json(nested(64)))
            assertFailsWith<SignalsDecodeException> { Streamlord.Default.readSignals<Map<String, Any?>>(json(nested(65))) }
            assertFailsWith<JsonParseException> { Streamlord.Default.readSignals(json(nested(65))) }
        }
}
